(ns frontend.test.helper
  "Common helper fns for tests"
  (:require [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.conn :as conn]
            [frontend.handler.file-based.repo :as file-repo-handler]
            [frontend.state :as state]
            [frontend.worker.handler.page :as worker-page]
            [frontend.worker.pipeline :as worker-pipeline]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.common.order :as db-order]))

(def test-db-name "test-db")
(def test-db test-db-name)

(defn start-test-db!
  [& {:as opts}]
  (let [test-db' test-db-name]
    (state/set-current-repo! test-db')
    (conn/start! test-db' opts)
    (ldb/register-transact-pipeline-fn!
     (fn [tx-report]
       (worker-pipeline/transact-pipeline test-db' tx-report)))
    (let [conn (conn/get-db test-db' false)]
      (d/listen! conn ::listen-db-changes!
                 (fn [tx-report]
                   (worker-pipeline/invoke-hooks test-db' conn tx-report {}))))))

(defn destroy-test-db!
  []
  (conn/destroy-all!))

(defn load-test-files-for-db-graph
  "Removed with DB graphs. Only referenced by dead `(when js/process.env.DB_GRAPH ...)`
  test branches which never execute in this file-graphs-only build."
  [& _]
  (throw (ex-info "DB-graph test helpers have been removed" {})))

(defn load-test-files
  "Given a collection of file maps, loads them into the current test-db.
This can be called in synchronous contexts as no async fns should be invoked"
  [files]
  (file-repo-handler/parse-files-and-load-to-db!
   test-db
   files
   ;; Set :refresh? to avoid creating default files in after-parse
   {:re-render? false :verbose false :refresh? true}))

(defn start-and-destroy-db
  "Sets up a db connection and current repo like fixtures/reset-datascript. It
  also seeds the db with the same default data that the app does and destroys a db
  connection when done with it."
  [f & {:as start-opts}]
  ;; Set current-repo explicitly since it's not the default
  (let [repo test-db-name]
    (state/set-current-repo! repo)
    (start-test-db! start-opts)
    (when-let [init-f (:init-data start-opts)]
      (assert (fn? f) "init-data should be a fn")
      (init-f (db/get-db repo false)))
    (f)
    (state/set-current-repo! nil)
    (destroy-test-db!)))

(defn initial-test-page-and-blocks
  [& {:keys [page-uuid]}]
  (let [page-uuid (or page-uuid (random-uuid))
        first-block-uuid (random-uuid)
        second-block-uuid (random-uuid)
        page-id [:block/uuid page-uuid]]
    (->>
     [;; page
      {:block/uuid page-uuid
       :block/name "test"
       :block/title "Test"}
      ;; first block
      {:block/uuid first-block-uuid
       :block/page page-id
       :block/parent page-id
       :block/order (db-order/gen-key nil)
       :block/title "block 1"}
      ;; second block
      {:block/uuid second-block-uuid
       :block/page page-id
       :block/parent page-id
       :block/order (db-order/gen-key nil)
       :block/title "block 2"}]
     (map common-util/block-with-timestamps))))

(defn create-page!
  [title & {:as opts}]
  (let [repo (state/get-current-repo)
        conn (db/get-db repo false)
        config (state/get-config repo)
        [page-name _page-uuid] (worker-page/create! repo conn config title opts)]
    page-name))
