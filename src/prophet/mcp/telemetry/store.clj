(ns prophet.mcp.telemetry.store
  "Derived SQLite mirror of the append-only telemetry JSONL, plus gap distillation
   for the retrieval gold set. telemetry.db is fully rebuildable from the JSONL
   (the source of truth) and is NEVER kb.db. Reuses the index.* SQLite layer
   (next.jdbc + db/open); no second driver."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [prophet.index.db :as db]))

(def ^:dynamic *db-path* "telemetry.db")

(def ^:dynamic *score-floor*
  "Top-score below which a search counts as a candidate gap; nil disables the
   low-score branch. Overridable via PROPHET_TELEMETRY_SCORE_FLOOR."
  (some-> (System/getenv "PROPHET_TELEMETRY_SCORE_FLOOR") Double/parseDouble))

(def ^:private ddl
  "create table if not exists tool_call (
     ts text, session_id text, transport text, tool text,
     args text, result_ids text, result_count integer,
     top_score real, mode text, latency_ms integer,
     is_error integer, error_msg text)")

(defn- row [rec]
  [(:ts rec) (:session_id rec) (:transport rec) (:tool rec)
   (json/write-str (:args rec)) (json/write-str (:result_ids rec))
   (:result_count rec) (:top_score rec) (:mode rec) (:latency_ms rec)
   (if (:is_error rec) 1 0) (:error_msg rec)])

(defn rebuild!
  "(Re)build telemetry.db from the JSONL at `jsonl-path` (the source of truth).
   Drops and recreates the mirror; returns {:db ... :rows n}."
  [jsonl-path]
  (with-open [conn (db/open *db-path*)]
    (jdbc/execute! conn ["drop table if exists tool_call"])
    (jdbc/execute! conn [ddl])
    (let [rows (when (and jsonl-path (.exists (io/file jsonl-path)))
                 (->> (str/split-lines (slurp jsonl-path))
                      (remove str/blank?)
                      (mapv #(row (json/read-str % :key-fn keyword)))))]
      (when (seq rows)
        (jdbc/execute-batch! conn
                             "insert into tool_call (ts,session_id,transport,tool,args,result_ids,result_count,top_score,mode,latency_ms,is_error,error_msg) values (?,?,?,?,?,?,?,?,?,?,?,?)"
                             rows {}))
      {:db *db-path* :rows (count rows)})))

(defn- arg-key [args-json k]
  (try (get (json/read-str args-json :key-fn keyword) k) (catch Exception _ nil)))

(defn- dedupe-by [kf coll]
  (->> coll (group-by kf) vals (map first)))

(defn gaps
  "Candidate gold-set additions distilled from telemetry.db: search queries that
   (a) returned 0 results, (b) scored below `floor` (when set), or (c) had no
   follow-up get_node on any returned id within the same session. Returns a seq of
   maps shaped for hand-labelling into eval/retrieval-gold.edn (one per :q+:reason)."
  ([] (gaps {}))
  ([{:keys [floor] :or {floor *score-floor*}}]
   (with-open [conn (db/open *db-path*)]
     (let [opts     {:builder-fn rs/as-unqualified-lower-maps}
           searches (jdbc/execute! conn ["select ts,session_id,args,result_ids,result_count,top_score from tool_call where tool='search'"] opts)
           getnodes (jdbc/execute! conn ["select session_id,args from tool_call where tool='get_node'"] opts)
           followed (into #{} (map (fn [g] [(:session_id g) (arg-key (:args g) :id)])) getnodes)]
       (->> searches
            (keep (fn [s]
                    (let [ids    (json/read-str (:result_ids s))
                          reason (cond
                                   (zero? (:result_count s)) :zero-result
                                   (and floor (:top_score s) (< (:top_score s) floor)) :low-score
                                   (and (seq ids)
                                        (not-any? #(followed [(:session_id s) %]) ids)) :no-followup
                                   :else nil)]
                      (when reason
                        (cond-> {:q (arg-key (:args s) :query)
                                 :reason reason
                                 :session_id (:session_id s)
                                 :ts (:ts s)}
                          (= reason :low-score)   (assoc :top_score (:top_score s))
                          (= reason :no-followup) (assoc :result_ids ids))))))
            (dedupe-by (juxt :q :reason)))))))
