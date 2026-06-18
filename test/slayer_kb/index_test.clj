(ns slayer-kb.index-test
  "Index integration: build the derived index from a note store and query it.
   Runs on the JVM with the vendored sqlite-vec (no embeddings -> inert vector
   lane). Exercises v0 acceptance: rebuild-from-files, FTS search, graph traverse."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [slayer-kb.store.node :as store]
            [slayer-kb.index.schema :as schema]
            [slayer-kb.index.embed :as embed]
            [slayer-kb.index.query :as query]))

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
