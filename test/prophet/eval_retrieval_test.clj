(ns prophet.eval-retrieval-test
  (:require [clojure.test :refer [deftest is]]
            [prophet.eval.retrieval :as ret]))

;; A stub index: query -> ranked id list. Lets us assert rank/recall/MRR math
;; without a db or embeddings.
(defn- stub [results-by-q]
  (fn [q _opts]
    (map (fn [id] {:id id :title id :type "x"}) (get results-by-q q []))))

(def ^:private gold
  [{:q "hit-first"  :lang :en :expect #{"A"}}        ; rank 1
   {:q "hit-third"  :lang :pl :expect #{"C"}}        ; rank 3
   {:q "miss"       :lang :en :expect #{"Z"}}        ; not returned
   {:q "any-of-set" :lang :pl :expect #{"P" "Q"}}])  ; first of either, rank 2

(def ^:private search
  (stub {"hit-first"  ["A" "B" "C"]
         "hit-third"  ["X" "Y" "C"]
         "miss"       ["M" "N"]
         "any-of-set" ["R" "Q" "P"]}))

(deftest rank-and-rr-per-query
  (let [{:keys [rows]} (ret/report search gold)
        by-q (into {} (map (juxt :q identity)) rows)]
    (is (= 1 (:rank (by-q "hit-first"))))
    (is (= 3 (:rank (by-q "hit-third"))))
    (is (nil? (:rank (by-q "miss"))) "no expected id in results -> miss")
    (is (= 2 (:rank (by-q "any-of-set"))) "first id from the expect SET counts")
    (is (== 0.0 (:rr (by-q "miss"))))
    (is (== (/ 1.0 3) (:rr (by-q "hit-third"))))))

(deftest aggregate-recall-and-mrr
  (let [{:keys [overall by-lang misses]} (ret/report search gold)]
    (is (= 4 (:n overall)))
    (is (== 0.25 (:r1 overall)) "1/4 at rank 1")
    (is (== 0.75 (:r5 overall)) "ranks 1,3,2 within 5 -> 3/4")
    (is (== 0.75 (:r10 overall)))
    ;; MRR = (1 + 1/3 + 0 + 1/2) / 4
    (is (< (Math/abs (- (double (:mrr overall)) (/ (+ 1.0 (/ 1.0 3) 0.0 0.5) 4))) 1e-9))
    (is (= 1 (count misses)) "only the genuine miss is reported")
    (is (= "miss" (:q (first misses))))
    (is (== 0.5 (:r1 (:en by-lang))) "en = {hit-first r1, miss} -> 1/2 at rank 1")
    (is (= 2 (:n (:pl by-lang))))))

(defn- rep [mode r1 r5 r10 mrr en-r10 pl-r10]
  {:mode mode
   :overall {:n 10 :r1 r1 :r5 r5 :r10 r10 :mrr mrr}
   :by-lang (sorted-map :en {:n 5 :r1 0.0 :r5 0.0 :r10 en-r10 :mrr 0.0}
                        :pl {:n 5 :r1 0.0 :r5 0.0 :r10 pl-r10 :mrr 0.0})
   :misses [] :rows []})

(deftest gate-enforces-mode-floors
  ;; measured milestone-#8 scorecard clears the hybrid floors
  (is (:pass (ret/gate! (rep :hybrid 0.50 0.80 0.95 0.643 0.90 1.00))))
  ;; a single PL query dropping below the hard 100% floor fails the gate
  (is (not (:pass (ret/gate! (rep :hybrid 0.50 0.80 0.95 0.643 0.90 0.90))))
      "PL r@10 non-regression is a hard floor")
  ;; an EN recall collapse fails even with everything else healthy
  (is (not (:pass (ret/gate! (rep :hybrid 0.50 0.80 0.95 0.643 0.50 1.00)))))
  ;; inert vector lane is judged against the lower :fts floors
  (is (:pass (ret/gate! (rep :fts 0.30 0.70 0.70 0.468 0.50 0.90)))))
