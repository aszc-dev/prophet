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

(defn- stats [_]
  (let [nodes (map :node (store/all-notes))]
    (prn {:nodes (count nodes)
          :by-type (into (sorted-map) (frequencies (map :type nodes)))})))

(def ^:private commands
  {"smoke"         smoke
   "ingest-repo"   ingest-repo
   "glossary-build" glossary-build
   "glossary-define" glossary-define
   "index-rebuild" index-rebuild
   "search"        search
   "stats"         stats
   "web-build"     web-build
   "eval-fidelity" eval-fidelity
   "eval-retrieval" eval-retrieval
   "eval-gate"      eval-gate
   "telemetry-gaps" telemetry-gaps
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
