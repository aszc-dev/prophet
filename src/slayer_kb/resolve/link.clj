(ns slayer-kb.resolve.link
  "v0 resolver: exact + structural linking only. Cards/recipes seed canonical
   names; link_hints from extractors resolve to node ids through an alias map
   built from titles + aliases. No embedding/cosine attach in v0 — that fuzzy
   path is a Tier B (Discord) need (ingest-repo.md §5)."
  (:require [clojure.string :as str]
            [slayer-kb.util :as util]))

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
   source_key, else mint a fresh ULID."
  [nodes existing]
  (let [by-key (into {} (map (juxt :source_key :id) existing))]
    (mapv (fn [n]
            (assoc n :id (or (:id n)
                             (get by-key (:source_key n))
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

(defn prepare
  "Two-phase v0 resolution over a batch of freshly extracted nodes plus the
   existing store nodes: assign stable ids, then resolve structural links.
   Returns nodes ready for upsert."
  [raw-nodes existing-nodes]
  (let [batch (assign-ids (vec raw-nodes) existing-nodes)
        aidx  (alias-index (concat existing-nodes batch))]
    (mapv #(resolve-hints % aidx) batch)))
