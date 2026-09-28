(ns logseq.db.common.property-util
  "Property related util fns. File graphs only."
  (:require [datascript.core :as d]))

(defn get-file-pid
  "Gets file graph property id given the db graph ident"
  [db-ident]
  ;; Map of unique cases where the db graph keyword name is different than the file graph id
  (let [unique-file-ids {:logseq.property/order-list-type :logseq.order-list-type}]
    (or (get unique-file-ids db-ident)
        (keyword (name db-ident)))))

(defn get-pid
  "Get a built-in property's id (keyword name for a file graph) given its db-ident."
  [_repo db-ident]
  (get-file-pid db-ident))

(defn lookup
  "Get the property value by a built-in property's db-ident from a file-graph block."
  [repo block db-ident]
  (get (:block/properties block) (get-pid repo db-ident)))

(defn get-block-property-value
  "Get the value of built-in block's property by its db-ident"
  [repo db block db-ident]
  (when db
    (let [block (or (d/entity db (:db/id block)) block)]
      (lookup repo block db-ident))))
