(ns frontend.handler.property.file
  "Handler for older property features that originated with file graphs. Most of these
   fns can be used with file or db graphs.
   It's unclear what the difference is between this and frontend.handler.property. If no difference,
   we should merge them"
  (:require [frontend.handler.file-based.property.util :as property-util]
            [frontend.handler.file-based.property :as file-property-handler]
            [clojure.string :as string]))

;; Why need these XXX-when-file-based fns?
;; there're a lot of usages of property-related fns(e.g. property-util/insert-property) in the whole codebase.
;; I want to minimize extensive modifications as much as possible when we add db-based graph support.

(defn insert-properties-when-file-based
  [_repo format content kvs]
  (property-util/insert-properties format content kvs))

(defn remove-property-when-file-based
  [_repo format key content & args]
  (apply property-util/remove-property format key content args))

(defn remove-properties-when-file-based
  [_repo format content]
  (property-util/remove-properties format content))

(defn remove-built-in-properties-when-file-based
  [_repo format content]
  (property-util/remove-built-in-properties format content))

(defn with-built-in-properties-when-file-based
  [_repo properties content format]
  (property-util/with-built-in-properties properties content format))

(def property-key-exist?-when-file-based property-util/property-key-exist?)
(def goto-properties-end-when-file-based property-util/goto-properties-end)

(defn properties-hidden?
  [properties]
  (and (seq properties)
       (let [ks (map (comp keyword string/lower-case name)
                     (keys properties))
             hidden-properties-set (file-property-handler/hidden-properties)]
         (every? hidden-properties-set ks))))
