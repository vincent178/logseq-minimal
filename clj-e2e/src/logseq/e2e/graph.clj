(ns logseq.e2e.graph
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [logseq.e2e.assert :as assert]
            [logseq.e2e.locator :as loc]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(defn goto-all-graphs
  []
  (util/search-and-click "Go to all graphs"))

(defn validate-graph
  []
  ;; Invoke validate-db directly instead of going through the command palette.
  ;; The dev command's visibility depends on developer-mode timing, which is
  ;; flaky; calling the worker fn directly is both faster and deterministic.
  (w/eval-js "window.frontend.handler.common.developer.validate_db()")
  (assert/assert-is-visible (loc/and ".notifications div.notification-success div" (w/get-by-text "Your graph is valid")))
  (let [content (.textContent (loc/and ".notifications div.notification-success div" (w/get-by-text "Your graph is valid")))
        summary (edn/read-string (subs content (string/index-of content "{")))]
    (w/click ".notifications div.notification-success .ls-icon-x")
    summary))
