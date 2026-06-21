(ns prophet.extract.json
  "Spec-driven extractor for structured JSON data files. The per-repo config maps
   each JSON path to a spec: a node type plus a data-driven observation mapping that
   turns a source's own field names (Polish `nazwa`/`licencja`, nested `eval.tasks`,
   training `curves`) into canonical, kind-tagged observations (ADR-017). The
   mapping is DATA, not code — the lab tunes which field becomes which observation
   without touching this engine.

   Deterministic, no LLM — the highest-value Tier A data is JSON, not prose (the
   slayer repo is Next.js, not Hugo). Parsed order-preserving (clj-yaml, a JSON
   superset) so source key order survives for ordered fields (`eval.tasks`, matrix
   `rows`). State is NOT set here: every fact becomes an observation, and synthesis
   selects State from those observations (invariant: State originates only from
   observations)."
  (:require [clojure.string :as str]
            [clj-yaml.core :as yaml]))

;; --- scalar cleaning -------------------------------------------------------

(def ^:private skip-vals
  "Scalar surface forms that carry no information; they emit no observation."
  #{"—" "-" "–" "n/a" "null"})

(defn- clean
  "A JSON scalar as trimmed text, or nil when it carries nothing (nil / blank / a
   dash or n/a placeholder). Numbers render in their natural form."
  [v]
  (when (some? v)
    (let [s (str/trim (str v))]
      (when-not (or (str/blank? s) (contains? skip-vals (str/lower-case s)))
        s))))

(defn- num-of [v]
  (if (number? v) (double v) (or (some-> v str str/trim parse-double) 0.0)))

(defn- key-str
  "Display form of a parsed map key. clj-yaml keywordizes keys, and a `/` in the
   source key (e.g. `human PL (Aya/OASST)`) splits into namespace/name — so rebuild
   the full surface from both parts."
  [k]
  (cond
    (not (keyword? k)) (str k)
    (namespace k)      (str (namespace k) "/" (name k))
    :else              (name k)))

;; --- records ---------------------------------------------------------------

(defn- records
  "Turn a parsed JSON document into a seq of [record-key record] per the spec's
   :iter mode. :list -> indexed; :dict-vals -> by map key; :dict-by-key uses the
   value at :under (a nested collection)."
  [data {:keys [iter under]}]
  (case (or iter :list)
    :list        (map-indexed (fn [i r] [(str i) r]) data)
    :dict-vals   (map (fn [[k v]] [(name k) v]) data)
    :dict-by-key (records (get data (keyword under)) {:iter :list})))

;; --- observation builders --------------------------------------------------
;; Each obs-spec entry is a map carrying a closed :kind plus an extraction mode.
;; `build-obs` dispatches on the mode and returns 0+ observation maps. Every
;; observation is {:date "" :ref ref :kind kind :text text}; the ref is the entry
;; anchor (`git:slayer@<sha>:<file>#<id>`), shared by all observations of a record.

(defn- mk [ref kind text] {:date "" :ref ref :kind kind :text text})

(defn- tmpl-or-verbatim [tmpl v] (if tmpl (format tmpl v) v))

(defn- entry-mode [e]
  (cond
    (:roll e)            :roll
    (= :kv (:each e))    :kv
    (= :row (:each e))   :row
    (:pick e)            :pick
    (:path e)            :path
    (:fields e)          :fields
    :else                :field))

(defn- build-obs
  "One obs-spec entry over one record -> a seq of observations (possibly empty)."
  [rec ref {:keys [kind tmpl] :as e}]
  (case (entry-mode e)
    ;; single scalar field, templated or verbatim
    :field  (when-let [v (clean (get rec (:field e)))]
              [(mk ref kind (tmpl-or-verbatim tmpl v))])

    ;; several fields into one templated line; skip if the first is empty,
    ;; substitute "" for any later empties.
    :fields (let [vs (map #(get rec %) (:fields e))]
              (when (clean (first vs))
                [(mk ref kind (apply format tmpl (map #(or (clean %) "") vs)))]))

    ;; nested scalar at a path (e.g. [:eval :macro])
    :path   (when-let [v (clean (get-in rec (:path e)))]
              [(mk ref kind (tmpl-or-verbatim tmpl v))])

    ;; map fan-out: one observation per key/value, in source order
    :kv     (for [[k v] (get-in rec (:path e))
                  :let  [cv (clean v)] :when cv]
              (mk ref kind (format (or tmpl "%s: %s") (key-str k) cv)))

    ;; roll a map into one observation; sort :val-desc (numeric) or :key-asc
    :roll   (let [items (for [[k v] (get rec (:field e))
                              :let  [cv (clean v)] :when cv]
                          [(key-str k) v cv])
                  items (case (:sort e)
                          :val-desc (sort-by (juxt (fn [[_ v]] (- (num-of v))) first) items)
                          :key-asc  (sort-by first items)
                          items)]
              (when (seq items)
                [(mk ref kind
                     (str (:prefix e)
                          (str/join (or (:join e) " · ")
                                    (map (fn [[k _ cv]] (format (or (:item e) "%s %s") k cv))
                                         items))))]))

    ;; derived from a series of [x y] points: the final y (e.g. last eval-loss)
    :pick   (let [coll (get-in rec (:path e))]
              (when (and (seq coll) (= :last-y (:pick e)))
                (when-let [y (clean (second (last coll)))]
                  [(mk ref kind (tmpl-or-verbatim tmpl y))])))

    ;; matrix rows: one observation per row, pairing :vals with the section :cols
    :row    (let [cols (get rec (:cols-field e))]
              (for [{nm :name vals :vals} (get-in rec (:path e)) :when nm]
                (mk ref kind
                    (str nm ": "
                         (str/join (or (:join e) " · ")
                                   (map (fn [c v] (str c " " (or (clean v) "—")))
                                        cols vals))))))))

;; --- record -> node --------------------------------------------------------

(defn- record->node
  [{:keys [repo path]} spec base-ref rkey rec]
  (let [{:keys [node-type id title tags moc aliases link_hints observations]} spec
        rid    (str (or (when id (get rec id)) rkey))
        ref    (str base-ref "#" rid)]
    {:type        node-type
     :title       (or (when title (clean (get rec title))) rid)
     :status      :current
     :moc         (or moc [])
     :tags        (vec (when tags (get rec tags)))
     :visibility  :public
     :source_key  (str repo ":" path "#" rid)
     :aliases     (->> (map #(get rec %) (or aliases [])) (remove nil?) (map str) distinct vec)
     :provenance  [{:source :git :ref ref}]
     :links       {}
     :link_hints  (reduce-kv (fn [m rel key]
                               (if-let [v (clean (get rec key))] (assoc m rel [v]) m))
                             {} (or link_hints {}))
     :observations (vec (mapcat #(build-obs rec ref %) (or observations [])))}))

(defn extract
  "RawItem (kind :json) + its spec -> seq of nodes. Returns [] (with a warning)
   when no spec is configured for this path — most result JSONs are deferred."
  [{:keys [body meta ref]} spec]
  (if-not spec
    (do (binding [*out* *err*]
          (println "json: no spec for" (:path meta) "— skipped"))
        [])
    (let [data (yaml/parse-string (or body "null"))]
      (->> (records data spec)
           (mapv (fn [[rkey rec]] (record->node meta spec ref rkey rec)))))))
