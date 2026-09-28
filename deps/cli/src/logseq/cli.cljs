(ns logseq.cli
  "Main ns for Logseq CLI"
  (:require ["fs" :as fs]
            ["path" :as node-path]
            [babashka.cli :as cli]
            [clojure.string :as string]
            [logseq.cli.common.graph :as cli-common-graph]
            [logseq.cli.text-util :as cli-text-util]
            [nbb.error]))

(defn- format-commands [{:keys [table]}]
  (let [table (mapv (fn [{:keys [cmds desc spec]}]
                      (cond-> [(str (string/join " " cmds)
                                    (when spec " [options]"))]
                        desc (conj desc)))
                    (filter (comp seq :cmds) table))]
    (cli/format-table {:rows table})))

(def ^:private default-spec
  {:version {:coerce :boolean
             :alias :v
             :desc "Print version"}})

(declare table)
(defn- print-general-help [_m]
  (println (str "Usage: logseq [command] [options]\n\nOptions:\n"
                (cli/format-opts {:spec default-spec})))
  (println (str "\nCommands:\n" (format-commands {:table table}))))

(defn- default-command
  [{{:keys [version]} :opts :as m}]
  (if version
    (let [package-json (node-path/join js/__dirname "package.json")]
      (when (fs/existsSync package-json)
        (println (-> (fs/readFileSync package-json)
                     js/JSON.parse
                     (aget "version")))))
    (print-general-help m)))

(defn- print-command-help [command cmd-map]
  (println (str "Usage: logseq " command
                (when (:args->opts cmd-map)
                  (str " " (string/join " "
                                        (map #(str "[" (name %) "]") (:args->opts cmd-map)))))
                (when (:spec cmd-map)
                  (str " [options]\n\nOptions:\n"
                       (cli/format-opts {:spec (:spec cmd-map)})))
                (when (:description cmd-map)
                  (str "\n\nDescription:\n" (cli-text-util/wrap-text (:description cmd-map) 80))))))

(defn- help-command [{{:keys [command help]} :opts}]
  (if-let [cmd-map (and command (some #(when (= command (first (:cmds %))) %) table))]
    (print-command-help command cmd-map)
    ;; handle help --help
    (if-let [cmd-map (and help (some #(when (= "help" (first (:cmds %))) %) table))]
      (print-command-help "help" cmd-map)
      (println "Command" (pr-str command) "does not exist"))))

(def ^:private table*
  [{:cmds ["help"] :fn help-command :desc "Print a command's help"
    :args->opts [:command] :require [:command]}
   {:cmds []
    :spec default-spec
    :fn default-command}])

;; Spec shared with all commands
(def ^:private shared-spec
  {:help {:alias :h
          :desc "Print help"}})

(def ^:private table
  (mapv (fn [m] (update m :spec #(merge % shared-spec))) table*))

(defn- warn-if-db-version-not-installed
  []
  (when-not (fs/existsSync (cli-common-graph/get-db-graphs-dir))
    (println "[WARN] The database version's desktop app is not installed. Please install per https://github.com/logseq/og/#-database-version.")))

(defn ^:api -main [& args]
  (warn-if-db-version-not-installed)
  (try
    (cli/dispatch table
                  args
                  {:error-fn (fn [{:keys [cause msg option opts] type' :type :as data}]
                               ;; Options aren't required when printing help
                               (when-not (:help opts)
                                 (if (and (= :org.babashka/cli type')
                                          (= :require cause))
                                   (do
                                     (println "Error: Command missing required"
                                              (if (get-in data [:spec option]) "option" "argument")
                                              (pr-str (name option)))
                                     (when-let [cmd-m (some #(when (= {:spec (:spec %)
                                                                       :require (:require %)}
                                                                      (select-keys data [:spec :require])) %) table)]
                                       (print-command-help (-> cmd-m :cmds first) cmd-m)))
                                   (throw (ex-info msg data)))
                                 (js/process.exit 1)))})
    (catch ^:sci/error js/Error e
      (nbb.error/print-error-report e)
      (js/process.exit 1))))

#js {:main -main}
