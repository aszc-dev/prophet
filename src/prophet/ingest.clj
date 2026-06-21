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
  "Per-source ingest config from resources/sources/<name>.edn. The name is
   explicit, never derived from a clone path: an unknown name throws rather than
   silently falling back to defaults and ingesting a near-empty corpus (ADR-018)."
  [name]
  (if-let [res (io/resource (str "sources/" name ".edn"))]
    (edn/read-string (slurp res))
    (throw (ex-info (str "Unknown source config " (pr-str name)
                         " — expected resources/sources/" name ".edn")
                    {:config name}))))

(defmulti extract-for
  "Dispatch a RawItem to its extractor by :kind. The single extractor registry
   (ADR-018): every source kind is a defmethod here, nothing dispatches elsewhere,
   and no source string is baked into one. A new kind is a new defmethod; a
   genuinely new shape that no :json-spec can express is the only reason to add
   one. Returns a seq of node maps and/or attach bundles (maps carrying
   :attach-to)."
  (fn [item _cfg] (:kind item)))

(defmethod extract-for :log [item _] (log/extract item))
(defmethod extract-for :page [item _] (page/extract item))
(defmethod extract-for :card [item _] (card/extract item))
(defmethod extract-for :config [item _] (config/extract item))
(defmethod extract-for :doc [item _] (doc/extract item))
;; :json needs the matching spec from config, keyed by the item's repo path.
(defmethod extract-for :json [item cfg]
  (ejson/extract item (get-in cfg [:json-specs (get-in item [:meta :path])])))
(defmethod extract-for :leaderboard [item _] (leaderboard/extract item))
(defmethod extract-for :default [_ _] nil)

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
  "Cold or incremental ingest of a source repo into the note store. `config-name`
   is required and must name a resources/sources/<name>.edn (ADR-018); a missing
   or unknown config fails the ingest loudly rather than degrading silently."
  [dir config-name]
  (let [cfg       (load-config config-name)
        shortname (or (:shortname cfg) config-name)
        a         (repo/adapter dir (or (:kind-rules cfg) repo/default-kind-rules) shortname)
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
               :orphans  (count (filter #(= :orphan (:status %)) att-res))))))
