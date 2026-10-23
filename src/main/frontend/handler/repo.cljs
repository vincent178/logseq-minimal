(ns frontend.handler.repo
  "System-component-like ns that manages user's repos/graphs"
  (:refer-clojure :exclude [clone])
  (:require [cljs-bean.core :as bean]
            [clojure.core.async :as async]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.persist :as db-persist]
            [frontend.date :as date]
            [frontend.db.restore :as db-restore]
            [frontend.fs :as fs]
            [frontend.handler.file-based.file :as file-handler]
            [frontend.handler.file-based.repo :as file-repo-handler]
            [frontend.handler.global-config :as global-config-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo-config :as repo-config-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.idb :as idb]
            [frontend.persist-db :as persist-db]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.undo-redo :as undo-redo]
            [frontend.util :as util]
            [frontend.util.text :as text-util]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [promesa.core :as p]
            [shadow.resource :as rc]))

;; Project settings should be checked in two situations:
;; 1. User changes the config.edn directly in logseq.com (fn: alter-file)
;; 2. Git pulls the new change (fn: load-files)

(defn remove-repo!
  [{:keys [url] :as repo} & {:keys [switch-graph?]
                             :or {switch-graph? true}}]
  (let [current-repo (state/get-current-repo)]
    (p/do!
     (idb/clear-local-db! url)     ; clear file handles
     (db/remove-conn! url)
     (db-persist/delete-graph! url)
     (search/remove-db! url)
     (state/delete-repo! repo)
     (when switch-graph?
       (if (= current-repo url)
         (do
           (state/set-current-repo! nil)
           (when-let [graph (:url (first (state/get-repos)))]
             (notification/show! (str "Removed graph "
                                      (pr-str (text-util/get-graph-name-from-path url))
                                      ". Redirecting to graph "
                                      (pr-str (text-util/get-graph-name-from-path graph)))
                                 :success)
             (state/pub-event! [:graph/switch graph {:persist? false}])))
         (notification/show! (str "Removed graph " (pr-str (text-util/get-graph-name-from-path url))) :success))))))

(defn start-repo-db-if-not-exists!
  [repo & {:as opts}]
  (state/set-current-repo! repo)
  (db/start-db-conn! repo (assoc opts
                                 :listen-handler (fn [conn]
                                                   (undo-redo/listen-db-changes! repo conn)))))

(declare <reseed-demo-graph-if-needed!)

(defn restore-and-setup-repo!
  "Restore the db of a graph from the persisted data, and setup. Create a new
  conn, or replace the conn in state with a new one."
  [repo & {:as opts}]
  (p/do!
   (state/set-db-restoring! true)
   (db-restore/restore-graph! repo opts)
   (<reseed-demo-graph-if-needed! repo)
   (repo-config-handler/restore-repo-config! repo)
   (when (config/global-config-enabled?)
     (global-config-handler/restore-global-config!))
    ;; Don't have to unlisten the old listener, as it will be destroyed with the conn
   (when-not (true? (:ignore-style? opts))
     (ui-handler/add-style-if-exists!))
   (state/set-db-restoring! false)))

(defn re-index!
  [nfs-rebuild-index! ok-handler]
  (when-let [repo (state/get-current-repo)]
    (state/reset-parsing-state!)
    (let [dir (config/get-repo-dir repo)]
      (when-not (state/unlinked-dir? dir)
        (route-handler/redirect-to-home!)
        (nfs-rebuild-index! repo ok-handler)
        (js/setTimeout
         (route-handler/redirect-to-home!)
         500)))))

(defn get-repos
  []
  (p/let [nfs-dbs (db-persist/get-all-graphs)
          nfs-dbs (map (fn [db]
                         (let [graph-name (:name db)]
                           {:url graph-name
                            :metadata (:metadata db)
                            :root (config/get-local-dir graph-name)
                            :nfs? true}))
                       nfs-dbs)
          nfs-dbs (and (seq nfs-dbs)
                       (cond (util/electron?)
                             (p/chain
                              (ipc/ipc :inflateGraphsInfo (ldb/write-transit-str nfs-dbs))
                              ldb/read-transit-str)

                             :else
                             nfs-dbs))]
    (seq (bean/->clj nfs-dbs))))

