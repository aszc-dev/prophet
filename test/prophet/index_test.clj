(ns prophet.index-test
  "Index integration: build the derived index from a note store and query it.
   Runs on the JVM with the vendored sqlite-vec (no embeddings -> inert vector
   lane). Exercises v0 acceptance: rebuild-from-files, FTS search, graph traverse."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [prophet.store.node :as store]
            [prophet.index.schema :as schema]
            [prophet.index.embed :as embed]
            [prophet.index.query :as query]))

(defn- rm-rf [f]
  (doseq [c (reverse (file-seq (io/file f)))] (.delete c)))

(deftest index-roundtrip-and-rebuild
  (let [dir (str (System/getProperty "java.io.tmpdir") "/kb-idx-" (System/nanoTime))
        db  (str dir ".db")]
    (binding [store/*kb-root* dir
              query/*db-path* db
              embed/*disabled* true]               ; deterministic: assert inert lane
      (try
        ;; two linked nodes: experiment -uses-dataset-> dataset (license observation)
        (let [ds  (:node (store/upsert!
                          {:type :dataset :title "style-sft-1.6k" :status :current
                           :aliases ["style-sft-1.6k"] :source_key "r:cards/ds.md"
                           :provenance [{:source :git :ref "git:r@s:cards/ds.md"}]
                           :observations [{:date "" :ref "git:r@s:cards/ds.md"
                                           :text "license: CC-BY-4.0"}]}))
              exp (:node (store/upsert!
                          {:type :experiment :title "smak bez amnezji" :status :current
                           :source_key "r:log#e1"
                           :provenance [{:source :git :ref "git:r@s:log#L1"}]
                           :links {:uses-dataset [(:id ds)]}
                           :observations [{:date "2026-06-17" :ref "git:r@s:log#L1"
                                           :text "result: LLMzSzL 65.0"}]}))
              r1  (schema/rebuild! db)]
          (is (= 2 (:nodes r1)))
          (is (= :fts+graph (:mode r1)) "inert vector lane without an endpoint")

          ;; FTS finds the experiment by an observation term
          (is (some #(= (:id exp) (:id %)) (query/search "amnezji")))
          ;; exact alias finds the dataset
          (is (= (:id ds) (:id (first (query/search "style-sft-1.6k")))))

          ;; multi-hop: experiment -> dataset, license readable from the file
          (is (contains? (set (keys (query/traverse (:id exp) {:depth 2}))) (:id ds)))
          (let [n (query/get-node (:id ds))]
            (is (some #(= "license: CC-BY-4.0" (:text %)) (:observations n))
                "observation round-trips through file even with empty date"))

          ;; rebuild is reproducible from files alone
          (is (= (:nodes r1) (:nodes (schema/rebuild! db)))))
        (finally (rm-rf dir) (.delete (io/file db)))))))

;; --- RRF fusion (pure) -----------------------------------------------------

(deftest rrf-fuses-by-rank-not-raw-score
  (let [rrf #'query/rrf]
    ;; a doc present in two lanes outranks a doc present in one, and a doc absent
    ;; from a lane contributes nothing there (rank-based, scale-invariant).
    (let [fused (rrf [{:w 1.0 :ids ["a" "b"]}
                      {:w 1.0 :ids ["b" "c"]}])]
      (is (> (fused "b") (fused "a")) "shared doc beats single-lane doc")
      (is (> (fused "b") (fused "c")))
      (is (= 3 (count fused)) "union spans all lanes")
      (is (nil? (fused "z")) "doc in no lane is absent"))
    ;; lane weight scales a lane's contribution; a higher-weighted single hit can
    ;; outrank a lower-weighted one at the same rank.
    (let [fused (rrf [{:w 3.0 :ids ["x"]} {:w 1.0 :ids ["y"]}])]
      (is (> (fused "x") (fused "y"))))))

;; --- candidate union + title lane (integration, inert vector lane) ---------

(deftest search-unions-lanes-and-weights-title
  (let [dir (str (System/getProperty "java.io.tmpdir") "/kb-union-" (System/nanoTime))
        db  (str dir ".db")]
    (binding [store/*kb-root* dir
              query/*db-path* db
              embed/*disabled* true]
      (try
        ;; A's TITLE carries the distinctive term; B only mentions it in the body
        ;; (repeatedly, so bm25 over all columns would favour B). The title lane
        ;; must lift A above B.
        (let [a (:node (store/upsert!
                        {:type :dataset :title "Zeta Baseline Matrix" :status :current
                         :aliases ["zeta-matrix"] :source_key "r:cards/a.md"
                         :provenance [{:source :git :ref "git:r@s:cards/a.md"}]
                         :observations [{:date "" :ref "git:r@s:cards/a.md"
                                         :text "an evaluation grid"}]}))
              b (:node (store/upsert!
                        {:type :page :title "General Notes" :status :current
                         :source_key "r:pages/b.md"
                         :provenance [{:source :git :ref "git:r@s:pages/b.md"}]
                         :observations [{:date "" :ref "git:r@s:pages/b.md"
                                         :text "zeta zeta zeta matrix matrix matrix"}]}))]
          (schema/rebuild! db)
          ;; title-token query: A (term in title) outranks B (term only in body)
          (let [res (query/search "Zeta Matrix")
                ids (mapv :id res)]
            (is (contains? (set ids) (:id a)))
            (is (contains? (set ids) (:id b)) "union still includes the body-only match")
            (is (< (.indexOf ids (:id a)) (.indexOf ids (:id b)))
                "title lane ranks the title match above the body-only match"))
          ;; exact alias still surfaces and leads
          (is (= (:id a) (:id (first (query/search "zeta-matrix"))))))
        (finally (rm-rf dir) (.delete (io/file db)))))))
