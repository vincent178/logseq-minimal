(ns frontend.handler.db-based.page
  "DB graph only page util fns"
  (:require [frontend.db :as db]
            [frontend.handler.common.page :as page-common-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.outliner.validate :as outliner-validate]
            [promesa.core :as p]))

(defn- valid-tag?
  "Returns a boolean indicating whether the new tag passes all valid checks.
   When returning false, this fn also displays appropriate notifications to the user"
  [repo block tag-entity]
  (try
    (outliner-validate/validate-unique-by-name-and-tags
     (db/get-db repo)
     (:block/title block)
     (update block :block/tags (fnil conj #{}) tag-entity))
    true
    (catch :default e
      (if (= :notification (:type (ex-data e)))
        (let [payload (:payload (ex-data e))]
          (notification/show! (:message payload) (:type payload))
          false)
        (throw e)))))

(defn add-tag [repo block-id tag-entity]
  (p/do!
   (editor-handler/save-current-block!)
   ;; Check after save-current-block to get most up to date block content
   (when (valid-tag? repo (db/entity repo [:block/uuid block-id]) tag-entity)
     (db-property-handler/set-block-property! block-id :block/tags (:db/id tag-entity)))))

(defn convert-page-to-tag!
  "Converts a Page to a Tag"
  [page-entity]
  (cond (db/page-exists? (:block/title page-entity) #{:logseq.class/Tag})
        (notification/show! (str "A tag with the name \"" (:block/title page-entity) "\" already exists.") :warning false)
        (:block/parent page-entity)
        (notification/show! "Namespaced pages can't be tags" :error false)
        (ldb/built-in? page-entity)
        (notification/show! "Built-in pages can't be used as tags" :error)
        :else
        (let [txs [(db-class/build-new-class (db/get-db)
                                             {:db/id (:db/id page-entity)
                                              :block/title (:block/title page-entity)
                                              :block/created-at (:block/created-at page-entity)})
                   [:db/retract (:db/id page-entity) :block/tags :logseq.class/Page]]]

          (db/transact! (state/get-current-repo) txs {:outliner-op :save-block}))))

(defn <create-class!
  "Creates a class page and provides class-specific error handling"
  [title options]
  (-> (page-common-handler/<create! title (assoc options :class? true))
      (p/catch (fn [e]
                 (when (= :notification (:type (ex-data e)))
                   (notification/show! (get-in (ex-data e) [:payload :message])
                                       (get-in (ex-data e) [:payload :type])))
                 ;; Re-throw as we don't want to proceed with a nonexistent class
                 (throw e)))))

