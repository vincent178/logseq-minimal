(ns frontend.handler.favorites-test
  "Regression tests for favorites drag-and-drop reordering. The favorites
  order is stored in the `:favorites` config list, which is the single source
  of truth read by `get-favorites` for the sidebar."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [frontend.db :as db]
            [frontend.handler.common.page :as page-common-handler]
            [frontend.handler.config :as config-handler]
            [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper]))

(defn- start-and-destroy-db
  [f]
  (test-helper/start-and-destroy-db
   f
   {:init-data (fn [_conn]
                 (test-helper/load-test-files
                  [{:file/path "pages/page_a.md" :file/content "- a"}
                   {:file/path "pages/page_b.md" :file/content "- b"}
                   {:file/path "pages/page_c.md" :file/content "- c"}]))}))

(use-fixtures :each start-and-destroy-db)

(defn- with-config
  "Runs `f` with `state/get-config`/`state/sub-config` reading from an atom and
  `config-handler/set-config!` writing `:favorites` back to that atom."
  [initial-config f]
  (let [config (atom initial-config)]
    (with-redefs [state/get-config (fn ([] @config) ([_repo] @config))
                  state/sub-config (fn ([] @config) ([_repo] @config))
                  config-handler/set-config! (fn [k v] (swap! config assoc k v))]
      (f config))))

(deftest ^:focus reorder-favorites-updates-config-order
  (testing "Dragging favorites reorders the `:favorites` config list (single source of truth)"
    (let [uuid-a (str (:block/uuid (db/get-page "page_a")))
          uuid-b (str (:block/uuid (db/get-page "page_b")))
          uuid-c (str (:block/uuid (db/get-page "page_c")))]
      (with-config {:favorites [uuid-a uuid-b uuid-c]}
        (fn [config]
          ;; Drag page A after page C: [B C A]
          (page-handler/reorder-favorites! [uuid-b uuid-c uuid-a])
          (is (= [uuid-b uuid-c uuid-a] (:favorites @config))
              "reorder writes the new order to config")
          (is (= ["page_b" "page_c" "page_a"]
                 (map :block/name (page-handler/get-favorites)))
              "get-favorites renders the reordered config order")))))

  (testing "Reordering legacy page-name entries preserves the entry format"
    (let [uuid-a (str (:block/uuid (db/get-page "page_a")))
          uuid-b (str (:block/uuid (db/get-page "page_b")))]
      (with-config {:favorites ["page_a" "page_b"]}
        (fn [config]
          (page-handler/reorder-favorites! [uuid-b uuid-a])
          (is (= ["page_b" "page_a"] (:favorites @config))
              "legacy page-name entries keep their format and get reordered")
          (is (= ["page_b" "page_a"]
                 (map :block/name (page-handler/get-favorites)))
              "legacy entries still resolve to pages"))))))

(deftest favorite-and-unfavorite-roundtrip
  (testing "Favorite stores the page uuid and unfavorite removes it"
    (with-config {:favorites []}
      (fn [config]
        (page-common-handler/file-favorite-page! "page_a")
        (let [uuid-a (str (:block/uuid (db/get-page "page_a")))]
          (is (= [uuid-a] (:favorites @config))
              "favorite stores the page uuid string")
          (is (page-handler/favorited? "page_a") "favorited? by name")
          (is (page-handler/favorited? uuid-a) "favorited? by uuid")
          (page-common-handler/file-unfavorite-page! "page_a")
          (is (= [] (:favorites @config))
              "unfavorite by name removes the uuid entry")
          (is (not (page-handler/favorited? "page_a")) "no longer favorited")))))

  (testing "Unfavorite also removes legacy page-name entries"
    (with-config {:favorites ["page_b"]}
      (fn [config]
        (is (page-handler/favorited? "page_b"))
        (page-common-handler/file-unfavorite-page! "page_b")
        (is (= [] (:favorites @config)))))))
