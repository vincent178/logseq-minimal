(ns frontend.handler.property
  "Property fns for both file and DB graphs"
  (:require [frontend.db.model :as db-model]
            [frontend.handler.file-based.page-property :as file-page-property]
            [frontend.handler.file-based.property :as file-property-handler]
            [frontend.state :as state]))

(defn remove-block-property!
  [_repo block-id property-id-or-key]
  (assert (some? property-id-or-key) "remove-block-property! remove-block-property! is nil")
  (file-property-handler/remove-block-property! block-id property-id-or-key))

(defn set-block-property!
  [_repo block-id key v]
  (assert (some? key) "set-block-property! key is nil")
  (file-property-handler/set-block-property! block-id key v))

(defn add-page-property!
  "Sanitized page-name, unsanitized key / value"
  [page-entity key value]
  (assert (some? key) "key is nil")
  (when page-entity
    (file-page-property/add-property! page-entity key value)))

(defn remove-id-property
  [_repo format content]
  (file-property-handler/remove-id-property format content))

(defn file-persist-block-id!
  [_repo block-id]
  (file-property-handler/set-block-property! block-id :id (str block-id)))

(defn batch-remove-block-property!
  [_repo block-ids key]
  (assert (some? key) "key is nil")
  (file-property-handler/batch-remove-block-property! block-ids key))

(defn batch-set-block-property!
  [_repo block-ids key value & {:as _opts}]
  (assert (some? key) "key is nil")
  (file-property-handler/batch-set-block-property! block-ids key value))

(defn set-block-properties!
  "DB-graph-only (fsrs/asset-size); file graphs never call this with real props.
  No file version exists — kept as a no-op so DB-only callers are safe."
  [_repo block-id _properties]
  (assert (uuid? block-id))
  nil)

(defonce class-property-excludes
  #{:logseq.property.class/properties :block/tags
    :logseq.property/icon :block/alias :logseq.property/enable-history?
    :logseq.property/exclude-from-graph-view :logseq.property/template-applied-to
    :logseq.property/hide-empty-value :logseq.property.class/hide-from-node
    :logseq.property/page-tags :logseq.property.class/extends
    :logseq.property/publishing-public? :logseq.property.user/avatar
    :logseq.property.user/email :logseq.property.user/name})

(defn get-class-property-choices
  []
  (->>
   (db-model/get-all-properties (state/get-current-repo)
                                {:remove-ui-non-suitable-properties? true})
   (remove (fn [p]
             (contains? class-property-excludes (:db/ident p))))))
