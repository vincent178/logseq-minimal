(ns logseq.api.block
  "Block related apis"
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.model :as db-model]
            [frontend.db.utils :as db-utils]
            [frontend.modules.outliner.tree :as outliner-tree]
            [frontend.state :as state]
            [logseq.db.frontend.db-ident :as db-ident]
            [logseq.sdk.utils :as sdk-utils]))

(def plugin-property-prefix "plugin.property.")

(defn sanitize-user-property-name
  [k]
  (if (string? k)
    (-> k (string/trim)
        (string/replace " " "")
        (string/replace #"^[:_\s]+" ""))
    (str k)))

(defn get-sanitized-plugin-id
  [^js plugin]
  (or
   (when (some-> js/window.LSPlugin (.-PluginLocal))
     (some->> plugin (.-id) sanitize-user-property-name))
   "_test_plugin"))

(defn resolve-property-prefix-for-db
  [^js plugin]
  (let [plugin-id (get-sanitized-plugin-id plugin)]
    (when-not plugin-id
      (js/console.error "Can't get current plugin id")
      (throw (ex-info "Can't get current plugin id"
                      {:plugin plugin})))
    (str plugin-property-prefix plugin-id)))

(defn get-db-ident-from-property-name
  "Finds a property :db/ident for a given property name"
  [property-name plugin]
  (let [property-name' (->
                        (if-not (string? property-name)
                          (str property-name)
                          property-name)
                        (string/replace #"^:+" ""))
        property-key (keyword property-name')]
    (if (qualified-keyword? property-key)
      property-key
      ;; plugin property
      (let [plugin-ns (resolve-property-prefix-for-db plugin)]
        (keyword plugin-ns (db-ident/normalize-ident-name-part property-name'))))))

(defn plugin-property-key?
  [ident]
  (and (qualified-keyword? ident)
       (string/starts-with? (namespace ident) plugin-property-prefix)))

(defn parse-property-json-value-if-need
  [ident property-value]
  (when-let [prop (and (string? property-value)
                       (plugin-property-key? ident)
                       (some-> ident (db-utils/entity)))]
    (if (= (:logseq.property/type prop) :json)
      (try
        (js/JSON.parse property-value)
        (catch js/Error _e
          property-value))
      property-value)))

(defn <sync-children-blocks!
  [block]
  (when block
    (db-async/<get-block (state/get-current-repo)
                         (:block/uuid (:block/parent block)) {:children? true})))

(defn get_block
  [id-or-uuid ^js opts]
  (when-let [block (if (number? id-or-uuid)
                     (db-utils/pull id-or-uuid)
                     (and id-or-uuid (db-model/query-block-by-uuid (sdk-utils/uuid-or-throw-error id-or-uuid))))]
    (when (or (true? (some-> opts (.-includePage)))
              (not (contains? block :block/name)))
      (when-let [uuid (:block/uuid block)]
        (let [{:keys [includeChildren]} (bean/->clj opts)
              repo (state/get-current-repo)
              block (if includeChildren
                      ;; nested children results
                      (let [blocks (->> (db-model/get-block-and-children repo uuid)
                                        (map (fn [b]
                                               (dissoc (db-utils/pull (:db/id b)) :block.temp/load-status))))]
                        (first (outliner-tree/blocks->vec-tree blocks uuid)))
                      ;; attached shallow children
                      (assoc block :block/children
                             (map #(list :uuid (:block/uuid %))
                                  (db/get-block-immediate-children repo uuid))))]
          (bean/->js (sdk-utils/normalize-keyword-for-json block)))))))
