(ns slayer-kb.mcp.server
  "Minimal MCP server (JSON-RPC 2.0 over newline-delimited stdio) exposing the v0
   read tools. Reads are open; no write tools in v0 (ADR-008). stdout carries the
   protocol only — all logging goes to stderr."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [slayer-kb.index.query :as query]))

(def ^:private protocol-version "2024-11-05")

(defn- log [& xs] (binding [*out* *err*] (apply println "[mcp]" xs)))

;; --- tool registry ---------------------------------------------------------

(def tools
  [{:name "search"
    :description "Hybrid search over the knowledge base (FTS + exact alias + graph). Returns ranked nodes with provenance-bearing snippets."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}
                               :limit {:type "integer" :description "max results (default 10)"}}
                  :required ["query"]}
    :handler (fn [{:keys [query limit]}]
               (query/search query (cond-> {} limit (assoc :limit limit))))}
   {:name "get_node"
    :description "Fetch a full node (frontmatter + observations) by its stable id."
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}
    :handler (fn [{:keys [id]}] (query/get-node id))}
   {:name "traverse"
    :description "Follow typed links from a node, multi-hop. Returns reached node ids with hop distance."
    :inputSchema {:type "object"
                  :properties {:id {:type "string"}
                               :rel {:type "string" :description "optional relation filter"}
                               :depth {:type "integer" :description "max hops (default 2)"}}
                  :required ["id"]}
    :handler (fn [{:keys [id rel depth]}]
               (query/traverse id (cond-> {} rel (assoc :rel rel) depth (assoc :depth depth))))}
   {:name "neighbors"
    :description "Directly linked nodes (incoming and outgoing) for a node id."
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}
    :handler (fn [{:keys [id]}] (query/neighbors id))}
   {:name "whats_new"
    :description "Recency feed for situational awareness: nodes by last-updated, optionally filtered by MOC."
    :inputSchema {:type "object"
                  :properties {:moc {:type "string"}
                               :limit {:type "integer"}}}
    :handler (fn [{:keys [moc limit]}]
               (query/whats-new (cond-> {} moc (assoc :moc moc) limit (assoc :limit limit))))}])

(def ^:private by-name (into {} (map (juxt :name identity) tools)))

;; --- JSON-RPC --------------------------------------------------------------

(defn- result [id v] {:jsonrpc "2.0" :id id :result v})
(defn- rpc-error [id code msg] {:jsonrpc "2.0" :id id :error {:code code :message msg}})

(defn handle
  "Pure-ish request handler: request map -> response map (or nil for notifications)."
  [{:keys [id method params]}]
  (case method
    "initialize"
    (result id {:protocolVersion protocol-version
                :capabilities {:tools {}}
                :serverInfo {:name "slayer-kb" :version "0.0-v0"}})

    ("notifications/initialized" "notifications/cancelled") nil

    "ping" (result id {})

    "tools/list"
    (result id {:tools (mapv #(dissoc % :handler) tools)})

    "tools/call"
    (let [{tool-name :name args :arguments} params
          tool (by-name tool-name)]
      (if-not tool
        (rpc-error id -32602 (str "unknown tool: " tool-name))
        (try
          (let [out ((:handler tool) (or args {}))]
            (result id {:content [{:type "text" :text (json/write-str out)}]
                        :isError false}))
          (catch Exception e
            (log "tool error" tool-name (.getMessage e))
            (result id {:content [{:type "text" :text (str "error: " (.getMessage e))}]
                        :isError true})))))

    (rpc-error id -32601 (str "method not found: " method))))

(defn serve
  "Run the stdio loop. Blocks until stdin closes."
  []
  (log "serving on stdio; db =" query/*db-path*)
  (let [r (java.io.BufferedReader. *in*)
        w *out*]
    (loop []
      (when-let [line (.readLine r)]
        (when-not (str/blank? line)
          (let [resp (try (handle (json/read-str line :key-fn keyword))
                          (catch Exception e
                            (rpc-error nil -32700 (str "parse error: " (.getMessage e)))))]
            (when resp
              (.write w (str (json/write-str resp) "\n"))
              (.flush w))))
        (recur)))))
