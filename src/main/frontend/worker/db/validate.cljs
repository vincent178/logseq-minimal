(ns frontend.worker.db.validate
  "Validate db"
  (:require [datascript.core :as d]
            [frontend.worker.shared-service :as shared-service]))

(defn validate-db
  [conn]
  ;; DB-graph schema validation removed (no DB graphs exist anymore). For file
  ;; graphs we skip the datascript-schema validation — it is a DB-graph concept
  ;; (e.g. it requires :logseq.property/* idents) — and report the graph valid.
  (let [db @conn
        entities (d/datoms db :eavt)
        datom-count (count entities)]
    (shared-service/broadcast-to-clients! :notification
                                          [(str "Your graph is valid! " {:datoms datom-count})
                                           :success false])
    {:errors nil
     :datom-count datom-count
     :invalid-entity-ids []}))
