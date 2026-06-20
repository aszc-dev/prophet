(ns prophet.mcp.telemetry.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [prophet.mcp.telemetry.store :as store]))

(defn- write-jsonl! [recs]
  (let [f (doto (java.io.File/createTempFile "tel-g4-" ".jsonl") (.deleteOnExit))]
    (spit f (str/join "\n" (map json/write-str recs)))
    (.getPath f)))

(defn- tmp-db [] (.getPath (doto (java.io.File/createTempFile "tel-g4-" ".db") (.delete) (.deleteOnExit))))

(defn- search [sess q ids top]
  {:tool "search" :session_id sess :args {:query q}
   :result_ids ids :result_count (count ids) :top_score top :ts "2026-06-21T00:00:00Z"})

(defn- get-node [sess id]
  {:tool "get_node" :session_id sess :args {:id id}
   :result_ids [id] :result_count 1 :ts "2026-06-21T00:00:01Z"})

(def ^:private all-hits
  [(search "s1" "good one" ["a"] 0.5) (get-node "s1" "a")
   (search "s2" "good two" ["b"] 0.5) (get-node "s2" "b")])

(deftest gaps-surfaces-exactly-the-zero-result-query
  (testing "G4: a zero-result query is the only gap among otherwise-satisfied traffic"
    (binding [store/*db-path* (tmp-db)]
      (store/rebuild! (write-jsonl! (conj all-hits (search "s3" "untouched topic" [] nil))))
      (let [gs (store/gaps {:floor nil})]
        (is (= 1 (count gs)))
        (is (= "untouched topic" (:q (first gs))))
        (is (= :zero-result (:reason (first gs))))))))

(deftest gaps-empty-on-all-hits
  (testing "G4: an all-hits store (every search has results and a follow-up) yields no gaps"
    (binding [store/*db-path* (tmp-db)]
      (store/rebuild! (write-jsonl! all-hits))
      (is (empty? (store/gaps {:floor nil}))))))

(deftest gaps-low-score-and-no-followup
  (testing "low-score fires only when a floor is set"
    (binding [store/*db-path* (tmp-db)]
      (store/rebuild! (write-jsonl! [(search "s1" "weak hit" ["a"] 0.01) (get-node "s1" "a")]))
      (is (empty? (store/gaps {:floor nil})) "no floor => not flagged")
      (is (= :low-score (:reason (first (store/gaps {:floor 0.1})))))))
  (testing "no-followup fires when a returned id was never get_node'd in-session"
    (binding [store/*db-path* (tmp-db)]
      (store/rebuild! (write-jsonl! [(search "s1" "ignored results" ["a" "b"] 0.5)]))
      (is (= :no-followup (:reason (first (store/gaps {:floor nil}))))))))
