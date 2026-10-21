(ns logseq.e2e.editor-basic-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.block :as b]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.page :as p]
   [logseq.e2e.util :as util]
   [wally.main :as w]
   [wally.repl :as repl]))

(use-fixtures :once fixtures/open-page)

(use-fixtures :each
  fixtures/new-logseq-page
  fixtures/validate-graph)

(deftest move-blocks-mod+shift+m
  (testing "move blocks using `mod+shift+m`"
    (p/new-page "Target page")
    (p/new-page "Source page")
    (b/new-blocks ["b1" "b2" "b3"])
    (b/select-blocks 3)
    (k/press "ControlOrMeta+Shift+m")
    (w/fill "input[placeholder=\"Move blocks to\"]" "Target page")
    (w/wait-for (w/get-by-test-id "Target page"))
    (.focus (w/-query ".cp__cmdk-search-input"))
    (k/enter)
    (assert/assert-have-count ".ls-page-blocks .page-blocks-inner .ls-block" 0)))

(deftest move-blocks-cmdk
  (testing "move blocks using cmdk"
    (p/new-page "Target page 2")
    (p/new-page "Source page 2")
    (b/new-blocks ["b1" "b2" "b3"])
    (b/select-blocks 3)
    (util/search-and-click "Move blocks to")
    (w/fill "input[placeholder=\"Move blocks to\"]" "Target page 2")
    (w/wait-for (w/get-by-test-id "Target page 2"))
    (.focus (w/-query ".cp__cmdk-search-input"))
    (k/arrow-down)
    (k/enter)
    (assert/assert-have-count ".ls-page-blocks .page-blocks-inner .ls-block" 0)))

