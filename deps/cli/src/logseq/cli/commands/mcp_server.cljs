(ns logseq.cli.commands.mcp-server
  "Command to run a MCP server"
  (:require ["@modelcontextprotocol/sdk/server/stdio.js" :refer [StdioServerTransport]]
            ["fastify$default" :as Fastify]
            [logseq.cli.common.mcp.server :as cli-common-mcp-server]
            [logseq.cli.util :as cli-util]
            [nbb.core :as nbb]
            [promesa.core :as p]))

(defn- create-http-server
  [mcp-server opts]
  (let [app (Fastify. #js {:requestTimeout (* 1000 30)})]
    (.post app "/mcp" #(cli-common-mcp-server/handle-post-request mcp-server opts %1 %2))
    (.get app "/mcp" cli-common-mcp-server/handle-get-request)
    (.delete app "/mcp" cli-common-mcp-server/handle-delete-request)
    app))

(defn- start-http-server [mcp-server {:keys [port host] :as opts}]
  (let [app (create-http-server mcp-server opts)]
    (.listen app (clj->js (select-keys opts [:port :host]))
             (fn [error]
               (if error
                 (do (js/console.error "Failed to start server:" error)
                     (js/process.exit 1))
                 (js/console.log
                  (str "MCP Streamable HTTP Server started on " host ":" port)))))))

(defn- call-api
  "Calls API from CLI for use w/ cli-common-mcp-server/api-tool"
  [api-server-token api-method method-args]
  (p/let [resp (cli-util/api-fetch api-server-token api-method method-args)]
    (if (= 200 (.-status resp))
      (.json resp)
      (p/let [body (.text resp)]
        #js {:error (str "Server status " (.-status resp)
                         "\nAPI Response: " (pr-str body))}))))

(defn start [{{:keys [debug-tool stdio api-server-token] :as opts} :opts}]
  ;; Make an initial /api call to ensure the API server is on
  (if debug-tool
    (if-let [tool-m (get cli-common-mcp-server/api-tools debug-tool)]
      (p/let [resp (cli-common-mcp-server/call-api-tool (:fn tool-m)
                                                        (partial call-api api-server-token)
                                                        (clj->js (dissoc opts :debug-tool)))]
        (js/console.log resp))
      (cli-util/error "Tool" (pr-str debug-tool) "not found"))
    (-> (p/let [_resp (call-api api-server-token "logseq.app.search" ["foo"])
                mcp-server (cli-common-mcp-server/create-mcp-api-server (partial call-api api-server-token))]
          (if stdio
            (nbb/await (.connect mcp-server (StdioServerTransport.)))
            (start-http-server mcp-server (select-keys opts [:port :host]))))
        (p/catch cli-util/command-catch-handler))))
