(ns slayer-kb.ingest
  "Drives a SourceAdapter through extract -> resolve -> store. Per-repo config
   (kind-table + JSON field-mappings) makes the adapter source-specific without
   changing any downstream stage. Deterministic; re-running is a no-op."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [slayer-kb.adapters.repo :as repo]
            [slayer-kb.extract.log :as log]
            [slayer-kb.extract.page :as page]
            [slayer-kb.extract.card :as card]
            [slayer-kb.extract.config :as config]
            [slayer-kb.extract.json :as ejson]
            [slayer-kb.extract.doc :as doc]
            [slayer-kb.resolve.link :as resolve]
            [slayer-kb.store.node :as store]))

(defn load-config
  "Per-repo config from resources/sources/<name>.edn, or a Hugo-ish default when
   absent. `name` defaults to the repo directory's basename."
  [name]
  (if-let [res (io/resource (str "sources/" name ".edn"))]
    (edn/read-string (slurp res))
    {:kind-rules repo/default-kind-rules}))

(defn- extract-for
  "Dispatch a RawItem to its per-kind extractor. :json needs the matching spec
   from config; :code/:data are reference-only in v0."
  [{:keys [kind meta] :as item} cfg]
  (case kind
    :log    (log/extract item)
    :page   (page/extract item)
    :card   (card/extract item)
    :config (config/extract item)
    :doc    (doc/extract item)
    :json   (ejson/extract item (get-in cfg [:json-specs (:path meta)]))
    nil))

(defn ingest-repo!
  "Cold or incremental ingest of a source repo into the note store."
  ([dir] (ingest-repo! dir (.getName (io/file dir))))
  ([dir config-name]
   (let [cfg      (load-config config-name)
         a        (repo/adapter dir (or (:kind-rules cfg) repo/default-kind-rules))
         refs     (repo/discover a)
         existing (mapv :node (store/all-notes))
         raw      (->> refs (map #(repo/fetch a %)) (mapcat #(extract-for % cfg)) (remove nil?))
         prepared (resolve/prepare raw existing)
         results  (map store/upsert! prepared)]
     (reduce (fn [acc {:keys [status]}] (update acc status (fnil inc 0)))
             {:head (repo/head-sha dir) :refs (count refs) :config config-name
              :nodes (count prepared)}
             results))))
