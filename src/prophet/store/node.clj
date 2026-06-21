(ns prophet.store.node
  "The md+YAML note store — the source of truth. Nodes are plain maps; on disk
   they are YAML frontmatter + a prose body (## State, ## Observations).
   Append-only: updates append observations; replaced facts are superseded with a
   pointer; nothing is hard-deleted (see decisions.md ADR-002/003)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clj-yaml.core :as yaml]
            [prophet.util :as util]))

(def ^:dynamic *kb-root*
  "Filesystem root of the note store; all node paths resolve under it."
  "kb")

;; --- frontmatter <-> map ---------------------------------------------------

(def ^:private fm-keys
  "Frontmatter keys, in canonical on-disk order (data-contracts.md §2)."
  [:id :type :title :status :superseded_by :moc :tags :visibility
   :source_key :aliases :provenance :links :definition
   :content_hash :embedding_hash :updated])

(defn- ordered-frontmatter [node]
  (reduce (fn [m k] (if (contains? node k) (assoc m k (get node k)) m))
          (array-map) fm-keys))

(defn- render-body [{:keys [definition state observations]}]
  (str "\n"
       (when (seq (str definition))
         (str "## Definition (draft — grounded in the provenance below)\n\n"
              definition "\n\n"))
       "## State (synthesized — claims only from the observations below)\n\n"
       (or state "_pending synthesis_") "\n\n"
       "## Observations (append-only; every line carries a ref)\n\n"
       (str/join "\n"
                 (for [{:keys [date ref text]} observations]
                   (str "- " (when (seq (str date)) (str date " ")) "[" ref "] " text)))
       "\n"))

(defn node->md
  "Node map -> on-disk note string: ordered YAML frontmatter + rendered prose body."
  [node]
  (str "---\n"
       (yaml/generate-string (ordered-frontmatter node) :dumper-options {:flow-style :block})
       "---\n"
       (render-body node)))

