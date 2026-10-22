(ns logseq.e2e.fixtures
  (:require [logseq.e2e.assert :as assert]
            [logseq.e2e.config :as config]
            [logseq.e2e.const :refer [*page1 *page2]]
            [logseq.e2e.custom-report :as custom-report]
            [logseq.e2e.graph :as graph]
            [logseq.e2e.page :as page]
            [logseq.e2e.playwright-page :as pw-page]
            [logseq.e2e.settings :as settings]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

;; TODO: save trace
;; TODO: parallel support

(defn- attach-console-log-listener!
  "Attach BEFORE navigating so SPA boot messages are captured too (a boot
  failure is exactly when we need them most)."
  [p]
  (.onConsoleMessage p (fn [msg]
                         (when custom-report/*pw-page->console-logs*
                           (swap! custom-report/*pw-page->console-logs* update p conj (.text msg))))))

(defn- boot-graph!
  "navigate -> developer-mode -> refresh -> wait for the graph to render.
  Runs in :once fixtures, i.e. OUTSIDE clojure.test's per-var error
  reporting: without the try/catch a boot failure escapes `run-tests` as a
  bare \"Execution error\" and no screenshot/console-log dump happens."
  [port]
  (try
    (w/navigate (pw-page/get-test-url port))
    (settings/developer-mode)
    (w/refresh)
    (assert/assert-graph-loaded?)
    (catch Throwable e
      (println "Graph failed to load during fixture setup; dumping diagnostics to e2e-dump/")
      (custom-report/collect-info-when-error-or-failed)
      (throw e))))

(defn open-page
  [f & {:keys [headless port]}]
  (w/with-page-open
    (w/make-page {:headless (or headless @config/*headless)
                  :persistent false
                  :slow-mo @config/*slow-mo
                  :default-timeout @config/*default-timeout})
    (w/grant-permissions :clipboard-write :clipboard-read)
    (binding [custom-report/*pw-contexts* #{(.context (w/get-page))}
              custom-report/*pw-page->console-logs* (atom {})]
      (w/grant-permissions :clipboard-write :clipboard-read)
      (attach-console-log-listener! (w/get-page))
      (boot-graph! port)
      (f))))

(defn open-2-pages
  "Use `*page1` and `*page2` in `f`"
  [f & {:keys [headless port]}]
  (let [headless (or headless @config/*headless)
        page-opts {:headless headless
                   :persistent false
                   :slow-mo @config/*slow-mo
                   :default-timeout @config/*default-timeout}
        p1 (w/make-page page-opts)
        p2 (w/make-page page-opts)
        port' (or port @config/*port)]
    (reset! *page1 p1)
    (reset! *page2 p2)
    (binding [custom-report/*pw-contexts* (set [(.context @p1) (.context @p2)])
              custom-report/*pw-page->console-logs* (atom {})
              w/*page* (delay (throw (ex-info "Don't use *page*, use *page1* and *page2* instead" {})))]
      (run!
       #(w/with-page %
          (w/grant-permissions :clipboard-write :clipboard-read)
          (attach-console-log-listener! (w/get-page))
          (boot-graph! port))
       [p1 p2])
      (f))

    ;; use with-page-open to release resources
    (w/with-page-open p1)
    (w/with-page-open p2)
    (reset! *page1 nil)
    (reset! *page2 nil)))

(def ^:dynamic *pw-ctx* nil)
(defn open-new-context
  "create a new playwright-context in `*pw-ctx*`"
  [f]
  (let [page-opts {:headless @config/*headless
                   :persistent false
                   :slow-mo @config/*slow-mo
                   :default-timeout @config/*default-timeout}
        p @(w/make-page page-opts)
        ctx (.newContext (.browser (.context p)))]
    ;; context for p is no longer needed
    (.close (.context p))
    (w/with-page-open p)              ; use with-page-open to close playwright instance
    (binding [custom-report/*pw-contexts* #{ctx}
              *pw-ctx* ctx]
      (f)
      (.close (.browser *pw-ctx*)))))

(defonce *page-number (atom 0))

(defn- with-error-dump
  "Run `f`; on any exception dump diagnostics (screenshots/console logs) to
  e2e-dump/ before rethrowing. Exceptions thrown in :each fixtures escape
  clojure.test's per-test reporting path entirely (the run dies with a bare
  \"Execution error\" and CI uploads no artifacts), so without this a fixture
  failure leaves zero evidence. Same pattern as `boot-graph!` for :once
  fixtures.

  The diagnostic collection itself is best-effort: it does Playwright
  screenshots + file writes, which can throw (e.g. a closed/crashed page).
  A collection failure must never replace the original fixture exception —
  it is attached as suppressed and the original is always rethrown."
  [f]
  (try
    (f)
    (catch Throwable e
      (println "Exception in :each fixture; dumping diagnostics to e2e-dump/")
      (try
        (custom-report/collect-info-when-error-or-failed)
        (catch Throwable dump-error
          (println "Diagnostic collection failed:" (ex-message dump-error))
          (.addSuppressed e dump-error)))
      (throw e))))

(defn create-page
  [& [page-name]]
  (let [page-name (or page-name (str "page " (swap! *page-number inc)))]
    (page/new-page page-name)
    page-name))

(defn new-logseq-page
  [f]
  (with-error-dump create-page)
  (f))

(defn validate-graph
  [f]
  (f)
  (with-error-dump
   (fn []
     (if (and @*page1 @*page2)
       (doseq [p [@*page1 @*page2]]
         (w/with-page p
           (graph/validate-graph)))

       (graph/validate-graph)))))


