(ns prophet.mcp.telemetry
  "Append-only capture of MCP tool calls at the shared `server/handle` chokepoint.
   Inert unless a sink path is configured (PROPHET_TELEMETRY_PATH); never touches
   kb.db; never throws out of `emit!`. The record shape is a lossless superset of
   the OTel tool-span + retrieval-span attributes, stored with SQL-friendly keys
   (the OTel rename is deferred to a future exporter — see the telemetry ADR)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.index.embed :as embed])
  (:import [java.time Instant]))

(def ^:dynamic *session-id* nil)
(def ^:dynamic *transport* nil)

(def ^:dynamic *sink-path-override*
  "Test seam: when bound, used as the sink path instead of the env var. Production
   always goes through PROPHET_TELEMETRY_PATH."
  nil)

(defn sink-path
  "Configured JSONL sink path, or nil (inert) when unset/blank."
  []
  (or *sink-path-override*
      (let [p (System/getenv "PROPHET_TELEMETRY_PATH")]
        (when-not (str/blank? p) p))))

(defn- log [& xs] (binding [*out* *err*] (apply println "[telemetry]" xs)))

(defn result->ids
  "Best-effort node ids from a tool result; returns [] on any shape surprise."
  [tool out]
  (try
    (case tool
      "search"    (vec (keep :id out))
      "get_node"  (if-let [id (:id out)] [id] [])
      "traverse"  (vec (keys out))
      "neighbors" (vec (keep :id (concat (:out out) (:in out))))
      "whats_new" (vec (keep :id out))
      [])
    (catch Exception _ [])))

(defn top-score
  "Top result score for `search`, else nil. Best-effort."
  [tool out]
  (when (= tool "search")
    (try (:score (first out)) (catch Exception _ nil))))

(defn mode
  "Retrieval mode for `search` (hybrid when an embedder is configured, else fts);
   nil for other tools."
  [tool]
  (when (= tool "search")
    (if (embed/config) "hybrid" "fts")))

(defn record
  "Canonical telemetry record for one tool call."
  [{:keys [tool args out latency-ms error? error-msg]}]
  (let [ids (result->ids tool out)]
    {:ts           (str (Instant/now))
     :session_id   *session-id*
     :transport    *transport*
     :tool         tool
     :args         args
     :result_ids   ids
     :result_count (count ids)
     :top_score    (top-score tool out)
     :mode         (mode tool)
     :latency_ms   latency-ms
     :is_error     (boolean error?)
     :error_msg    error-msg}))

(defonce ^:private write-lock (Object.))

(defn emit!
  "Append one JSON line for a tool call to the configured sink; no-op when inert.
   Writes are serialized (the HTTP server is multi-threaded) and never throw."
  [m]
  (when-let [path (sink-path)]
    (try
      (let [line (str (json/write-str (record m)) "\n")]
        (locking write-lock
          (with-open [w (io/writer path :append true)]
            (.write w line))))
      (catch Exception e
        (log "emit failed:" (.getMessage e))
        nil))))
