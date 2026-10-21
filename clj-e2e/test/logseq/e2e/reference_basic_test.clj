(ns logseq.e2e.reference-basic-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.block :as b]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.util :as util]
   [wally.repl :as repl]))

(use-fixtures :once fixtures/open-page)

(use-fixtures :each
  fixtures/new-logseq-page
  fixtures/validate-graph)

;; block references
(deftest self-reference
  (testing "self reference"
    (b/new-block "b2")
    (b/copy)
    (b/paste)
    (util/exit-edit)
    (assert/assert-selected-block-text "b2")))

;; TODO: page references
