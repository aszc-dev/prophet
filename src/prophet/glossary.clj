(ns prophet.glossary
  "Deterministic glossary derivation: a `concept` node per recurring vocabulary
   term, linked to the nodes that use it (the roam-enabler) AND, crucially, to the
   nodes that DEFINE it. Three deterministic candidate sources, merged per term:

   - tags        — the lab's own tags on >=2 nodes (high precision, no prose).
   - entities    — benchmark/dataset/model/axis titles + aliases (the real jargon;
                   the entity's own `opis`/state is its definition).
   - jargon      — CamelCase / ACRONYM / hyphenated technical tokens in node prose
                   (LLMzSzŁ, EntiGraph, KLEJ, DoRA, held-out…), the jargon that is
                   never a tag.

   A term's links split by strength: `:defined-by` (entity-self + any node whose
   title is about the term) vs weak `:mentions`. Grounding (`define!`) draws
   passages from the defining nodes — that is what lets a definition reach real
   explanatory prose instead of bare category tags. Provenance-or-nothing applies:
   a concept with no source ref is not emitted. A polished 1-2 sentence definition
   is an optional, grounded, draft-status LLM gap-fill (see `define!`)."
  (:require [clojure.string :as str]
            [prophet.index.chat :as chat]
            [prophet.store.node :as store])
  (:import [java.text Normalizer Normalizer$Form]
           [java.util.regex Pattern]))

(def ^:private min-uses 2)
(def ^:private min-len 3)   ; drops bare language codes (pl, en)
(def ^:private entity-types #{"benchmark" "dataset" "model" "axis"})

;; --- term normalization ----------------------------------------------------

(defn- fold
  "Diacritic-folded, lower-cased grouping key. Collapses the surface forms of one
   term (e.g. the title `LLMzSzŁ` and the ascii alias `llmzszl`) onto one key.
   NFD strips combining diacritics; ł/Ł are folded explicitly (no NFD form)."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/replace #"[łŁ]" "l")
      str/lower-case str/trim))

(defn- token-present?
  "True when `folded-term` occurs as a whole token in `folded-text` (not as a
   substring of a larger word). Letters/digits bound the token; hyphens inside the
   term are honoured (held-out matches `held-out`, not `withheld-output`)."
  [folded-term folded-text]
  (boolean
   (and (seq folded-term) (seq folded-text)
        (re-find (re-pattern (str "(?<![\\p{L}\\p{N}])"
                                  (Pattern/quote folded-term)
                                  "(?![\\p{L}\\p{N}])"))
                 folded-text))))

;; --- prose access ----------------------------------------------------------

