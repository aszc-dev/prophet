(ns slayer-kb.index.schema
  "Derived SQLite index: DDL + full rebuild from the note store. The index holds
   no truth — drop the file and rebuild reconstructs it from the md (invariant #1,
   data-contracts.md §4)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [slayer-kb.store.node :as store]
            [slayer-kb.index.db :as db]
            [slayer-kb.index.embed :as embed]))

(def ddl
  ["create table nodes (
      id text primary key, type text not null, title text not null,
      status text not null, visibility text not null, moc text,
      path text not null, embedding_hash text, updated text not null)"
   "create table links (
      src_id text not null, rel text not null, dst_id text not null,
      primary key (src_id, rel, dst_id))"
   "create table provenance (
      node_id text not null, source text not null, ref text not null,
      primary key (node_id, ref))"
   "create table aliases (
      alias text not null, node_id text not null, primary key (alias, node_id))"
   "create virtual table nodes_fts using fts5(node_id unindexed, title, body)"
   (str "create virtual table vec_nodes using vec0(node_id text primary key, embedding float["
        embed/dim "])")])

(defn- exec! [conn sql] (with-open [st (.createStatement conn)] (.execute st sql)))

(defn- fts-body
  "Searchable text for a node: its synthesized state + every observation line."
  [{:keys [state observations]}]
  (->> (cons state (map :text observations))
       (remove nil?) (str/join "\n")))

(defn- insert-node! [conn {:keys [file node]}]
  (let [{:keys [id type title status visibility moc provenance aliases links
                embedding_hash updated]} node
        ins (fn [sql params]
              (with-open [ps (.prepareStatement conn sql)]
                (dotimes [i (count params)]
                  (.setString ps (inc i) (str (nth params i))))
                (.executeUpdate ps)))]
    (ins "insert into nodes(id,type,title,status,visibility,moc,path,embedding_hash,updated)
          values(?,?,?,?,?,?,?,?,?)"
         [id (name type) title (name (or status :current)) (name (or visibility :public))
          (json/write-str (vec moc)) (.getPath file) embedding_hash updated])
    (doseq [{:keys [source ref]} provenance]
      (ins "insert or ignore into provenance(node_id,source,ref) values(?,?,?)"
           [id (name source) ref]))
    (doseq [a (distinct aliases)]
      (ins "insert or ignore into aliases(alias,node_id) values(?,?)" [a id]))
    (doseq [[rel ids] links, dst ids]
      (ins "insert or ignore into links(src_id,rel,dst_id) values(?,?,?)"
           [id (name rel) dst]))
    (ins "insert into nodes_fts(node_id,title,body) values(?,?,?)"
         [id title (fts-body node)])))

(defn rebuild!
  "Rebuild the entire index at db-path from the note store. Returns a summary."
  [db-path]
  (let [f (io/file db-path)]
    (when (.exists f) (.delete f)))
  (with-open [conn (db/open db-path)]
    (doseq [s ddl] (exec! conn s))
    (let [notes  (vec (store/all-notes))
          _      (doseq [n notes] (insert-node! conn n))
          ;; embeddings: one batch call; inert (skipped) when no endpoint
          vecs   (try (embed/embed-batch (map #(fts-body (:node %)) notes))
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "embed skipped:" (.getMessage e))) nil))]
      (when vecs
        (doseq [[{:keys [node]} v] (map vector notes vecs)]
          (with-open [ps (.prepareStatement conn "insert into vec_nodes(node_id,embedding) values(?,?)")]
            (.setString ps 1 (:id node))
            (.setString ps 2 (embed/vec->literal v))
            (.executeUpdate ps))))
      {:db db-path :nodes (count notes)
       :embedded (if vecs (count notes) 0)
       :mode (if vecs :hybrid :fts+graph)})))