(defn get-detail-graph-info
  [url]
  (when-let [graphs (seq (and url (state/get-repos)))]
    (first (filter #(when-let [url' (:url %)]
                      (= url url')) graphs))))

(defn refresh-repos!
  []
  (p/let [repos (get-repos)]
    (state/set-repos! repos)
    repos))

(defn graph-ready!
  ;; FIXME: Call electron that the graph is loaded, an ugly implementation for redirect to page when graph is restored
  [graph]
  (when (util/electron?)
    (ipc/ipc "graphReady" graph)))

;; DB-graph creation removed (minimal build is file-graphs only).
;; Exception: the first-run demo graph is still created so a fresh launch (and
;; the e2e suite, which has no native folder picker) opens into a working graph.

(def ^:private demo-graph-dir "memory:///local")

(def ^:private demo-graph-journal-content
  "- Welcome to Logseq!\n- This is a demo graph stored in memory.")

(def ^:private demo-graph-seed-files
  "The demo graph's deterministic first-run content, as [file-rpath content]
  pairs. `logseq/config.edn` is deliberately not included: it carries no pages
  and the app falls back to the default config when it is missing."
  [["journals/2020_12_26.md" demo-graph-journal-content]
   ["pages/contents.md" (rc/inline "templates/contents.md")]])

(defn- <write-demo-graph-files!
  [repo dir]
  (p/do! (fs/mkdir-recur! (str dir "/journals"))
         (fs/mkdir-recur! (str dir "/pages"))
         (fs/mkdir-recur! (str dir "/logseq"))
         (fs/write-plain-text-file! repo dir "journals/2020_12_26.md"
                                    demo-graph-journal-content {})
         (fs/write-plain-text-file! repo dir "logseq/config.edn" config/config-default-content {})))

(defn- <parse-demo-graph-files!
  "Parse the demo seed files into the db, resolving only once the initial parse
  has completed. The wait matters: parsing is what makes the seed durable in
  the db-worker's store, so a reload (or an e2e fixture refreshing the page)
  right after first-run seeding must not observe a partial db."
  [repo new-graph?]
  (let [graph-added-chan (file-repo-handler/load-new-repo-to-db! repo {:new-graph? new-graph?
                                                                       :empty-graph? false
                                                                       :file-objs [{:file/path "journals/2020_12_26.md"
                                                                                    :file/content demo-graph-journal-content}]})]
    (p/create (fn [resolve _reject]
                (async/take! graph-added-chan resolve)))))

(defn- demo-graph-missing-seed-files
  "Seed files absent from the db, i.e. seed data that was never durably
  created. Only non-empty when a reload interrupted the first-run seed: the
  restored sqlite snapshot predates the parse txes while the in-memory fs that
  held the seed files is already wiped, so nothing would ever re-add the
  missing pages and the app renders \"Page not found\" for the page route the
  first boot redirected to."
  [repo]
  (into []
        (keep (fn [[file-rpath _content]]
                (when (nil? (db/get-file repo file-rpath))
                  file-rpath)))
        demo-graph-seed-files))

(defn- <create-demo-seed-file!
  "Create one missing seed file: writes the canned content only when the file
  is not on disk (never overwrites an existing file), then parses whatever is
  on disk into the db. When the file exists on disk its content is preferred
  (it can hold user edits the db snapshot predates); the read-back falls back
  to the canned content because lightning-fs can occasionally fail to read a
  torn file (stat succeeded but content blob missing)."
  [repo file-rpath canned-content]
  (p/let [exists? (fs/create-if-not-exists repo demo-graph-dir file-rpath canned-content)
          content (if exists?
                    (-> (fs/read-file demo-graph-dir file-rpath)
                        (p/catch (fn [_] canned-content)))
                    canned-content)]
    (file-handler/reset-file! repo file-rpath content {})))

(defn <reseed-demo-graph-if-needed!
  "Recreate the demo graph's MISSING seed files when a previous boot was
  reloaded mid-seed. Creates only seed data that is absent from the db —
  existing files/pages (and any user edits in the demo graph) are never
  rewritten or reparsed. Called on the restore path, where the worker and
  frontend conns already exist."
  [repo]
  ;; `=`, not `config/demo-graph?`: the latter also matches any user graph
  ;; whose name ends in "Demo", which must never get canned content.
  (when (= repo config/demo-repo)
    (let [missing (set (demo-graph-missing-seed-files repo))]
      (when (seq missing)
        (log/warn ::reseed-demo-graph "Demo graph seed is incomplete (interrupted first-run seed); recreating missing seed files"
                  :repo repo :files missing)
        (-> (p/do! (fs/mkdir-recur! (str demo-graph-dir "/journals"))
                   (fs/mkdir-recur! (str demo-graph-dir "/pages"))
                   (p/all (map (fn [[file-rpath content]]
                                 (<create-demo-seed-file! repo file-rpath content))
                               (filter #(contains? missing (first %))
                                       demo-graph-seed-files))))
            ;; recovery is best-effort self-heal: a failure here must not
            ;; abort the surrounding restore/boot chain
            (p/catch (fn [e]
                       (log/error ::reseed-demo-graph-failed e))))))))

(defn create-demo-file-graph!
  "Create the first-run demo file graph. Web/e2e only (Electron lands on the
  folder picker). Uses the in-memory memory-fs backend, so no directory picker
  or SQLite is needed."
  []
  (let [repo config/demo-repo
        dir demo-graph-dir
        today (date/today)]
    (-> (p/do! (<write-demo-graph-files! repo dir)
               ;; register the worker-side conn (same as the NFS open flow)
               (persist-db/<new repo {})
               (start-repo-db-if-not-exists! repo)
               (<parse-demo-graph-files! repo true)
               (state/add-repo! {:url repo :root dir}))
        (p/then (fn [_]
                  (state/pub-event! [:shortcut/refresh])
                  (state/pub-event! [:init/commands])
                  (state/pub-event! [:page/create today {:redirect? false}])
                  ;; land on the seeded journal page (e2e expects a page title)
                  (route-handler/redirect-to-page! "Dec 26th, 2020")
                  repo))
        (p/catch (fn [error]
                   (notification/show! "Create demo graph failed." :error)
                   (js/console.error error))))))

(defn gc-graph!
  [graph]
  (p/do!
   (state/<invoke-db-worker :thread-api/gc-graph graph)
   (state/pub-event! [:notification/show
                      {:content "Graph gc successfully!"
                       :status :success}])))
