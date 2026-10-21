(ns logseq.outliner.pipeline
  "Core fns for use with frontend worker and node")

(defn filter-deleted-blocks
  [datoms]
  (keep
   (fn [d]
     (when (and (= :block/uuid (:a d)) (false? (:added d)))
       {:db/id (:e d)
        :block/uuid (:v d)}))
   datoms))
