(ns ^:node-only logseq.cli.common.graph
  "Graph related fns shared between CLI and electron"
  (:require ["fs-extra" :as fs-extra]
            ["os" :as os]
            ["path" :as node-path]
            [clojure.string :as string]
            [logseq.common.config :as common-config]
            [logseq.common.graph :as common-graph]))

(defn ^:api graph-name->path
  [graph-name]
  (when graph-name
    (-> graph-name
        (string/replace "+3A+" ":")
        (string/replace "++" "/"))))

(defn ^:api get-sqlite-graphs-dir
  "Directory where graphs' SQLite databases are stored"
  []
  (node-path/join (os/homedir) "logseq" "graphs"))

(defn ^:api get-sqlite-graphs
  []
  (let [dir (get-sqlite-graphs-dir)]
    (fs-extra/ensureDirSync dir)
    (->> (common-graph/read-directories dir)
         (remove (fn [s] (= s common-config/unlinked-graphs-dir)))
         (map graph-name->path)
         (map (fn [s]
                (if (string/starts-with? s common-config/file-version-prefix)
                  s
                  (str common-config/db-version-prefix s)))))))
