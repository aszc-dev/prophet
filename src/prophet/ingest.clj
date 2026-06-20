(ns prophet.ingest
  "Drives a SourceAdapter through extract -> resolve -> store. Per-repo config
   (kind-table + JSON field-mappings) makes the adapter source-specific without
   changing any downstream stage. Deterministic; re-running is a no-op."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.adapters.repo :as repo]
            [prophet.extract.log :as log]
            [prophet.extract.page :as page]
            [prophet.extract.card :as card]
            [prophet.extract.config :as config]
            [prophet.extract.json :as ejson]
            [prophet.extract.doc :as doc]
            [prophet.extract.leaderboard :as leaderboard]
            [prophet.resolve.link :as resolve]
            [prophet.store.node :as store]))

(defn load-config
  "Per-repo config from resources/sources/<name>.edn, or a Hugo-ish default when
   absent. `name` defaults to the repo directory's basename."
  [name]
  (if-let [res (io/resource (str "sources/" name ".edn"))]
    (edn/read-string (slurp res))
    {:kind-rules repo/default-kind-rules}))

(defn- extract-for
  "Dispatch a RawItem to its extractor. Returns a seq of node maps and/or attach
   bundles (maps carrying :attach-to). :json needs the matching spec from config."
  [{:keys [kind meta] :as item} cfg]
  (case kind
    :log         (log/extract item)
    :page        (page/extract item)
    :card        (card/extract item)
    :config      (config/extract item)
    :doc         (doc/extract item)
    :json        (ejson/extract item (get-in cfg [:json-specs (:path meta)]))
    :leaderboard (leaderboard/extract item)
    nil))

(defn- norm [s] (some-> s str str/lower-case str/trim))

(defn- find-target
  "Existing node matching an attach bundle: same type and an alias/title hit.
   Type-aware so a shared id (dataset vs benchmark) attaches to the right node."
  [nodes {:keys [attach-to match]}]
  (some (fn [n]
          (when (and (= attach-to (keyword (:type n)))
                     (contains? (set (map norm (cons (:title n) (:aliases n))))
                                (norm match)))
            n))
        nodes))

(defn- attach!
  "Append a bundle's observations to its target anchor. upsert! merges them
   append-only, so a repeat run is a no-op."
  [nodes bundle]
  (if-let [target (find-target nodes bundle)]
    (store/upsert! (assoc target :observations (:observations bundle)))
    {:status :orphan :match (:match bundle)}))

(defn ingest-repo!
  "Cold or incremental ingest of a source repo into the note store."
  ([dir] (ingest-repo! dir (.getName (io/file dir))))
  ([dir config-name]
   (let [cfg       (load-config config-name)
         a         (repo/adapter dir (or (:kind-rules cfg) repo/default-kind-rules))
         refs      (repo/discover a)
         existing  (mapv :node (store/all-notes))
         extracted (->> refs (map #(repo/fetch a %)) (mapcat #(extract-for % cfg)) (remove nil?))
         bundles   (filter :attach-to extracted)
         raw       (remove :attach-to extracted)
         prepared  (resolve/prepare raw existing)
         node-res  (mapv store/upsert! prepared)
         ;; attach pass runs against the now-current store (anchors exist by now)
         after     (mapv :node (store/all-notes))
         att-res   (mapv #(attach! after %) bundles)
         tally     (fn [acc {:keys [status]}] (update acc status (fnil inc 0)))]
     (-> {:head (repo/head-sha dir) :refs (count refs) :config config-name
          :nodes (count prepared)}
         (as-> m (reduce tally m node-res))
         (assoc :attached (count (remove #(#{:unchanged :orphan} (:status %)) att-res))
                :orphans  (count (filter #(= :orphan (:status %)) att-res)))))))
