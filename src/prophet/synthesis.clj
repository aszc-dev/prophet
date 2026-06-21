(ns prophet.synthesis
  "State synthesis: a decoupled, idempotent enrichment over already-ingested nodes
   (ADR-016). A single chokepoint (`synthesize-node`) dispatches on `:strategy`;
   v1.4 implements `:extractive` — it SELECTS the top 1-2 existing observations and
   writes their text verbatim into `## State`, each line keeping its existing ref.
   No LLM, no embedder, no new tokens: State stays a pure function of node content,
   so provenance is inherited unchanged (provenance-or-nothing, invariant #3).

   Synthesis never runs at query time. It owns its own writer (NOT store/upsert!):
   `:state` is a body section, not a hashed frontmatter field, so a write through
   upsert! would be skipped by the content-hash. The writer renders the node and
   writes only when the `## State` block actually changes, so re-running is a
   byte-identical no-op."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [prophet.provenance :as prov]
            [prophet.store.node :as store]))

(def ^:private state-plan
  "Per-node-type State selection (ADR-017): ordered [kind take-n] slices.
   Observations are chosen in this kind priority, then source (insertion) order
   within a kind. A result-less experiment is legitimate — when a slice has no
   matching observations it simply contributes nothing. A type not listed falls
   back to insertion order over all observations (prose sources, whose observations
   carry no :kind)."
  {"experiment" [[:result 3] [:decision 1] [:method 1]]
   "axis"       [[:result 3] [:decision 1] [:method 1]]
   "dataset"    [[:definition 1] [:meta 2]]
   "benchmark"  [[:definition 1] [:meta 2]]
   "concept"    [[:definition 1] [:meta 2]]})

(def ^:private fallback-claims
  "How many observations an extractive State selects for a kind-less node, by stable
   insertion order."
  2)

(def ^:private state-line-cap
  "State display is bounded per claim (decisions/results can be long verbatim
   prose). The observation stays full on disk; only the State line is capped."
  200)

(def ^:private state-block-re
  "Captures the prose between the `## State` and `## Observations` headings."
  #"(?s)## State[^\n]*\n+(.*?)\n+## Observations")

(defn- state-block
  "Raw text of a node's `## State` section (from its on-disk body), or nil."
  [node]
  (some-> (:body node) (->> (re-find state-block-re)) second str/trim not-empty))

(defn- current-state
  "State prose already present (extraction-derived or previously synthesized), or
   nil when the node is still `_pending synthesis_` / empty. Mirrors the body+:state
   fallback used by the glossary so a node is treated as filled the same way."
  [node]
  (or (some-> (state-block node)
              (as-> s (when-not (= s "_pending synthesis_") s)))
      (some-> (:state node) str/trim not-empty)))

(defn- resolves? [ref] (some? (prov/ref->url (str ref))))

(defn- cap
  "Bound a claim for State display (length-only, never sentence-split)."
  [text]
  (let [s (str/trim (str text))]
    (if (> (count s) state-line-cap) (str (subs s 0 state-line-cap) "…") s)))

(defn- render-state
  "Selected observations -> State prose: each claim on its own line, carrying its
   existing ref. Selection only — no paraphrase, no merge, no new sentence; long
   claims are capped for display (the observation itself stays verbatim)."
  [obs]
  (str/join "\n" (for [{:keys [ref text]} obs]
                   (str "- [" ref "] " (cap text)))))

(defn- selectable
  "Observations eligible to become State claims: non-blank text with a ref, in
   stable insertion order."
  [node]
  (->> (:observations node)
       (filter (fn [{:keys [ref text]}]
                 (and (seq (str/trim (str ref))) (seq (str/trim (str text))))))))

(defn- select-state-obs
  "Observations selected for State, by the node-type salience plan (ADR-017). Falls
   back to the top `fallback-claims` in insertion order for kind-less nodes."
  [node]
  (let [obs  (selectable node)
        plan (state-plan (name (:type node)))
        by-k (fn [k] (filter #(= k (some-> (:kind %) keyword)) obs))]
    (if-let [picked (and plan (seq (mapcat (fn [[k n]] (take n (by-k k))) plan)))]
      (vec picked)
      (vec (take fallback-claims obs)))))

(defn- extractive
  "Extractive State for one node. Returns {:status ... :node node' [:state s]}:
   :skipped  already has State (never clobber source-provided / prior State),
   :pending  nothing selectable (legitimate — counted, not an error),
   :orphan   a selected claim's ref does not resolve (left pending, NOT written),
   :filled   State synthesized from the top observations."
  [node]
  (cond
    (current-state node) {:status :skipped :node node}
    :else
    (let [sel (select-state-obs node)]
      (cond
        (empty? sel)
        {:status :pending :node node}

        (not (every? (comp resolves? :ref) sel))
        {:status :orphan :node node}

        :else
        (let [s (render-state sel)]
          {:status :filled :node (assoc node :state s) :state s})))))

(defn synthesize-node
  "The synthesis chokepoint (ADR-016). Pure: returns
   {:status :filled|:skipped|:pending|:orphan :node node' [:state s]}.
   Dispatches on `:strategy`; v1.4 implements `:extractive`. `:abstractive` is
   declared in ADR-016 but not built."
  ([node] (synthesize-node node {:strategy :extractive}))
  ([node {:keys [strategy] :or {strategy :extractive}}]
   (case strategy
     :extractive (extractive node)
     (throw (ex-info "unknown synthesis strategy" {:strategy strategy})))))

;; --- the pass --------------------------------------------------------------

(defn run!
  "Synthesize State across the note store and write the filled nodes. Uses its own
   idempotent writer (NOT upsert! — :state is not in the content-hash): a node is
   re-rendered and written only when it is newly filled, so re-running is a no-op.
   A node whose selected claim has an unresolvable ref is left `pending` and
   counted (runtime degrades gracefully — never throws). Returns a tally."
  ([] (run! {:strategy :extractive}))
  ([opts]
   (reduce
    (fn [acc {:keys [file node]}]
      (let [{:keys [status] n :node} (synthesize-node node opts)]
        (when (= status :filled)
          (spit file (store/node->md n)))
        (-> acc (update :total inc) (update status (fnil inc 0)))))
    {:total 0 :filled 0 :skipped 0 :pending 0 :orphan 0}
    (store/all-notes))))

(def ^:private state-ref-re #"(?m)^- \[([^\]]+)\]")

(defn orphans
  "Every synthesized State claim across the store whose ref does not resolve. Empty
   in a healthy corpus (the runtime guard never writes an unresolvable ref); the CI
   gate hard-fails when this is non-empty (provenance invariant). Only scans the
   `## State` block, so ref-less extraction prose contributes nothing."
  []
  (vec
   (for [{:keys [node]} (store/all-notes)
         :let [block (state-block node)]
         :when block
         [_ ref] (re-seq state-ref-re block)
         :when (not (resolves? ref))]
     {:id (:id node) :ref ref})))
