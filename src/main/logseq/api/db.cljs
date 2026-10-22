(ns logseq.api.db
  "DB APIs"
  (:require [cljs-bean.core :as bean]
            [cljs.reader]
            [frontend.db.query-custom :as query-custom]
            [frontend.db.query-dsl :as query-dsl]
            [frontend.modules.layout.core]
            [frontend.state :as state]
            [logseq.sdk.core]
            [logseq.sdk.experiments]
            [logseq.sdk.git]
            [logseq.sdk.utils :as sdk-utils]
            [promesa.core :as p]))

(defn q
  [query-string]
  (when-let [repo (state/get-current-repo)]
    (p/let [result (query-dsl/query repo query-string
                                    {:disable-reactive? true
                                     :return-promise? true})]
      (bean/->js (sdk-utils/normalize-keyword-for-json (flatten result))))))

(defn custom_query
  [query-string]
  (p/let [result (let [query (cljs.reader/read-string query-string)]
                   (query-custom/custom-query {:query query
                                               :disable-reactive? true
                                               :return-promise? true}))]
    (bean/->js (sdk-utils/normalize-keyword-for-json (flatten result)))))
