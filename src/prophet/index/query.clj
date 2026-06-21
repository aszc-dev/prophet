(ns prophet.index.query
  "Read primitives over the derived index: hybrid search (FTS + exact alias +
   optional vector) and typed-graph traversal. The vector lane is inert when no
   embeddings were built (ADR-009), so v0 search is FTS + exact + graph."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [prophet.store.node :as store]
            [prophet.provenance :as prov]
            [prophet.index.db :as db]
            [prophet.index.embed :as embed]))

(def ^:dynamic *db-path* "kb.db")

(defmacro ^:private with-db [[binding] & body]
  `(with-open [conn# (db/open *db-path*)]
     (let [~binding conn#] ~@body)))

(defn- q [conn sql & params]
  (jdbc/execute! conn (into [sql] params) {:builder-fn rs/as-unqualified-lower-maps}))

;; --- FTS query string ------------------------------------------------------

(defn- fts-tokens
  "Quoted, escaped tokens of a query (blanks dropped)."
  [query]
  (->> (str/split (str/trim query) #"\s+")
       (remove str/blank?)
       (mapv #(str "\"" (str/replace % "\"" "\"\"") "\""))))

(defn- fts-match
  "FTS5 MATCH expression over all columns: OR of quoted tokens."
  [query]
  (str/join " OR " (fts-tokens query)))

(defn- title-match
  "FTS5 MATCH expression scoped to the title column: a node whose TITLE carries the
   query terms is high-signal (e.g. an exact entity name), independent of how often
   the terms appear in bodies. nil when the query has no usable tokens."
  [query]
  (when-let [toks (seq (fts-tokens query))]
    (str "{title} : (" (str/join " OR " toks) ")")))

;; --- search ----------------------------------------------------------------
;;
;; Each lane (exact alias, FTS bm25, vector KNN) produces an INDEPENDENT ranked
;; list of candidate ids; the union is fused with Reciprocal Rank Fusion (ADR:
;; milestone #8). RRF is rank-based and scale-invariant, so the vector lane EXPANDS
;; recall (adds cross-lingual candidates FTS can't tokenize) without its raw cosine
;; ever displacing a strong FTS hit — a doc keeps each lane's rank contribution and
;; lanes only ever add. Weights/k are knobs (lever 2), kept as vars for tuning.

(def ^:dynamic *rrf-k*
  "RRF damping: score contribution of a lane is w/(rrf-k + rank). Larger k flattens
   the rank curve so deep hits still matter; smaller sharpens the top-rank edge so a
   strong single-lane hit is not crowded out by mediocre multi-lane docs. Defaults
   tuned (milestone #8) on the retrieval gold set against a live embedding endpoint;
   they sit on a wide plateau (EN r@10 90% / PL 100%), not a knife-edge."
  10)
(def ^:dynamic *w-alias* "Exact-alias lane weight (dominates: an exact match is authoritative)." 3.0)
(def ^:dynamic *w-title* "Title-scoped FTS lane weight (query terms in the node title)." 1.5)
(def ^:dynamic *w-fts*   "Full-text (title+body) FTS bm25 lane weight." 1.0)
(def ^:dynamic *w-vec*   "Vector KNN lane weight (cross-lingual recall expander)." 1.5)

(defn- candidate-pool
  "KNN pool / per-lane cap. Must exceed `limit` or the vector lane cannot lift
   recall@limit; floored at 40 so small requests still get a deep cross-lingual pool."
  [limit]
  (max 40 (* 4 limit)))

(defn- vec-lane
  "Ordered KNN node ids (closest cosine first), or nil when embeddings are
   inert/unavailable."
  [conn query limit]
  (when-let [[v] (try (embed/embed-batch [query]) (catch Exception _ nil))]
    (->> (q conn "select node_id from vec_nodes
                  where embedding match ? and k = ? order by distance"
            (embed/vec->literal v) limit)
         (mapv :node_id))))

(defn- rrf
  "Reciprocal Rank Fusion over lanes [{:w weight :ids [id ...best-first]}].
   Returns {id fused-score}; an id absent from a lane contributes 0 there."
  [lanes]
  (reduce (fn [acc {:keys [w ids]}]
            (reduce (fn [a [i id]]
                      (update a id (fnil + 0.0) (/ (double w) (+ *rrf-k* (inc i)))))
                    acc (map-indexed vector ids)))
          {} lanes))

(defn- head-snippet
  "Undelimited fallback snippet for a hit with no FTS span (alias/vec-only lane):
   the head of the node's indexed body (state + observations). No match markers —
   there is no lexical span to highlight, but a snippet is more useful than nil."
  [body]
  (some-> body str/trim not-empty (str/replace #"\s+" " ")
          (as-> s (if (> (count s) 180) (str (subs s 0 180) " …") s))))

(def ^:private ident-char "[\\p{L}\\p{N}._/:-]")

(defn- demask-snippet
  "FTS5 marks matched tokens with [ ]. A highlight that abuts an identifier
   character (inside a URL or a hyphenated identifier, e.g. `.../[llmzszl]-dataset`)
   reads as a broken literal. Unwrap any such glued highlight, keeping only
   whole-token highlights bounded by whitespace/punctuation. Render-only — the
   stored observations are untouched."
  [s]
  (some-> s
          (str/replace (re-pattern (str "(?<=" ident-char ")\\[([^\\]]*)\\]")) "$1")
          (str/replace (re-pattern (str "\\[([^\\]]*)\\](?=" ident-char ")")) "$1")))

(defn- node-meta
  "Metadata {id {:title :type :status :snippet}} for `ids`. Reuses FTS rows (which
   carry the matched, delimited snippet); for the rest — alias/vec-only ids with no
   FTS span — falls back to the head of the indexed body so every hit has a snippet."
  [conn ids fts-rows]
  (let [from-fts (into {} (map (fn [r] [(:node_id r)
                                        (-> (select-keys r [:title :type :status :snippet])
                                            (update :snippet demask-snippet))]))
                       fts-rows)
        missing  (remove from-fts ids)]
    (cond-> from-fts
      (seq missing)
      (into (map (fn [r] [(:id r) {:title (:title r) :type (:type r) :status (:status r)
                                   :snippet (head-snippet (:body r))}]))
            (apply q conn (str "select n.id as id, n.title as title, n.type as type,
                                       n.status as status, f.body as body
                                from nodes n join nodes_fts f on f.node_id = n.id
                                where n.id in ("
                               (str/join "," (repeat (count missing) "?")) ")")
                   missing)))))

(defn- provenance-for
  "{id [{:source :ref :source_url} ...]} for `ids`, from the provenance table.
   `:source_url` is DERIVED from the ref at query time (nil for unmappable refs)."
  [conn ids]
  (when (seq ids)
    (->> (apply q conn (str "select node_id, source, ref from provenance where node_id in ("
                            (str/join "," (repeat (count ids) "?")) ")")
                ids)
         (map (fn [{:keys [node_id source ref]}]
                [node_id {:source source :ref ref :source_url (prov/ref->url ref)}]))
         (reduce (fn [m [id p]] (update m id (fnil conj []) p)) {}))))

(defn search
  "Hybrid search. Returns ranked
   [{:id :title :type :status :snippet :score :provenance :source_url}].
   `:provenance` is the node's source refs; `:source_url` is the rendered,
   commit-pinned GitHub blob links (nil-stripped) — cite them per claim.
   Builds a candidate UNION across exact-alias, FTS bm25, and vector-KNN lanes,
   then fuses with RRF. The vector lane is inert (and thus contributes nothing)
   when no embeddings/endpoint are present (ADR-009)."
  ([query] (search query {}))
  ([query {:keys [limit] :or {limit 10}}]
   (with-db [conn]
     (let [pool      (candidate-pool limit)
           alias-ids (->> (q conn "select node_id from aliases where lower(alias)=lower(?)" query)
                          (mapv :node_id))
           fts-rows  (q conn "select f.node_id as node_id, n.title as title, n.type as type,
                                     n.status as status,
                                     snippet(nodes_fts, 2, '[', ']', ' … ', 12) as snippet
                              from nodes_fts f join nodes n on n.id = f.node_id
                              where nodes_fts match ? order by bm25(nodes_fts) limit ?"
                        (fts-match query) pool)
           title-ids (when-let [m (title-match query)]
                       (->> (q conn "select node_id from nodes_fts where nodes_fts match ?
                                     order by bm25(nodes_fts) limit ?" m pool)
                            (mapv :node_id)))
           vec-ids   (vec-lane conn query pool)
           fused     (rrf [{:w *w-alias* :ids alias-ids}
                           {:w *w-title* :ids (or title-ids [])}
                           {:w *w-fts*   :ids (mapv :node_id fts-rows)}
                           {:w *w-vec*   :ids (or vec-ids [])}])
           top-ids   (->> fused (sort-by (fn [[id s]] [(- s) id])) (take limit) (mapv key))
           meta      (node-meta conn top-ids fts-rows)
           prov      (provenance-for conn top-ids)]
       (mapv (fn [id]
               (let [m (meta id)
                     ps (get prov id [])]
                 {:id id :title (:title m) :type (:type m) :status (:status m)
                  :snippet (:snippet m) :score (fused id)
                  :provenance ps
                  :source_url (into [] (keep :source_url) ps)}))
             top-ids)))))

;; --- node + graph ----------------------------------------------------------

(defn- with-source-urls
  "Decorate a node read from disk: add `:source_url` alongside each node-level
   provenance ref and each observation's inline ref, so the model can cite the
   exact line it used. Derived from the stored ref — never persisted."
  [node]
  (-> node
      (update :provenance
              (fn [ps] (mapv #(assoc % :source_url (prov/ref->url (:ref %))) ps)))
      (update :observations
              (fn [os] (mapv #(assoc % :source_url (prov/ref->url (:ref %))) os)))))

(defn get-node
  "Full node (frontmatter + observations) by id, read from its file (source of
   truth); the index only supplies the path. Each provenance ref and observation
   carries a derived `:source_url` (commit-pinned GitHub blob link)."
  [id]
  (with-db [conn]
    (when-let [path (:path (first (q conn "select path from nodes where id=?" id)))]
      (with-source-urls (store/md->node (slurp path))))))

(defn neighbors
  "Directly linked nodes (outgoing + incoming) with the relation and direction."
  [id]
  (with-db [conn]
    {:out (q conn "select rel, dst_id as id from links where src_id=?" id)
     :in  (q conn "select rel, src_id as id from links where dst_id=?" id)}))

(defn traverse
  "Multi-hop outgoing traversal from id, optionally filtered to one rel, up to
   depth. Returns the set of reached node ids with the hop distance."
  ([id] (traverse id {}))
  ([id {:keys [rel depth] :or {depth 2}}]
   (with-db [conn]
     (loop [frontier #{id}, seen {id 0}, d 0]
       (if (or (>= d depth) (empty? frontier))
         (dissoc seen id)
         (let [sql (str "select dst_id as id from links where src_id=?"
                        (when rel " and rel=?"))
               nxt (->> frontier
                        (mapcat (fn [src]
                                  (apply q conn sql src (when rel [(name rel)]))))
                        (map :id)
                        (remove seen) set)]
           (recur nxt
                  (into seen (map #(vector % (inc d)) nxt))
                  (inc d))))))))

(defn whats-new
  "Recency feed: nodes ordered by updated desc, optionally filtered by MOC."
  ([] (whats-new {}))
  ([{:keys [moc limit] :or {limit 20}}]
   (with-db [conn]
     (cond->> (q conn "select id,title,type,status,moc,updated from nodes
                       order by updated desc limit ?" (* 3 limit))
       moc (filter #(str/includes? (str (:moc %)) moc))
       true (take limit)
       true vec))))
