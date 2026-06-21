(ns prophet.main
  "JVM CLI entrypoint. Babashka tasks (bb.edn) shell out here for any work that
   touches the index. Dispatches on the first arg; the rest are command args."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [prophet.index.db :as db]
            [prophet.index.schema :as schema]
            [prophet.index.query :as query]
            [prophet.mcp.server :as mcp]
            [prophet.mcp.http :as mcp-http]
            [prophet.glossary :as glossary]
            [prophet.synthesis :as synthesis]
            [prophet.web.build :as web]
            [prophet.eval.fidelity :as fidelity]
            [prophet.eval.retrieval :as retrieval]
            [prophet.mcp.telemetry :as tel]
            [prophet.mcp.telemetry.store :as tel-store]
            [prophet.store.node :as store]
            [prophet.ingest :as ingest]))

(def ^:private db-path (or (System/getenv "PROPHET_DB_PATH") "kb.db"))

(defn- smoke [_]
  (let [r (db/smoke ":memory:")]
    (println "index smoke OK:" (pr-str r))))

(defn- ingest-repo [[dir config-name]]
  (when-not dir
    (binding [*out* *err*] (println "usage: ingest-repo <repo-path> [config-name]"))
    (System/exit 2))
  (println "ingest:" (pr-str (if config-name
                               (ingest/ingest-repo! dir config-name)
                               (ingest/ingest-repo! dir)))))

(defn- glossary-build [_]
  (println "glossary:" (pr-str (glossary/build!))))

(defn- glossary-define [_]
  (println "glossary-define:" (pr-str (glossary/define!))))

(defn- web-build [_]
  (println "web:" (pr-str (web/build!))))

(defn- synthesis-run [_]
  (let [tally (synthesis/run!)
        orph  (synthesis/orphans)]
    (println "synthesis:" (pr-str tally))
    (println "unref'd/orphan State lines:" (count orph))
    ;; runner + CI gate in one: every State line must carry a resolvable ref
    ;; (presence, not just resolvability — ADR-017). Any breach -> fail.
    (when (seq orph)
      (doseq [{:keys [id ref line]} orph]
        (println "  STATE-PROVENANCE" id (or ref "<no-ref>") "::" line))
      (System/exit 1))))

(defn- eval-fidelity [_]
  (fidelity/scan!))

(defn- eval-retrieval [_]
  (binding [query/*db-path* db-path]
    (retrieval/scan!)))

(defn- eval-gate [_]
  (binding [query/*db-path* db-path]
    (when-not (:pass (retrieval/gate!))
      (System/exit 1))))

(defn- index-rebuild [_]
  (binding [query/*db-path* db-path]
    (println "index:" (pr-str (schema/rebuild! db-path)))))

(defn- search [args]
  (binding [query/*db-path* db-path]
    (pp/print-table (map #(select-keys % [:score :type :title :snippet])
                         (query/search (str/join " " args))))))

(defn- telemetry-gaps [_]
  (if-let [sink (tel/sink-path)]
    (let [{:keys [rows]} (tel-store/rebuild! sink)]
      (binding [*out* *err*] (println "telemetry: mirrored" rows "rows from" sink))
      (pp/pprint (vec (tel-store/gaps))))
    (binding [*out* *err*]
      (println "telemetry disabled (set PROPHET_TELEMETRY_PATH)")
      (System/exit 2))))

(defn- telemetry-stats [_]
  (if-let [sink (tel/sink-path)]
    (let [{:keys [rows]} (tel-store/rebuild! sink)
          s (tel-store/summary)
          pair-line (fn [pairs] (str/join "  " (map (fn [[k n]] (str (or k "—") "=" n)) pairs)))]
      (binding [*out* *err*] (println "telemetry: mirrored" rows "rows from" sink))
      (println (format "calls: %d   errors: %d" (:total s) (:errors s)))
      (println "by tool:     " (pair-line (:by_tool s)))
      (println "by transport:" (pair-line (:by_transport s)))
      (let [se (:searches s)
            zr (if (pos? (:total se)) (* 100.0 (/ (:zero_result se) (double (:total se)))) 0.0)]
        (println (format "searches: %d   zero-result: %d (%.0f%%)" (:total se) (:zero_result se) zr))
        (doseq [m (:by_mode se)]
          (println (format "  mode %-6s n=%-4d avg-score %.3f"
                           (or (:mode m) "—") (:n m) (double (or (:avg_score m) 0.0))))))
      (println (format "latency ms:   avg %.0f   max %s"
                       (double (or (:avg (:latency_ms s)) 0.0)) (str (or (:max (:latency_ms s)) "—"))))
      (when (seq (:top_queries s))
        (println "\ntop queries:")
        (doseq [q (:top_queries s)]
          (println (format "  %4d  %-44s ~%.1f hits" (:count q) (str \" (:query q) \") (:avg_results q)))))
      (when (seq (:recent s))
        (println "\nrecent:")
        (doseq [r (:recent s)]
          (println (format "  %s  %-9s %s%s"
                           (:ts r) (:tool r)
                           (if (:query r) (format "\"%s\" -> %s" (:query r) (:results r)) "")
                           (if (:error r) "  [ERROR]" ""))))))
    (binding [*out* *err*]
      (println "telemetry disabled (set PROPHET_TELEMETRY_PATH)")
      (System/exit 2))))

(defn- stats [_]
  (let [nodes    (map :node (store/all-notes))
        concepts (filter #(= "concept" (some-> (:type %) name)) nodes)
        pending  (count (filter #(empty? (:observations %)) concepts))]
    (prn {:nodes (count nodes)
          :by-type (into (sorted-map) (frequencies (map :type nodes)))
          :concept-pending pending})))

(def ^:private commands
  {"smoke"         smoke
   "ingest-repo"   ingest-repo
   "glossary-build" glossary-build
   "glossary-define" glossary-define
   "index-rebuild" index-rebuild
   "search"        search
   "stats"         stats
   "web-build"     web-build
   "synthesis-run" synthesis-run
   "eval-fidelity" eval-fidelity
   "eval-retrieval" eval-retrieval
   "eval-gate"      eval-gate
   "telemetry-gaps" telemetry-gaps
   "telemetry-stats" telemetry-stats
   "serve-mcp"     (fn [_] (binding [query/*db-path* db-path] (mcp/serve)))
   "serve-mcp-http" (fn [_] (binding [query/*db-path* db-path] (mcp-http/serve)))})

(defn -main [& args]
  (let [[cmd & rest] args
        f (get commands cmd)]
    (if f
      (f (vec rest))
      (do (binding [*out* *err*]
            (println "unknown command:" (pr-str cmd))
            (println "available:" (str/join ", " (sort (keys commands)))))
          (System/exit 2)))))
