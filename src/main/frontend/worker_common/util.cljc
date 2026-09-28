(ns frontend.worker-common.util
  "Worker utils"
  #?(:cljs (:require-macros [frontend.worker-common.util]))
  #?(:cljs (:refer-clojure :exclude [format]))
  #?(:cljs (:require [logseq.db.common.sqlite :as common-sqlite]
                     [frontend.common.file.util :as wfu])))

;; Copied from https://github.com/tonsky/datascript-todo
#?(:clj
   (defmacro profile
     [k & body]
     `(if goog.DEBUG
        (let [k# ~k]
          (.time js/console k#)
          (let [res# (do ~@body)]
            (.timeEnd js/console k#)
            res#))
        (do ~@body))))

#?(:cljs
   (do
     (def post-message wfu/post-message)

     (defn get-pool-name
       [graph-name]
       (str "logseq-pool-" (common-sqlite/sanitize-db-name graph-name)))))
