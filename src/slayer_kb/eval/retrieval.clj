(ns slayer-kb.eval.retrieval
  "Layer-4 retrieval eval: a hand-built gold set of PL+EN paraphrase queries ->
   expected node ids, scored with recall@k and MRR. Where eval:fidelity scores the
   extraction PIPELINE, this scores the PRODUCT — does a human's question reach the
   right note? The gold set (eval/retrieval-gold.edn) is authored by intent
   and independent of current ranking, so a regression in the blend weights or the
   index surfaces here as a recall/MRR drop.

   With the embedding endpoint up (SLAYER_EMBED_*) the vector lane is active and the
   run reflects :hybrid; otherwise it degrades to FTS + alias + graph (:fts), and
   the cross-lingual EN->PL entries are the ones that suffer — a useful contrast."
  (:require [clojure.edn :as edn]
            [slayer-kb.index.embed :as embed]
            [slayer-kb.index.query :as query]))

(def ^:dynamic *gold-path* "eval/retrieval-gold.edn")
(def ^:private k 10)

(defn load-gold [path]
  (edn/read-string (slurp path)))

(defn- rank-of
  "1-based rank of the first id in `ids` that is in `expect`, or nil."
  [expect ids]
  (->> ids
       (map-indexed vector)
       (some (fn [[i id]] (when (expect id) (inc i))))))

(defn score-query
  "Run one gold entry through `search-fn`, attaching its rank, reciprocal rank, and
   the top-3 results (for miss diagnosis)."
  [search-fn {:keys [q expect] :as entry}]
  (let [res  (search-fn q {:limit k})
        rank (rank-of (set expect) (map :id res))]
    (assoc entry
           :rank rank
           :rr   (if rank (/ 1.0 rank) 0.0)
           :top  (mapv #(select-keys % [:title :type]) (take 3 res)))))

(defn- recall-at [rows n]
  (/ (double (count (filter #(when-let [r (:rank %)] (<= r n)) rows)))
     (max 1 (count rows))))

(defn- agg [rows]
  {:n   (count rows)
   :r1  (recall-at rows 1)
   :r5  (recall-at rows 5)
   :r10 (recall-at rows 10)
   :mrr (/ (reduce + (map :rr rows)) (max 1 (count rows)))})

(defn report
  "Score the gold set. Pure given a search-fn + gold, so it is unit-testable with a
   stub; the CLI passes the live index."
  ([] (report query/search (load-gold *gold-path*)))
  ([search-fn gold]
   (let [rows (mapv #(score-query search-fn %) gold)]
     {:mode    (if (embed/config) :hybrid :fts)
      :overall (agg rows)
      :by-lang (into (sorted-map)
                     (map (fn [[l rs]] [l (agg rs)]) (group-by :lang rows)))
      :misses  (->> rows (filter #(nil? (:rank %)))
                    (mapv #(select-keys % [:q :lang :top])))
      :rows    rows})))

(defn- pct [x] (format "%.1f%%" (* 100.0 (double x))))

(defn print-report!
  [{:keys [mode overall by-lang misses rows]}]
  (println)
  (println "=== Retrieval eval (layer 4) ===")
  (println (format "mode: %s   queries: %d" (name mode) (:n overall)))
  (println (format "recall@1: %s   recall@5: %s   recall@10: %s   MRR: %.3f"
                   (pct (:r1 overall)) (pct (:r5 overall))
                   (pct (:r10 overall)) (double (:mrr overall))))
  (println)
  (println "by language:")
  (doseq [[l {:keys [n r1 r5 r10 mrr]}] by-lang]
    (println (format "  %-3s  n=%-2d  r@1 %s  r@5 %s  r@10 %s  MRR %.3f"
                     (name l) n (pct r1) (pct r5) (pct r10) (double mrr))))
  (println)
  (println "per query (rank in top-10, · = miss):")
  (doseq [{:keys [q lang rank]} rows]
    (println (format "  %-4s %-5s %s" (if rank (str "#" rank) "·") (name lang) q)))
  (when (seq misses)
    (println)
    (println "misses (top-3 returned):")
    (doseq [{:keys [q top]} misses]
      (println (format "  %s" q))
      (doseq [{:keys [title type]} top]
        (println (format "      [%s] %s" type title)))))
  (println))

(defn scan!
  "Entry point: compute + print, return the data map."
  []
  (doto (report) print-report!))
