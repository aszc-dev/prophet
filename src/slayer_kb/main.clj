(ns slayer-kb.main
  "JVM CLI entrypoint. Babashka tasks (bb.edn) shell out here for any work that
   touches the index. Dispatches on the first arg; the rest are command args."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [slayer-kb.index.db :as db]
            [slayer-kb.index.schema :as schema]
            [slayer-kb.index.query :as query]
            [slayer-kb.mcp.server :as mcp]
            [slayer-kb.ingest :as ingest]))

(def ^:private db-path "kb.db")

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

(defn- index-rebuild [_]
  (binding [query/*db-path* db-path]
    (println "index:" (pr-str (schema/rebuild! db-path)))))

(defn- search [args]
  (binding [query/*db-path* db-path]
    (pp/print-table (map #(select-keys % [:score :type :title :snippet])
                         (query/search (str/join " " args))))))

(defn- todo [cmd]
  (fn [_] (println (str cmd ": not implemented yet (v0 in progress)"))))

(def ^:private commands
  {"smoke"         smoke
   "ingest-repo"   ingest-repo
   "index-rebuild" index-rebuild
   "search"        search
   "serve-mcp"     (fn [_] (binding [query/*db-path* db-path] (mcp/serve)))})

(defn -main [& args]
  (let [[cmd & rest] args
        f (get commands cmd)]
    (if f
      (f (vec rest))
      (do (binding [*out* *err*]
            (println "unknown command:" (pr-str cmd))
            (println "available:" (str/join ", " (sort (keys commands)))))
          (System/exit 2)))))
