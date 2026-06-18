(ns slayer-kb.glossary
  "Deterministic glossary derivation: a `concept` node per recurring vocabulary
   term, linked to every node that uses it (the roam-enabler). Candidate terms are
   the lab's own tags appearing across >=2 nodes — high precision, no LLM. Each
   concept is grounded: its provenance points to the source refs of the nodes that
   use it (provenance-or-nothing applies to the glossary too). A polished 1-2
   sentence definition is left to an optional, grounded, draft-status LLM gap-fill
   (deferred); the used-in links already make terms navigable."
  (:require [clojure.string :as str]
            [slayer-kb.index.chat :as chat]
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

;; --- optional grounded definitions (LLM gap-fill, ADR-005) -----------------

(defn- state-text
  "Prose from a parsed node's `## State` section, or nil when empty/placeholder.
   md->node keeps the raw body but does not structure it, so we slice it here."
  [body]
  (some-> body
          (->> (re-find #"(?s)## State[^\n]*\n+(.*?)\n+## Observations"))
          second str/trim
          (as-> s (when-not (or (str/blank? s) (= s "_pending synthesis_")) s))))

(defn- grounding-units
  "Ref-bearing text units from a using node: its State prose (attributed to the
   node's primary provenance ref) and each observation (its own ref)."
  [node]
  (let [prov-ref (-> node :provenance first :ref)]
    (concat
     (when-let [s (and prov-ref (state-text (:body node)))]
       [{:ref prov-ref :text s}])
     (for [{:keys [ref text]} (:observations node)
           :when (and (seq (str ref)) (seq (str text)))]
       {:ref ref :text text}))))

(defn passages-for
  "Grounding passages for `term`: the text units across `users` that actually
   mention the term (case-insensitive), each carrying its source ref. These — and
   only these — are fed to the model; a term that is merely tagged but never
   discussed yields no passages, so it stays undefined (no fabrication)."
  [term users]
  (let [needle (str/lower-case term)]
    (->> users
         (mapcat grounding-units)
         (filter #(str/includes? (str/lower-case (:text %)) needle))
         distinct vec)))

(defn- definition-messages [term passages]
  [{:role "system"
    :content (str "You write glossary definitions for a Polish LLM research lab. "
                  "Define the term using ONLY the supplied passages — never outside "
                  "knowledge. If the passages merely mention the term without "
                  "explaining it, you cannot define it. Reply with a single JSON "
                  "object: {\"definition\": string (1-2 sentences, in the language of "
                  "the passages), \"grounded\": boolean, \"refs\": [string] (the refs "
                  "of the passages you used, taken verbatim from those given)}. "
                  "Set grounded=false (and definition=\"\") when the passages do not "
                  "explain the term. Do not invent refs.")}
   {:role "user"
    :content (str "Term: " term "\n\nPassages:\n"
                  (str/join "\n" (map-indexed
                                  (fn [i {:keys [ref text]}]
                                    (str (inc i) ". [" ref "] " text))
                                  passages)))}])

(defn- gen-definition
  "Ask the model for a grounded definition of `term` from `passages`. Returns the
   definition string, or nil when the model declines, the output fails validation,
   or the endpoint is inert. Validate-or-skip: grounded must be true, the text
   non-blank and within 2-sentence range, and every cited ref one we supplied
   (rejects hallucinated provenance)."
  [term passages]
  (let [supplied (set (map :ref passages))
        resp     (chat/complete-json (definition-messages term passages))]
    (when (map? resp)
      (let [{:keys [definition grounded refs]} resp
            definition (str/trim (str definition))]
        (when (and (true? grounded)
                   (seq definition)
                   (<= (count definition) 400)
                   (seq refs)
                   (every? supplied refs))
          definition)))))

(defn define!
  "Optional grounded gap-fill: a 1-2 sentence draft definition per `concept` that
   lacks one, derived strictly from passages where the term appears in its using
   nodes. Writes through upsert! (append-only, canonical hash, status stays
   :draft). Discipline:
   - Generate-once / idempotent: a concept that already has a definition is SKIPPED,
     never regenerated — LLM output is non-deterministic, so regeneration would
     churn the content-hash on every run. Re-running define! is a verified no-op.
   - Grounded-or-nothing: ungroundable terms are left undefined (no fabrication).
   - Inert fallback: no chat endpoint -> skip generation entirely, like the inert
     vector lane (ADR-009).
   Returns a tally."
  []
  (let [notes    (store/all-notes)
        by-id    (into {} (map (juxt (comp :id :node) :node)) notes)
        concepts (->> notes (map :node) (filter #(= "concept" (name (:type %)))))]
    (if (nil? (chat/config))
      {:concepts (count concepts) :inert true}
      (reduce
       (fn [acc c]
         (cond
           (seq (str (:definition c)))
           (update acc :skipped (fnil inc 0))

           :else
           (let [users    (keep by-id (get-in c [:links :mentions]))
                 passages (passages-for (:title c) users)
                 def-text (when (seq passages) (gen-definition (:title c) passages))]
             (if def-text
               (do (store/upsert! (assoc c :definition def-text))
                   (update acc :defined (fnil inc 0)))
               (update acc :ungrounded (fnil inc 0))))))
       {:concepts (count concepts)} concepts))))
