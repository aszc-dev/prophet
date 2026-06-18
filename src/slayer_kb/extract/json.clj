(ns slayer-kb.extract.json
  "Spec-driven extractor for structured JSON data files. The per-repo config maps
   each JSON path to a spec (node type + field mapping); this is where a source's
   own field names (e.g. Polish `nazwa`/`licencja`) get translated to the canonical
   node shape. Deterministic, no LLM — the highest-value Tier A data is JSON, not
   prose (the slayer repo is Next.js, not Hugo)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]))

(defn- records
  "Turn a parsed JSON document into a seq of [record-key record] per the spec's
   :iter mode. :list -> indexed; :dict-vals -> by map key; :dict-by-key uses the
   value at :under (a nested collection)."
  [data {:keys [iter under]}]
  (case (or iter :list)
    :list       (map-indexed (fn [i r] [(str i) r]) data)
    :dict-vals  (map (fn [[k v]] [(name k) v]) data)
    :dict-by-key (records (get data (keyword under)) {:iter :list})))

(defn- observations [rec obs-keys ref]
  (->> obs-keys
       (keep (fn [k]
               (let [v (get rec k)]
                 (when (and (some? v) (not (str/blank? (str v))))
                   {:date "" :ref ref :text (str (name k) ": " v)}))))
       vec))

(defn- record->node
  [{:keys [repo path]} spec base-ref rkey rec]
  (let [{:keys [node-type id title state tags moc aliases link_hints]} spec
        obs-keys (:observations spec)
        rid    (str (or (when id (get rec id)) rkey))
        ref    (str base-ref "#" rid)]
    {:type        node-type
     :title       (or (when title (get rec title)) rid)
     :status      :current
     :moc         (or moc [])
     :tags        (vec (when tags (get rec tags)))
     :visibility  :public
     :source_key  (str repo ":" path "#" rid)
     :aliases     (->> (map #(get rec %) (or aliases [])) (remove nil?) (map str) distinct vec)
     :provenance  [{:source :git :ref ref}]
     :links       {}
     :link_hints  (reduce-kv (fn [m rel key]
                               (if-let [v (get rec key)] (assoc m rel [(str v)]) m))
                             {} (or link_hints {}))
     :state       (when state (get rec state))
     :observations (observations rec (or obs-keys []) ref)}))

(defn extract
  "RawItem (kind :json) + its spec -> seq of nodes. Returns [] (with a warning)
   when no spec is configured for this path — most result JSONs are deferred."
  [{:keys [body ref meta]} spec]
  (if-not spec
    (do (binding [*out* *err*]
          (println "json: no spec for" (:path meta) "— skipped"))
        [])
    (let [data (json/read-str (or body "null") :key-fn keyword)]
      (->> (records data spec)
           (mapv (fn [[rkey rec]] (record->node meta spec ref rkey rec)))))))
