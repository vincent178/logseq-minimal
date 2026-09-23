(ns logseq.cli.spec
  "Babashka.cli specs for commands. Normally these would live alongside their
  commands but are separate because command namespaces are lazy loaded")

(def append
  {:api-server-token {:alias :a
                      :desc "API server token to modify current graph"}})

(def mcp-server
  {:api-server-token {:alias :a
                      :desc "API server token to connect to current graph"}
   :graph {:alias :g
           :desc "Local graph to use with MCP server"}
   :stdio {:alias :s
           :desc "Run the MCP server via stdio transport"}
   :port {:alias :p
          :default 12315
          :coerce :long
          :desc "Port for streamable HTTP server"}
   :host {:default "127.0.0.1"
          :desc "Host for streamable HTTP server"}
   :debug-tool {:alias :t
                :coerce :keyword
                :desc "Debug mcp tool with direct invocation"}})

