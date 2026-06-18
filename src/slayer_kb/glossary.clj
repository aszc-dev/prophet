(ns slayer-kb.glossary
  "Deterministic glossary derivation: a `concept` node per recurring vocabulary
   term, linked to every node that uses it (the roam-enabler). Candidate terms are
   the lab's own tags appearing across >=2 nodes — high precision, no LLM. Each
   concept is grounded: its provenance points to the source refs of the nodes that
   use it (provenance-or-nothing applies to the glossary too). A polished 1-2
   sentence definition is left to an optional, grounded, draft-status LLM gap-fill
   (deferred); the used-in links already make terms navigable."
  (:require [clojure.string :as str]
            [slayer-kb.store.node :as store]))

(def ^:private min-uses 2)
(def ^:private min-len 3)   ; drops bare language codes (pl, en)

(defn- term-usage
  "Map term -> set of node-ids that carry it as a tag."
  [nodes]
  (reduce (fn [m {:keys [id tags]}]
            (reduce (fn [m t] (update m (str/lower-case (str t)) (fnil conj #{}) id))
                    m tags))
          {} nodes))

(defn candidates
  "Glossary concepts to (re)materialize from the current store. One per term used
   by >=2 nodes, long enough to be a real term. Keyed by a stable glossary
   source_key, so re-running refreshes used-in links idempotently."
  [nodes]
  (let [by-id (into {} (map (juxt :id identity) nodes))
        usage (term-usage nodes)]
    (for [[term ids] usage
          :when (and (>= (count ids) min-uses)
                     (>= (count term) min-len))
          :let [users (keep by-id ids)
                refs  (->> users
                           (keep #(-> % :provenance first :ref))
                           distinct (take 8) vec)]
          :when (seq refs)]                ; provenance-or-nothing
      {:type        :concept
       :title       term
       :status      :draft               ; definition pending human/LLM grounding
       :moc         ["glosariusz"]
       :tags        []
       :visibility  :public
       :source_key  (str "glossary:tag:" term)
       :aliases     [term]
       :provenance  (mapv (fn [r] {:source :git :ref r}) refs)
       :links       {:mentions (vec (sort ids))}   ; used-in
       :state       nil
       :observations []})))

(defn build!
  "Derive glossary concept nodes from the current note store. Append-only via
   upsert!; re-running is a no-op. Returns a write tally."
  []
  (let [nodes (mapv :node (store/all-notes))
        cands (candidates nodes)
        res   (map store/upsert! cands)]
    (reduce (fn [acc {:keys [status]}] (update acc status (fnil inc 0)))
            {:candidates (count cands)} res)))
