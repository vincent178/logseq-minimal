(ns frontend.worker.pipeline
  "Pipeline work after transaction"
  (:require [datascript.core :as d]
            [frontend.worker.file :as file]
            [frontend.worker.react :as worker-react]
            [frontend.worker.state :as worker-state]
            [logseq.db :as ldb]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.datascript-report :as ds-report]
            [logseq.outliner.pipeline :as outliner-pipeline]))

(defn- refs-need-recalculated?
  [tx-meta]
  (let [outliner-op (:outliner-op tx-meta)]
    (not (or
          (contains? #{:collapse-expand-blocks :delete-blocks} outliner-op)
          (:undo? tx-meta) (:redo? tx-meta)))))

(defn- rebuild-block-refs
  [repo {:keys [tx-meta db-after] :as _tx-report} blocks]
  (when (or (and (:outliner-op tx-meta) (refs-need-recalculated? tx-meta))
            (:rtc-tx? tx-meta)
            (:rtc-op? tx-meta))
    (mapcat (fn [block]
              (when (d/entity db-after (:db/id block))
                (let [date-formatter (worker-state/get-date-formatter repo)
                      refs (->> (outliner-core/rebuild-block-refs repo db-after date-formatter block) set)]
                  ;; retract all refs for file graphs because we can't ensure `refs` are all db ids
                  (cond-> [[:db/retract (:db/id block) :block/refs]]
                    (seq refs)
                    (conj {:db/id (:db/id block)
                           :block/refs refs})))))
            blocks)))


(defn- remove-conflict-datoms
  [datoms]
  (->> datoms
       (group-by (fn [d] (take 4 d))) ; group by '(e a v tx)
       (keep (fn [[_eavt same-eavt-datoms]]
               (first (rseq same-eavt-datoms))))
       ;; sort by :tx, use nth to make this fn works on both vector and datom
       (sort-by #(nth % 3))))

(defn transact-pipeline
  "Compute block/refs and tx-ids, should ensure it's a pure function and
  doesn't call `d/transact!` or `ldb/transact!`."
  [repo {:keys [_db-after tx-meta] :as tx-report}]
  (let [tx-report* tx-report
        {:keys [pages blocks]} (ds-report/get-blocks-and-pages tx-report*)
        deleted-blocks (outliner-pipeline/filter-deleted-blocks (:tx-data tx-report*))
        deleted-block-ids (set (map :db/id deleted-blocks))
        blocks' (remove (fn [b] (deleted-block-ids (:db/id b))) blocks)
        block-refs (when (seq blocks')
                     (rebuild-block-refs repo tx-report* blocks'))
        tx-id-data (let [db-after (:db-after tx-report*)
                         updated-blocks (remove (fn [b] (contains? deleted-block-ids (:db/id b)))
                                                (concat pages blocks))
                         tx-id (get-in tx-report* [:tempids :db/current-tx])]
                     (keep (fn [b]
                             (when-let [db-id (:db/id b)]
                               (when (:block/uuid (d/entity db-after db-id))
                                 {:db/id db-id
                                  :block/tx-id tx-id}))) updated-blocks))
        block-refs-tx-id-data (concat block-refs tx-id-data)
        replace-tx-report (when (seq block-refs-tx-id-data)
                            (d/with (:db-after tx-report*) block-refs-tx-id-data))
        tx-report' (or replace-tx-report tx-report*)
        full-tx-data (-> (concat (:tx-data tx-report*)
                                 (:tx-data replace-tx-report))
                         remove-conflict-datoms)]
    (assoc tx-report'
           :tx-data full-tx-data
           :tx-meta tx-meta
           :db-before (:db-before tx-report)
           :db-after (or (:db-after tx-report')
                         (:db-after tx-report)))))

(defn- invoke-hooks-default
  [repo conn {:keys [tx-meta] :as tx-report} context]
  (try
    (let [{:keys [pages blocks]} (ds-report/get-blocks-and-pages tx-report)
          deleted-blocks (outliner-pipeline/filter-deleted-blocks (:tx-data tx-report))
          _ (let [page-ids (distinct (map :db/id pages))]
              (doseq [page-id page-ids]
                (when (d/entity @conn page-id)
                  (file/sync-to-file repo page-id tx-meta))))
          deleted-block-uuids (set (map :block/uuid deleted-blocks))
          deleted-block-ids (set (map :db/id deleted-blocks))
          _ (when (seq deleted-block-uuids)
              (swap! worker-state/*deleted-block-uuid->db-id merge
                     (zipmap (map :block/uuid deleted-blocks)
                             (map :db/id deleted-blocks))))
          deleted-assets (keep (fn [id]
                                 (let [e (d/entity (:db-before tx-report) id)]
                                   (when (ldb/asset? e)
                                     {:block/uuid (:block/uuid e)
                                      :ext (:logseq.property.asset/type e)}))) deleted-block-ids)
          affected-query-keys (when-not (or (:importing? context) (:rtc-download-graph? tx-meta))
                                (worker-react/get-affected-queries-keys tx-report))]
      {:tx-report tx-report
       :affected-keys affected-query-keys
       :deleted-block-uuids deleted-block-uuids
       :deleted-assets deleted-assets
       :pages pages
       :blocks blocks})
    (catch :default e
      (js/console.error e)
      (throw e))))

(defn invoke-hooks
  [repo conn {:keys [tx-meta] :as tx-report} context]
  (let [{:keys [from-disk? new-graph? transact-new-graph-refs?]} tx-meta]
    (when-not transact-new-graph-refs?
      (if (or from-disk? new-graph?)
        {:tx-report tx-report}
        (invoke-hooks-default repo conn tx-report context)))))