(defn- state-text
  "Prose from a node's `## State` section, or nil when empty/placeholder. Falls
   back to a fresh node's `:state` (md->node keeps the raw body, not :state)."
  [node]
  (or (some-> (:body node)
              (->> (re-find #"(?s)## State[^\n]*\n+(.*?)\n+## Observations"))
              second str/trim
              (as-> s (when-not (or (str/blank? s) (= s "_pending synthesis_")) s)))
      (some-> (:state node) str/trim not-empty)))

(defn- scan-text
  "Text scanned for jargon tokens / mentions: title + state + observation texts.
   Excludes refs so provenance URLs do not seed bogus acronyms."
  [node]
  (str/join "\n" (remove str/blank?
                         (concat [(:title node)] [(state-text node)]
                                 (map :text (:observations node))))))

;; --- jargon detection ------------------------------------------------------

(def ^:private camel-re   #"[\p{L}\p{N}]*\p{Ll}\p{Lu}[\p{L}\p{N}]*")  ; EntiGraph, DoRA, LLMzSzŁ
;; A standalone all-caps acronym in prose (KLEJ, ORPO, MCQ). The boundaries reject
;; the dominant noise source: fragments of UPPER_SNAKE_CASE code constants and the
;; pipe-tagged morpheme role labels that `flatten-body` folds in from code/data
;; dumps — `ROOT_ALLOMORPH` -> ROOT, `em|INST_SG` -> INST, `GEN_DEFAULT` -> GEN.
;; Left: not after a letter/digit/`_`/`|`. Right: not before a lowercase (CamelCase
;; head -> camel lane), digit (truncated `GSM8K` -> GSM), `_`, or `|`.
(def ^:private acronym-re #"(?<![\p{L}\p{N}_|])\p{Lu}{3,}(?![\p{Ll}\p{N}_|])")
(def ^:private hyphen-re  #"\p{Ll}{2,}(?:-\p{Ll}{2,})+")              ; held-out, reading-comprehension

(defn- strip-code
  "Blank out inline code spans before jargon harvesting so code identifiers
   (`GEN_MODEL`, `DATASET_MANIFEST.md`) never seed concepts. Backtick spans only —
   fenced code is already flattened into prose upstream, where the acronym
   boundaries above keep its SNAKE_CASE / pipe-tag tokens out."
  [text]
  (str/replace (or text "") #"`[^`]*`" " "))

(def ^:private stop-acronyms
  "Common all-caps words the acronym lane would otherwise mint as bogus concepts:
   English/Polish function & heading words, plus the prose-emphasis and enum/split
   labels (HIGH/MID/LOW quality tiers, TRAIN/TEST splits, shouted Polish words) that
   survive the structural boundaries because they appear in real prose, not code.
   Folded (diacritic-stripped, lower-cased) forms."
  #{"and" "not" "only" "all" "for" "the" "nie" "tak" "first" "pass" "data" "json"
    "clean" "contaminated" "czysto" "generatywna" "license" "lineage" "faza" "core"
    "note" "proxy" "done" "todo" "yes" "raw" "via" "non" "tylko" "oraz" "lub"
    "also" "dropped" "high" "mid" "low" "train" "test" "readme"
    "naszym" "otwarty" "prywatny" "prywatnym" "wiedzy"})

(defn- surfaces-by-lane
  "{:camel #{..} :acronym #{..} :hyphen #{..}} jargon tokens in `text` (>= min-len).
   Acronyms in the stoplist are dropped at the source."
  [text]
  {:camel   (->> (re-seq camel-re text)   (filter #(>= (count %) min-len)) set)
   :acronym (->> (re-seq acronym-re text) (filter #(>= (count %) min-len))
                 (remove #(stop-acronyms (fold %))) set)
   :hyphen  (->> (re-seq hyphen-re text)  (filter #(>= (count %) min-len)) set)})

;; --- candidate assembly ----------------------------------------------------

(defn- entity-surfaces
  "Surface forms a node contributes as an entity term: its title + aliases when it
   is an entity-typed node. The node defines these terms (its opis is the def)."
  [node]
  (when (entity-types (name (:type node)))
    (->> (cons (:title node) (:aliases node)) (keep identity) (map str)
         (filter #(>= (count %) min-len)))))

(defn- collect-vocabulary
  "fold-key -> {:surfaces {surface count} :tag? :entity? :camel? :acronym? :hyphen?}.
   The union of every term-like surface seen across the store, before linking. The
   lane flags drive promotion: coined jargon (camel) and named entities/tags pass
   on their own, but hyphenated category-looking terms must also have a defining
   node (handled in `candidates`), keeping structured category labels out."
  [nodes]
  (reduce
   (fn [voc node]
     (let [add  (fn [voc surface flag]
                  (-> voc
                      (update-in [(fold surface) :surfaces surface] (fnil inc 0))
                      (assoc-in  [(fold surface) flag] true)))
           lane (surfaces-by-lane (strip-code (scan-text node)))]
       (as-> voc v
         (reduce #(add %1 (str %2) :tag?) v
                 (filter #(>= (count (str %)) min-len) (:tags node)))
         (reduce #(add %1 %2 :entity?)  v (entity-surfaces node))
         (reduce #(add %1 %2 :camel?)   v (:camel lane))
         (reduce #(add %1 %2 :acronym?) v (:acronym lane))
         (reduce #(add %1 %2 :hyphen?)  v (:hyphen lane)))))
   {} nodes))

(defn- display-surface
  "Canonical display form for a term: most upper-case-rich surface wins (LLMzSzŁ
   over llmzszl), then longest, then alphabetical — deterministic."
  [surfaces]
  (->> (keys surfaces)
       (sort-by (fn [s] [(- (count (re-seq #"\p{Lu}" s))) (- (count s)) s]))
       first))

(defn- links-for
  "Split the nodes related to `fold-key` into defining vs mentioning ids.
   - defined-by: entity-self nodes (an entity whose own title/alias is the term)
     and any node whose TITLE is about the term — their prose grounds the def.
   - mentions: defined-by ∪ tag-carriers ∪ nodes whose prose names the term."
  [fold-key entity? nodes]
  (let [defines  (for [n nodes
                       :when (or (and entity? (entity-types (name (:type n)))
                                      (some #(= fold-key (fold %))
                                            (cons (:title n) (:aliases n))))
                                 (token-present? fold-key (fold (:title n))))]
                   (:id n))
        mentions (for [n nodes
                       :when (or (some #(= fold-key (fold %)) (:tags n))
                                 (token-present? fold-key (fold (scan-text n))))]
                   (:id n))]
    {:defined-by (vec (sort (distinct defines)))
     :mentions   (vec (sort (distinct (concat defines mentions))))}))

(defn candidates
  "Glossary concepts to (re)materialize from the current store. One per term that
   appears in >=2 nodes and is term-like (tag, entity, or detected jargon). Keyed
   by a stable source_key (`glossary:tag:<term>` for tag-sourced terms — preserving
   existing ids — else `glossary:term:<fold>`), so re-running is idempotent."
  [nodes]
  ;; Concept nodes are derived from these sources — never source a concept from a
  ;; concept, or re-running build! churns as concepts accrete into the vocabulary.
  (let [nodes (remove #(= "concept" (name (:type %))) nodes)
        by-id (into {} (map (juxt :id identity) nodes))
        voc   (collect-vocabulary nodes)]
    (for [[fk {:keys [surfaces tag? entity? camel? acronym? hyphen?]}] voc
          :let  [{:keys [defined-by mentions]} (links-for fk entity? nodes)]
          ;; promotion: tags, named entities, and coined jargon (CamelCase / non-stop
          ;; acronyms) stand on their own; hyphenated terms must also have a defining
          ;; node, which filters structured category labels (eval-pl-core, no-answer).
          :when (and (>= (count mentions) min-uses)
                     (or tag? entity? camel? acronym?
                         (and hyphen? (seq defined-by))))
          :let  [display (display-surface surfaces)
                 ;; provenance: defining refs first, then mention refs
                 refs (->> (concat defined-by mentions)
                           (keep by-id) distinct
                           (keep #(-> % :provenance first :ref))
                           distinct (take 8) vec)]
          :when (seq refs)]                ; provenance-or-nothing
      {:type        :concept
       :title       display
       :status      :draft
       :moc         ["glosariusz"]
       :tags        []
       :visibility  :public
       :source_key  (if tag? (str "glossary:tag:" fk) (str "glossary:term:" fk))
       :aliases     (vec (sort (keys surfaces)))
       :provenance  (mapv (fn [r] {:source :git :ref r}) refs)
       :links       (cond-> {:mentions mentions}
                      (seq defined-by) (assoc :defined-by defined-by))
       :state       nil
       :observations []})))

(defn- prune-concepts!
  "Remove derived concept nodes whose `source_key` is no longer a candidate. A
   concept is a pure projection of the store (no own observations); when a sourcing
   heuristic tightens, the orphans it minted must not linger. Provenance-or-nothing
   in reverse: a concept with a grounded `:definition` is knowledge, so it is
   archived (`status :archived`), never deleted (invariant #3); a definition-less
   concept is a derivation artifact and is removed. Returns {:pruned n :archived n}."
  [cand-keys]
  (let [keep? (set cand-keys)]
    (reduce
     (fn [acc {:keys [file node]}]
       (if (and (= "concept" (name (:type node)))
                (not (keep? (:source_key node))))
         (if (seq (str (:definition node)))
           (do (store/upsert! (assoc node :status :archived))
               (update acc :archived (fnil inc 0)))
           (do (.delete file)
               (update acc :pruned (fnil inc 0))))
         acc))
     {:pruned 0 :archived 0} (store/all-notes))))

(defn build!
  "Derive glossary concept nodes from the current note store. Candidates are
   upserted (append-only; re-running is a verified no-op), then orphaned concepts
   from earlier, looser heuristics are pruned. Returns a write tally."
  []
  (let [nodes (mapv :node (store/all-notes))
        cands (candidates nodes)
        res   (map store/upsert! cands)
        tally (reduce (fn [acc {:keys [status]}] (update acc status (fnil inc 0)))
                      {:candidates (count cands)} (doall res))]
    (merge tally (prune-concepts! (map :source_key cands)))))

;; --- optional grounded definitions (LLM gap-fill, ADR-005) -----------------

(defn- grounding-units
  "Ref-bearing text units from a node: its State prose (attributed to the node's
   primary provenance ref) and each observation (its own ref)."
  [node]
  (let [prov-ref (-> node :provenance first :ref)]
    (concat
     (when-let [s (and prov-ref (state-text node))] [{:ref prov-ref :text s}])
     (for [{:keys [ref text]} (:observations node)
           :when (and (seq (str ref)) (seq (str text)))]
       {:ref ref :text text}))))

(defn- defining-passages
  "Passages from a node that DEFINES the term: its title and State prose (the
   node is about the term, so these ground it unconditionally) plus any
   observation that names the term."
  [fold-term node]
  (let [prov-ref (-> node :provenance first :ref)]
    (when prov-ref
      (->> (concat
            [{:ref prov-ref :text (:title node)}]
            (when-let [s (state-text node)] [{:ref prov-ref :text s}])
            (for [{:keys [ref text]} (:observations node)
                  :when (and (seq (str ref)) (token-present? fold-term (fold text)))]
              {:ref ref :text text}))
           (filter #(seq (str (:text %))))))))

(defn passages-for
  "Grounding passages for a concept. From its `:defined-by` nodes: title + state +
   term-naming observations (the link already establishes relevance). From its
   weak `:mentions`: only the units that name the term. A term that is merely
   mentioned, never defined or discussed, yields nothing -> stays undefined."
  [concept by-id]
  (let [ft        (fold (:title concept))
        def-ids   (set (get-in concept [:links :defined-by]))
        def-nodes (keep by-id def-ids)
        men-nodes (keep by-id (remove def-ids (get-in concept [:links :mentions])))]
    (->> (concat (mapcat #(defining-passages ft %) def-nodes)
                 (for [n men-nodes, u (grounding-units n)
                       :when (token-present? ft (fold (:text u)))]
                   u))
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
        ;; The model is inconsistent about ref format: sometimes the bare ref,
        ;; sometimes the whole "[ref] text" passage line echoed back. Accept a cited
        ;; ref when it contains a supplied bare ref as a substring — that still
        ;; rejects fabricated provenance (a supplied git URL won't appear by chance).
        cites?   (fn [r] (some #(str/includes? (str r) %) supplied))
        resp     (chat/complete-json (definition-messages term passages))]
    (when (map? resp)
      (let [{:keys [definition grounded refs]} resp
            definition (str/trim (str definition))]
        (when (and (true? grounded)
                   (seq definition)
                   (<= (count definition) 400)
                   (seq refs)
                   (every? cites? refs))
          definition)))))

(defn define!
  "Optional grounded gap-fill: a 1-2 sentence draft definition per `concept` that
   lacks one, derived strictly from the passages of its defining/mentioning nodes.
   Writes through upsert! (append-only, canonical hash, status stays :draft).
   Discipline:
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
           (let [passages (passages-for c by-id)
                 def-text (when (seq passages) (gen-definition (:title c) passages))]
             (if def-text
               (do (store/upsert! (assoc c :definition def-text))
                   (update acc :defined (fnil inc 0)))
               (update acc :ungrounded (fnil inc 0))))))
       {:concepts (count concepts)} concepts))))
