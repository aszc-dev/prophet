(ns slayer-kb.index.query
  "Read primitives over the derived index: hybrid search (FTS + exact alias +
   optional vector) and typed-graph traversal. The vector lane is inert when no
   embeddings were built (ADR-009), so v0 search is FTS + exact + graph."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [slayer-kb.store.node :as store]
            [slayer-kb.index.db :as db]
            [slayer-kb.index.embed :as embed]))

(def ^:dynamic *db-path* "kb.db")

(defmacro ^:private with-db [[binding] & body]
  `(with-open [conn# (db/open *db-path*)]
     (let [~binding conn#] ~@body)))

(defn- q [conn sql & params]
  (jdbc/execute! conn (into [sql] params) {:builder-fn rs/as-unqualified-lower-maps}))

;; --- FTS query string ------------------------------------------------------

(defn- fts-match
  "Build an FTS5 MATCH expression: OR of quoted tokens (quotes escaped)."
  [query]
  (->> (str/split (str/trim query) #"\s+")
       (remove str/blank?)
       (map #(str "\"" (str/replace % "\"" "\"\"") "\""))
       (str/join " OR ")))

;; --- search ----------------------------------------------------------------

(defn- vec-hits
  "KNN node ids by cosine, or nil when embeddings are inert/unavailable."
  [conn query limit]
  (when-let [[v] (try (embed/embed-batch [query]) (catch Exception _ nil))]
    (->> (q conn "select node_id, distance from vec_nodes
                  where embedding match ? and k = ? order by distance"
            (embed/vec->literal v) limit)
         (map (fn [r] [(:node_id r) (- 1.0 (/ (:distance r) 2.0))])))))

(defn search
  "Hybrid search. Returns ranked [{:id :title :type :status :snippet :score}].
   Blend: exact alias (highest), FTS bm25, then vector if present."
  ([query] (search query {}))
  ([query {:keys [limit] :or {limit 10}}]
   (with-db [conn]
     (let [alias-ids (->> (q conn "select node_id from aliases where lower(alias)=lower(?)" query)
                          (map :node_id) set)
           fts (q conn "select f.node_id as node_id, n.title as title, n.type as type,
                               n.status as status, bm25(nodes_fts) as rank,
                               snippet(nodes_fts, 2, '[', ']', ' … ', 12) as snippet
                        from nodes_fts f join nodes n on n.id = f.node_id
                        where nodes_fts match ? order by rank limit ?"
                  (fts-match query) (* 3 limit))
           vmap (into {} (vec-hits conn query (* 3 limit)))
           scored (map (fn [{:keys [node_id title type status rank snippet]}]
                         {:id node_id :title title :type type :status status
                          :snippet snippet
                          :score (+ (- (or rank 0))                ; bm25: lower=better
                                    (if (alias-ids node_id) 100.0 0.0)
                                    (* 5.0 (get vmap node_id 0.0)))})
                       fts)
           ;; surface exact-alias nodes even if FTS missed them
           extra (for [id (remove (set (map :id scored)) alias-ids)]
                   (let [n (first (q conn "select id,title,type,status from nodes where id=?" id))]
                     {:id (:id n) :title (:title n) :type (:type n) :status (:status n)
                      :snippet nil :score 100.0}))]
       (->> (concat scored extra) (sort-by :score >) (take limit) vec)))))

;; --- node + graph ----------------------------------------------------------

(defn get-node
  "Full node (frontmatter + observations) by id, read from its file (source of
   truth); the index only supplies the path."
  [id]
  (with-db [conn]
    (when-let [path (:path (first (q conn "select path from nodes where id=?" id)))]
      (store/md->node (slurp path)))))

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
