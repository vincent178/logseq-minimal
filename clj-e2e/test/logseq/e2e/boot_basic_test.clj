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
            [logseq.e2e.block :as b]
            [logseq.e2e.config :as config]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.page :as page]
            [logseq.e2e.playwright-page :as pw-page]
            [logseq.e2e.util :as util]
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

(deftest interrupted-seed-recovery-test
  ;; Deterministic regression test for the boot-time "Page not found" flake
  ;; (CI runs 39529122322, 39864177546 attempts 4/14): a reload during
  ;; first-run seeding restores a partial db; recovery on the restore path
  ;; must recreate the missing seed data.
  ;;
  ;; Instead of racing the seed with a refresh (probabilistic), this test
  ;; puts the graph into the persisted-but-incomplete state directly: it
  ;; deletes the seeded journal page while keeping Contents (with a user
  ;; edit), then refreshes. Recovery must recreate exactly the missing seed
  ;; page and must NOT reparse/rewrite the surviving seed page — the user
  ;; edit added below must survive.
  (w/with-page-open
    (w/make-page {:headless @config/*headless
                  :persistent false
                  :slow-mo 0
                  :default-timeout @config/*default-timeout})
    (w/navigate (pw-page/get-test-url nil))
    (assert/assert-graph-loaded?)
    ;; a user edit in a seed page that recovery must not clobber (Contents is
    ;; excluded from page-menu delete, so it is the page that survives)
    (page/goto-page "Contents")
    ;; Contents' seed block is empty (contents.md is "-"), so its
    ;; .block-content is zero-size and unclickable; use the block add button,
    ;; which inserts a new block straight into edit mode.
    (w/click ".ls-page-blocks .block-add-button")
    (assert/assert-editor-mode)
    (b/save-block "e2e persisted edit")
    (k/esc)
    ;; delete the seeded journal page -> the restored db on next boot has an
    ;; incomplete seed (journals/2020_12_26.md missing, contents.md intact)
    (page/delete-page "Dec 26th, 2020")
    ;; the delete only round-trips through the db-worker once the app has
    ;; redirected home, so wait for the journals view before refreshing
    (w/wait-for "#journals")
    (w/refresh)
    (assert/assert-graph-loaded?)
    ;; recovery recreated exactly the missing seed page (a missing journal
    ;; would make the search-click time out instead)
    (page/goto-page "Dec 26th, 2020")
    (is (true? (assert/assert-is-visible "div[data-testid='page title']:has-text('Dec 26th, 2020')"))
        "recovery recreated the deleted seed journal page")
    (is (true? (assert/assert-is-visible "text=Welcome to Logseq!"))
        "recreated journal has the seed content")
    ;; ...and left the surviving seed page untouched: the user edit is still there
    (page/goto-page "Contents")
    (is (true? (assert/assert-is-visible "text=e2e persisted edit"))
        "recovery did not reparse/rewrite the surviving seed page (user edit preserved)")))
