(ns logseq.e2e.boot-basic-test
  "Regression test for the flaky boot hang behind the CI failure
  \"Timeout 30000ms exceeded waiting for [data-testid='page title']\" at
  namespace startup.

  Root cause: the db-worker elects a shared-service master per graph via a
  Web Lock named after the graph (\"Demo\" for the e2e demo graph). On
  page refresh the previous worker is destroyed while the browser releases
  its lock asynchronously; a new worker booting inside that window loses the
  election to the dead master, registers as a slave, and (before the fix)
  waited forever for a registration ack that could never come, hanging the
  whole app boot.

  This test emulates that window deterministically: a foreign page holds the
  \"Demo\" lock for 2.5s without listening on the service BroadcastChannel
  (i.e. a dead master), while the app boots in another page of the same
  browser context."
  (:require [clojure.test :refer [deftest is]]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.config :as config]
            [logseq.e2e.playwright-page :as pw-page]
            [wally.main :as w]))

(deftest boot-while-stale-service-lock-held-test
  (w/with-page-open
    (w/make-page {:headless @config/*headless
                  :persistent false
                  :slow-mo 0
                  :default-timeout @config/*default-timeout})
    (let [seed (w/get-page)
          browser (.browser (.context seed))
          ctx (.newContext browser)
          _ (.close (.context seed))
          ;; A dead-master stand-in: holds the graph service lock briefly,
          ;; never acks slave registrations.
          locker (.newPage ctx)
          _ (.navigate locker (str "http://localhost:" @config/*port "/manifest.json"))
          _ (.evaluate locker
                       "async () => {
                          navigator.locks.request('Demo', () => new Promise(r => setTimeout(r, 2500)));
                          for (let i = 0; i < 100; i++) {
                            const s = await navigator.locks.query();
                            if ((s.held || []).some(l => l.name === 'Demo')) return true;
                            await new Promise(r => setTimeout(r, 20));
                          }
                          return false;
                        }")
          page (.newPage ctx)]
      (.navigate page (pw-page/get-test-url nil))
      (is (true? (w/with-page page (assert/assert-graph-loaded?)))
          "App boots even when the graph service lock is initially held by a stale (dead) master")
      (.close ctx))))

(deftest refresh-during-first-run-seed-test
  ;; Regression test for the boot-time "Page not found" flake (CI runs
  ;; 39529122322, 39864177546 attempts 4/14): the first-run demo-graph seed
  ;; parses files asynchronously, so a refresh before the seed's txes reach
  ;; the db-worker store restores a partial db on the next boot — the seeded
  ;; page is missing and the route from boot 1's redirect renders "Page not
  ;; found" forever. The fix re-seeds the demo graph on restore when the
  ;; seeded files are missing.
  ;;
  ;; The refresh below lands in the seeding window with high probability
  ;; (header renders well before the initial parse completes), but the
  ;; assertion must hold either way: a restored demo graph always renders
  ;; its content.
  (w/with-page-open
    (w/make-page {:headless @config/*headless
                  :persistent false
                  :slow-mo 0
                  :default-timeout @config/*default-timeout})
    (w/navigate (pw-page/get-test-url nil))
    ;; Wait only for the app chrome (as fixtures/developer-mode does), not
    ;; for the graph to finish seeding, then refresh mid-seed.
    (.waitFor (.first (w/-query "button[title='More']")))
    (w/refresh)
    (is (true? (assert/assert-graph-loaded?))
        "App renders the graph after a refresh that interrupted first-run seeding")
    (is (true? (assert/assert-is-visible "text=Welcome to Logseq!"))
        "Seeded journal content survives a mid-seed refresh (re-seeded on restore)")))
