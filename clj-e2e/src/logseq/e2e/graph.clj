(ns logseq.e2e.graph
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.keyboard :as k]
            [logseq.e2e.locator :as loc]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(defn goto-all-graphs
  []
  (util/search-and-click "Go to all graphs"))

(defn validate-graph
  []
  (k/esc)
  (k/esc)
  (util/search-and-click "(Dev) Validate current graph")
  (assert/assert-is-visible (loc/and ".notifications div.notification-success div" (w/get-by-text "Your graph is valid")))
  (let [content (.textContent (loc/and ".notifications div.notification-success div" (w/get-by-text "Your graph is valid")))
        summary (edn/read-string (subs content (string/index-of content "{")))]
    (w/click ".notifications div.notification-success .ls-icon-x")
    summary))
