(ns prophet.mcp.telemetry-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.index.embed :as embed]
            [prophet.mcp.telemetry :as tel]))

(defn- tmp-file []
  (doto (java.io.File/createTempFile "prophet-tel-" ".jsonl") (.delete)))

(defn- lines [f] (when (.exists f) (remove str/blank? (str/split-lines (slurp f)))))

(deftest result-ids-are-defensive
  (testing "per-tool id extraction"
    (is (= ["a" "b"] (tel/result->ids "search" [{:id "a"} {:id "b"}])))
    (is (= ["x"] (tel/result->ids "get_node" {:id "x" :title "t"})))
    (is (= [] (tel/result->ids "get_node" {})))
    (is (= #{"a" "b"} (set (tel/result->ids "traverse" {"a" 1 "b" 2}))))
    (is (= #{"a" "b"} (set (tel/result->ids "neighbors"
                                            {:out [{:rel "r" :id "a"}] :in [{:rel "q" :id "b"}]}))))
    (is (= ["n"] (tel/result->ids "whats_new" [{:id "n"}])))
    (is (= [] (tel/result->ids "frobnicate" [{:id "a"}]))))
  (testing "never throws on shape surprise"
    (is (= [] (tel/result->ids "search" "not-a-seq")))
    (is (= [] (tel/result->ids "neighbors" nil)))))

(deftest top-score-and-mode
  (is (= 0.8 (tel/top-score "search" [{:score 0.8}])))
  (is (nil? (tel/top-score "search" [])))
  (is (nil? (tel/top-score "get_node" {:id "x"})))
  (testing "mode reflects embedder config, search-only"
    (binding [embed/*disabled* true]
      (is (= "fts" (tel/mode "search"))))
    (with-redefs [embed/config (constantly {:url "http://tei:80"})]
      (is (= "hybrid" (tel/mode "search"))))
    (is (nil? (tel/mode "get_node")))))

(deftest inert-when-unconfigured
  (testing "no sink path => emit! is a no-op returning nil (G1)"
    (binding [tel/*sink-path-override* nil]
      (is (nil? (tel/sink-path)))
      (is (nil? (tel/emit! {:tool "search" :args {:query "x"}
                            :out [{:id "a"}] :latency-ms 1}))))))

(deftest writes-one-record-when-configured
  (let [f (tmp-file)]
    (binding [tel/*sink-path-override* (.getPath f)
              tel/*session-id* "sess-1"
              tel/*transport* "stdio"
              embed/*disabled* true]
      (tel/emit! {:tool "search" :args {:query "benchmark"}
                  :out [{:id "01ABC" :score 0.7}] :latency-ms 12}))
    (let [ls (lines f)
          rec (json/read-str (first ls) :key-fn keyword)]
      (is (= 1 (count ls)))
      (is (= "search" (:tool rec)))
      (is (= ["01ABC"] (:result_ids rec)))
      (is (= 1 (:result_count rec)))
      (is (= "fts" (:mode rec)))
      (is (= "sess-1" (:session_id rec)))
      (is (= "stdio" (:transport rec)))
      (is (>= (:latency_ms rec) 0))
      (is (false? (:is_error rec)))
      (.delete f))))

(deftest concurrent-writes-do-not-interleave
  (testing "50 concurrent emits => exactly 50 individually parseable lines (G3)"
    (let [f (tmp-file)]
      (binding [tel/*sink-path-override* (.getPath f)
                tel/*session-id* "sess-c"]
        (->> (range 50)
             (mapv (fn [i] (future (tel/emit! {:tool "get_node" :args {:id (str i)}
                                               :out {:id (str "node-" i)} :latency-ms i}))))
             (run! deref)))
      (let [ls (lines f)]
        (is (= 50 (count ls)))
        (is (every? #(map? (json/read-str % :key-fn keyword)) ls) "each line parses"))
      (.delete f))))