(def ^:private obs-re #"^- (?:(\S+) )?\[([^\]]+)\] (.*)$")

(defn md->node
  "Parse an on-disk note back into a node map (frontmatter + observations)."
  [^String content]
  (let [[_ fm body] (re-matches #"(?s)---\n(.*?)\n---\n(.*)" content)
        front (yaml/parse-string fm :keywords true)
        obs   (->> (str/split-lines (or body ""))
                   (keep #(when-let [[_ date ref text] (re-matches obs-re %)]
                            {:date date :ref ref :text text})))]
    (assoc front :observations (vec obs) :body (or body ""))))

;; --- paths -----------------------------------------------------------------

(defn node-path
  "Node -> its canonical File under *kb-root*: <type>/<slug>-<id-tail>.md."
  [{:keys [id type title]}]
  (io/file *kb-root* (name type)
           (str (util/slug title) "-" (subs id (max 0 (- (count id) 8))) ".md")))

;; --- reading the store (for id reuse + index rebuild) ----------------------

(defn all-notes
  "Seq of {:file :node} for every note currently in the store."
  []
  (let [root (io/file *kb-root*)]
    (when (.isDirectory root)
      (->> (file-seq root)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (map (fn [f] {:file f :node (md->node (slurp f))}))))))

(defn by-source-key
  "Existing node for a natural source key, or nil. Keeps ULIDs stable across
   re-ingest without re-minting (the store, not a side table, is authoritative)."
  [source-key]
  (some (fn [{:keys [node]}] (when (= (:source_key node) source-key) node))
        (all-notes)))

;; --- writing ---------------------------------------------------------------

(defn- ensure-id
  "Give the node a stable id: reuse the existing node's id for this source key,
   else derive one deterministically from the source key (so a regenerated store
   reproduces it — invariant #5), falling back to a random ULID only when there is
   no source key."
  [node]
  (if-let [existing (and (:source_key node) (by-source-key (:source_key node)))]
    (assoc node :id (:id existing))
    (assoc node :id (or (:id node)
                        (some-> (:source_key node) util/stable-ulid)
                        (util/ulid)))))

(defn- canonical
  "Representation-independent form for hashing. Collapses the two shapes a node can
   take — fresh extraction (vectors, plain maps, keyword values like :git) and
   clj-yaml parse output (LazySeq, OrderedMap, string values like \"git\") — to one
   canonical shape, so the content-hash does not flip between ingest passes.
   Keywords (keys and values) become their name; seqs become vectors; maps become
   key-sorted; an empty string collapses to nil, so a value absent on disk (parsed
   back as nil — e.g. an observation with no date) hashes the same as a fresh
   extraction's \"\" and re-ingest converges in one pass."
  [x]
  (walk/postwalk
   (fn [n]
     (cond
       (keyword? n)                             (name n)
       (= "" n)                                 nil
       (and (sequential? n) (not (vector? n)))  (vec n)
       (map? n)                                 (into (sorted-map) n)
       :else n))
   x))

(defn- ref-identity
  "SHA-independent identity of a provenance ref: `git:<repo>@<sha>:<rest>` collapses
   to `git:<repo>:<rest>`, so the same file location pinned at a different commit
   maps to one identity. Non-git refs are returned unchanged."
  [ref]
  (str/replace (str ref) #"^(git:[^@]+)@[^:]+:" "$1:"))

(defn- obs-identity
  "Identity of an observation for dedup: SHA-independent ref + date + text. The
   commit sha is provenance metadata, not part of what the observation says, so the
   same fact re-ingested at a newer HEAD is the same observation."
  [{:keys [ref date text]}]
  [(ref-identity ref) (str date) text])

(defn merge-observations
  "Append-only union of observation lists, deduped by SHA-independent identity
   (ref-without-sha + date + text). The same fact re-pinned at a newer commit
   collapses to one — keeping the freshest pin — so re-ingest at a moved HEAD does
   not accumulate duplicate observations. Genuinely new observations (different
   text/location) are still preserved and appended (invariant #3); existing order
   is kept."
  [existing-obs new-obs]
  (let [fresh    (into {} (map (juxt obs-identity identity)) new-obs)
        ;; re-pin existing observations to the latest ref when re-seen this pass
        repinned (map (fn [o] (get fresh (obs-identity o) o)) existing-obs)]
    (->> (concat repinned new-obs)
         (reduce (fn [[seen acc] o]
                   (let [k (obs-identity o)]
                     (if (seen k) [seen acc] [(conj seen k) (conj acc o)])))
                 [#{} []])
         second)))

(defn upsert!
  "Materialize a node to disk. Observations merge append-only with any already on
   disk; the rest of the node reflects current state. Content-hash over the
   meaningful payload skips no-op writes. Returns {:status :created|:updated|
   :unchanged :node :file}."
  [node]
  (let [node     (ensure-id node)
        file     (node-path node)
        existing (when (.exists file) (md->node (slurp file)))
        node     (cond-> node
                   existing (assoc :observations
                                   (merge-observations (:observations existing)
                                                       (:observations node)))
                   ;; A grounded definition on disk is knowledge (append-only): a
                   ;; writer that does not supply one (e.g. glossary:build) must not
                   ;; drop it. glossary:define is the only writer that sets it.
                   (and existing (seq (str (:definition existing)))
                        (not (seq (str (:definition node)))))
                   (assoc :definition (:definition existing)))
        payload  (pr-str (canonical (select-keys node [:type :title :status :provenance
                                                       :links :moc :tags :aliases
                                                       :definition :observations])))
        hash     (util/sha256 payload)
        node     (assoc node :content_hash hash)]
    (if (= (:content_hash existing) hash)
      {:status :unchanged :node node :file file}
      (let [node (assoc node :updated (str (java.time.Instant/now)))]
        (io/make-parents file)
        (spit file (node->md node))
        {:status (if existing :updated :created) :node node :file file}))))
