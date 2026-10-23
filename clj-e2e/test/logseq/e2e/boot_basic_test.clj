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

(deftest deleted-demo-seed-page-stays-deleted-test
  ;; Deterministic guard for the interrupted-first-run-seed recovery (CI runs
  ;; 39529122322, 39864177546 attempts 4/14): recovery must only fire when the
  ;; first-run seed never completed (no seed-completed marker in the db). A
  ;; seed page the user intentionally deleted AFTER a completed first run
  ;; must stay deleted across a reload — recovery must not resurrect it —
  ;; while user edits in other seed pages survive.
  ;;
  ;; The positive recovery path (interrupted seed -> missing seed data
  ;; recreated) is exercised by the refresh-mid-seed repro sweep recorded in
  ;; .pi/tasks/task-20261021-e2e-plugins-page-not-found-boot-flake.md; it
  ;; cannot be reached deterministically through the UI once the seed has
  ;; completed, because page deletion is only possible after the marker is
  ;; durably written.
  (w/with-page-open
    (w/make-page {:headless @config/*headless
                  :persistent false
                  :slow-mo 0
                  :default-timeout @config/*default-timeout})
    (w/navigate (pw-page/get-test-url nil))
    (assert/assert-graph-loaded?)
    ;; a user edit in a seed page that must survive (Contents is excluded
    ;; from page-menu delete, so it is the page that survives)
    (page/goto-page "Contents")
    ;; Contents' seed block is empty (contents.md is "-"), so its
    ;; .block-content is zero-size and unclickable; use the block add button,
    ;; which inserts a new block straight into edit mode.
    (w/click ".ls-page-blocks .block-add-button")
    (assert/assert-editor-mode)
    (b/save-block "e2e persisted edit")
    (k/esc)
    ;; delete the seeded journal page. The first-run seed has completed by
    ;; now (the delete UI only works after boot finished, which is gated on
    ;; the seed-completed marker being durable), so the restored db on next
    ;; boot carries the marker and recovery must NOT fire.
    (page/delete-page "Dec 26th, 2020")
    ;; the delete only round-trips through the db-worker once the app has
    ;; redirected home, so wait for the journals view before refreshing
    (w/wait-for "#journals")
    (w/refresh)
    (assert/assert-graph-loaded?)
    ;; the intentionally deleted seed page stays deleted: searching for it
    ;; offers only to create a new page, with no title match
    (util/search "Dec 26th, 2020")
    (assert/assert-is-visible "text=Create page called 'Dec 26th, 2020'")
    (is (zero? (.count (w/get-by-test-id "Dec 26th, 2020")))
        "recovery did not resurrect the intentionally deleted seed journal page")
    (k/esc)
    ;; ...and the surviving seed page was never reparsed/rewritten: the user
    ;; edit is still there
    (page/goto-page "Contents")
    (is (true? (assert/assert-is-visible "text=e2e persisted edit"))
        "user edit in the surviving seed page preserved across reload")))
