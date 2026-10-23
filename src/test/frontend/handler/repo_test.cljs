(ns frontend.handler.repo-test
  (:require ["fs" :as fs]
            ["path" :as node-path]
            [cljs.test :refer [deftest use-fixtures testing is]]
            [clojure.edn :as edn]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.db.model :as model]
            [frontend.handler.file-based.repo :as file-repo-handler]
            [frontend.handler.repo]
            [logseq.db :as ldb]
            [frontend.test.helper :as test-helper :refer [load-test-files]]
            [frontend.worker.state :as worker-state]
            [logseq.common.util.block-ref :as block-ref]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.graph-parser.test.docs-graph-helper :as docs-graph-helper]))

(use-fixtures :each test-helper/start-and-destroy-db)

(deftest ^:integration parse-and-load-files-to-db
  (let [graph-dir "src/test/docs-0.10.12"
        _ (docs-graph-helper/clone-docs-repo-if-not-exists graph-dir "v0.10.12")
        repo-config (edn/read-string (str (fs/readFileSync (node-path/join graph-dir "logseq/config.edn"))))
        files (#'gp-cli/build-graph-files graph-dir repo-config)
        _ (with-redefs [worker-state/get-config (constantly repo-config)]
            (file-repo-handler/parse-files-and-load-to-db! test-helper/test-db files {:re-render? false :verbose false}))
        db (conn/get-db test-helper/test-db)]

    (docs-graph-helper/docs-graph-assertions db graph-dir (map :file/path files))
    (testing "Additional Counts"
      (is (= 57501 (count (d/datoms db :eavt))) "Correct datoms count")

      (is (= 2065
             (ffirst
              (d/q '[:find (count ?b)
                     :where [?b :block/refs ?bp] [?bp :block/name]] db)))
          "Correct referenced blocks count"))))

(deftest parse-files-and-load-to-db-with-block-refs-on-reload
  (testing "Refs to blocks on a page are retained if that page is reloaded"
    (let [test-uuid "16c90195-6a03-4b3f-839d-095a496d9acd"
          target-page-content (str "- target block\n  id:: " test-uuid)
          referring-page-content (str "- " (block-ref/->block-ref test-uuid))]
      (load-test-files [{:file/path "pages/target.md"
                         :file/content target-page-content}
                        {:file/path "pages/referrer.md"
                         :file/content referring-page-content}])
      (is (= [(parse-uuid test-uuid)] (model/get-all-referenced-blocks-uuid)))

      (load-test-files [{:file/path "pages/target.md"
                         :file/content target-page-content}])
      (is (= [(parse-uuid test-uuid)] (model/get-all-referenced-blocks-uuid))))))

(deftest parse-files-and-load-to-db-with-page-rename
  (testing
   "Reload a file when the disk contents result in the file having a new page name"
    (let [test-uuid "16c90195-6a03-4b3f-839d-095a496d9efc"
          target-page-content (str "- target block\n  id:: " test-uuid)
          referring-page-content (str "- " (block-ref/->block-ref test-uuid))
          update-referring-page-content (str "title:: updatedPage\n- " (block-ref/->block-ref test-uuid))
          get-page-block-count (fn [page-name]
                                 (let [page-id (:db/id (model/get-page page-name))]
                                   (if (some? page-id)
                                     (model/get-page-blocks-count test-helper/test-db page-id)
                                     0)))]
      (load-test-files [{:file/path "pages/target.md"
                         :file/content target-page-content}
                        {:file/path "pages/referrer.md"
                         :file/content referring-page-content}])
      (is (= [(parse-uuid test-uuid)] (model/get-all-referenced-blocks-uuid)))
      (is (= 1 (get-page-block-count "referrer")))
      (is (= 0 (get-page-block-count "updatedPage")))

      (load-test-files [{:file/path "pages/referrer.md"
                         :file/content update-referring-page-content}])
      (is (= [(parse-uuid test-uuid)] (model/get-all-referenced-blocks-uuid)))
      (is (= 0 (get-page-block-count "referrer")))
      (is (= 2 (get-page-block-count "updatedPage"))))))

(deftest demo-graph-pristine-first-boot-test
  (testing "R6: recovery fingerprint only matches the genuine interrupted first boot"
    (let [pristine? (fn [] (#'frontend.handler.repo/demo-graph-pristine-first-boot?
                            (conn/get-db test-helper/test-db) "oct 23rd, 2026"))]
      (testing "pre-parse skeleton (no pages/blocks) is pristine"
        (is (true? (pristine?))))
      (testing "seed pages + seed blocks + auto-created empty today journal are pristine"
        (load-test-files [{:file/path "journals/2020_12_26.md"
                           :file/content "- Welcome to Logseq!\n- This is a demo graph stored in memory."}
                          {:file/path "pages/contents.md"
                           :file/content "-"}])
        (ldb/transact! (conn/get-db test-helper/test-db false)
                       [{:block/name "oct 23rd, 2026"}])
        (is (true? (pristine?))))
      (testing "a user page means user data -> not pristine (legacy graph, deleted seed pages stay deleted)"
        (load-test-files [{:file/path "pages/my_notes.md"
                           :file/content "- my own note"}])
        (is (false? (pristine?)))))
    (testing "a user edit inside the seed journal page itself is user data -> not pristine"
      (load-test-files [{:file/path "journals/2020_12_26.md"
                         :file/content "- Welcome to Logseq!\n- my own addition"}])
      (is (false? (#'frontend.handler.repo/demo-graph-pristine-first-boot?
                   (conn/get-db test-helper/test-db) "oct 23rd, 2026"))))
    (testing "a block with content in the auto-created today journal is user data -> not pristine"
      (load-test-files [{:file/path "journals/2020_12_26.md"
                         :file/content "- Welcome to Logseq!"}])
      (ldb/transact! (conn/get-db test-helper/test-db false)
                     [{:block/name "oct 23rd, 2026"}])
      (ldb/transact! (conn/get-db test-helper/test-db false)
                     [{:block/content "user wrote here"
                       :block/page [:block/name "oct 23rd, 2026"]}])
      (is (false? (#'frontend.handler.repo/demo-graph-pristine-first-boot?
                   (conn/get-db test-helper/test-db) "oct 23rd, 2026"))))
    (testing "a content block with no page is conservatively user data -> not pristine"
      (load-test-files [{:file/path "journals/2020_12_26.md"
                         :file/content "- Welcome to Logseq!"}])
      (ldb/transact! (conn/get-db test-helper/test-db false)
                     [{:block/content "orphan block"}])
      (is (false? (#'frontend.handler.repo/demo-graph-pristine-first-boot?
                   (conn/get-db test-helper/test-db) "oct 23rd, 2026"))))))
