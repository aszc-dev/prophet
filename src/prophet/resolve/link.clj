(ns prophet.resolve.link
  "v0 resolver: exact + structural linking only. Cards/recipes seed canonical
   names; link_hints from extractors resolve to node ids through an alias map
   built from titles + aliases. Experiment/dataset/axis nodes additionally get
   typed links from in-content entity mentions (an experiment that names a
   benchmark -> :uses-benchmark), resolved through the same alias table — no
   hand-wiring. No embedding/cosine attach in v0 — that fuzzy path is a Tier B
   (Discord) need (ingest-repo.md §5)."
  (:require [clojure.string :as str]
            [prophet.util :as util])
  (:import [java.text Normalizer Normalizer$Form]
           [java.util.regex Pattern]))

(defn- norm [s] (when s (str/lower-case (str/trim (str s)))))

(defn- node-aliases
  "All strings that should resolve to this node: title + declared aliases + slug."
  [{:keys [title aliases]}]
  (->> (concat [title] aliases [(util/slug title)])
       (keep norm) distinct))

(defn alias-index
  "Map normalized-alias -> node id, across the given nodes. Earlier nodes win on
   collision (cards are ingested deterministically; ties are logged by the caller
   if needed)."
  [nodes]
  (reduce (fn [m n]
            (reduce (fn [m a] (if (contains? m a) m (assoc m a (:id n))))
                    m (node-aliases n)))
          {} nodes))

(defn- assign-ids
  "Give every node a stable id: reuse the existing node's id for a matching
   source_key, else derive one deterministically from the source_key so a
   regenerated store reproduces it (invariant #5); random ULID only when a node
   has no source_key."
  [nodes existing]
  (let [by-key (into {} (map (juxt :source_key :id) existing))]
    (mapv (fn [n]
            (assoc n :id (or (:id n)
                             (get by-key (:source_key n))
                             (some-> (:source_key n) util/stable-ulid)
                             (util/ulid))))
          nodes)))

(defn- resolve-hints
  "Turn :link_hints (rel -> [name-hint]) into :links (rel -> [id]) via the alias
   index. Unresolved hints are dropped from links but kept under :unresolved for
   visibility (a later review/Tier-B pass can revisit them)."
  [node aidx]
  (let [{:keys [resolved unresolved]}
        (reduce-kv
         (fn [acc rel hints]
           (reduce (fn [acc h]
                     (if-let [id (get aidx (norm h))]
                       (update-in acc [:resolved rel] (fnil conj []) id)
                       (update acc :unresolved (fnil conj []) {:rel rel :hint h})))
                   acc hints))
         {:resolved {} :unresolved []}
         (:link_hints node))]
    (cond-> (assoc node :links (merge-with into (:links node) resolved))
      (seq unresolved) (assoc :unresolved unresolved)
      true (dissoc :link_hints))))

;; --- in-content entity mentions -> typed links -----------------------------
;; Experiment/dataset/axis prose names the entities it concerns (an experiment
;; "v3 — LLMzSzŁ acc: 66.8" measures on the LLMzSzŁ benchmark). Resolve those
;; mentions to typed links through the same alias table, so `traverse` reaches the
;; named entity. Deterministic: whole-token match on a diacritic-folded surface.

(def ^:private min-mention-len 3)

(def ^:private entity-rel
  "Link relation for a mention, keyed by the mentioned entity's node type."
  {"benchmark" :uses-benchmark
   "dataset"   :uses-dataset
   "model"     :base-model})

(def ^:private mentioner-types
  "Node types whose content is scanned for entity mentions."
  #{"experiment" "axis" "dataset"})

(defn- fold
  "Diacritic-folded, lower-cased surface (ł/Ł -> l). Collapses LLMzSzŁ and llmzszl."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/replace #"[łŁ]" "l")
      str/lower-case str/trim))

(defn- whole-token?
  "True when `folded-term` occurs as a whole token in `folded-text` (letters/digits
   bound it), so a substring inside a longer identifier does not match."
  [folded-term folded-text]
  (boolean
   (and (>= (count folded-term) min-mention-len) (seq folded-text)
        (re-find (re-pattern (str "(?<![\\p{L}\\p{N}])"
                                  (Pattern/quote folded-term)
                                  "(?![\\p{L}\\p{N}])"))
                 folded-text))))

(defn- mention-index
  "folded-surface -> {:id :rel :type} for every entity node (one entry per alias).
   Earlier nodes win on collision."
  [nodes]
  (reduce
   (fn [m n]
     (if-let [rel (entity-rel (some-> (:type n) name))]
       (reduce (fn [m a]
                 (let [k (fold a)]
                   (if (or (< (count k) min-mention-len) (contains? m k))
                     m
                     (assoc m k {:id (:id n) :rel rel}))))
               m (node-aliases n))
       m))
   {} nodes))

(defn- resolve-mentions
  "Add typed links for entity surfaces named in a mentioner node's title +
   observation texts. Skips self-links; ids per relation are sorted (determinism)."
  [node midx]
  (if-not (mentioner-types (some-> (:type node) name))
    node
    (let [text  (fold (str/join "\n" (cons (:title node) (map :text (:observations node)))))
          found (for [[surf {:keys [id rel]}] midx
                      :when (and (not= id (:id node)) (whole-token? surf text))]
                  [rel id])
          links (reduce (fn [m [rel id]] (update m rel (fnil conj #{}) id)) {} found)
          links (into {} (map (fn [[rel ids]] [rel (vec (sort ids))]) links))]
      (update node :links #(merge-with (comp vec distinct into) % links)))))

(defn prepare
  "Two-phase v0 resolution over a batch of freshly extracted nodes plus the
   existing store nodes: assign stable ids, resolve structural link_hints, then
   resolve in-content entity mentions to typed links. Returns nodes ready for
   upsert."
  [raw-nodes existing-nodes]
  (let [batch (assign-ids (vec raw-nodes) existing-nodes)
        all   (concat existing-nodes batch)
        aidx  (alias-index all)
        midx  (mention-index all)]
    (mapv #(-> % (resolve-hints aidx) (resolve-mentions midx)) batch)))
