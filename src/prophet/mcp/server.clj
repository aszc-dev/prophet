(ns prophet.mcp.server
  "Minimal MCP server (JSON-RPC 2.0 over newline-delimited stdio) exposing the v0
   read tools. Reads are open; no write tools in v0 (ADR-008). stdout carries the
   protocol only — all logging goes to stderr."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [prophet.index.query :as query]))

(def supported-protocol-versions
  "MCP protocol versions this server speaks, newest first. On `initialize` the
   client's requested version is echoed if listed here, otherwise the newest is
   returned (spec-compliant negotiation)."
  ["2025-06-18" "2025-03-26" "2024-11-05"])

(def latest-protocol-version (first supported-protocol-versions))

(def server-instructions
  "Slayer KB (Polish LLM research lab): provenance-first, read-only. Start with `search` (hybrid, cross-lingual — English queries hit Polish-titled nodes), then `get_node`/`traverse` on the ids returned. Cite the `source_url` (commit-pinned GitHub blob) for every claim. Use `whats_new` for situational awareness.")

(defn- log [& xs] (binding [*out* *err*] (apply println "[mcp]" xs)))

;; --- tool registry ---------------------------------------------------------

(def tools
  "The v0 read-only MCP tool registry: each entry carries name, description,
   inputSchema, and a handler delegating to prophet.index.query."
  [{:name "search"
    :description "Primary entry point for ANY question about the Slayer applied-research lab (Polish LLM training): its datasets, benchmarks, experiments, evaluation axes, training recipes, and glossary terms. Hybrid retrieval (lexical BM25 + semantic vector + exact-alias, RRF-fused) — cross-lingual, so an English question retrieves Polish-titled nodes. Returns ranked nodes with stable ids, types, and provenance-bearing snippets. Each result includes source_url (a GitHub blob link at the pinned commit) — cite it for every claim drawn from that node. Start here, then call get_node or traverse on the ids it returns."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}
                               :limit {:type "integer" :description "max results (default 10)"}}
                  :required ["query"]}
    :handler (fn [{:keys [query limit]}]
               (query/search query (cond-> {} limit (assoc :limit limit))))}
   {:name "get_node"
    :description "Fetch the full node for a stable id (from search): frontmatter (type, status, links) plus its observations, each carrying a git provenance ref (git:slayer@<sha>:<path>) and a derived source_url (a GitHub blob link at the pinned commit) — cite the source_url for every claim drawn from that node or observation."
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}
    :handler (fn [{:keys [id]}] (query/get-node id))}
   {:name "traverse"
    :description "Walk typed links outward from a node id, multi-hop (e.g. experiment -> uses-dataset -> measured-by). Use for relational/lineage questions ('what does X depend on', 'what measures Y'). Optional rel filter and depth (default 2). Returns reached node ids with hop distance — get_node them to read content."
    :inputSchema {:type "object"
                  :properties {:id {:type "string"}
                               :rel {:type "string" :description "optional relation filter"}
                               :depth {:type "integer" :description "max hops (default 2)"}}
                  :required ["id"]}
    :handler (fn [{:keys [id rel depth]}]
               (query/traverse id (cond-> {} rel (assoc :rel rel) depth (assoc :depth depth))))}
   {:name "neighbors"
    :description "A node's directly linked neighbors (incoming and outgoing) with relation and direction. Use for a quick one-hop view of what a node connects to before deciding where to traverse."
    :inputSchema {:type "object" :properties {:id {:type "string"}} :required ["id"]}
    :handler (fn [{:keys [id]}] (query/neighbors id))}
   {:name "whats_new"
    :description "Recency feed for situational awareness / onboarding: nodes by last-updated, optionally filtered to a MOC facet (e.g. 'datasety', 'trening', 'benchmarki'). Use for 'what changed recently' or 'catch me up on X'."
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
    (let [requested (:protocolVersion params)]
      (result id {:protocolVersion (if (some #{requested} supported-protocol-versions)
                                     requested
                                     latest-protocol-version)
                  :capabilities {:tools {}}
                  :serverInfo {:name "prophet" :version "0.5.0-preview"}
                  :instructions server-instructions}))

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
