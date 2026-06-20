(ns prophet.embed-test
  (:require [clojure.test :refer [deftest is testing]]
            [prophet.index.embed :as embed]))

(deftest request-body-omits-dimensions-at-native-width
  (testing "native width must NOT send :dimensions (some servers, incl. TEI, 400 on it)"
    (let [b (embed/request-body "Qwen/Qwen3-Embedding-0.6B" ["a" "b"])]
      (is (= "Qwen/Qwen3-Embedding-0.6B" (:model b)))
      (is (= ["a" "b"] (:input b)))
      (is (not (contains? b :dimensions))))))

(deftest request-body-includes-dimensions-only-for-mrl-truncation
  (testing "a smaller target dim is genuine MRL truncation -> send :dimensions"
    (with-redefs [embed/dim 512]
      (is (= 512 (:dimensions (embed/request-body "m" ["a"])))))))

(deftest default-model-is-the-tei-model
  (is (= "Qwen/Qwen3-Embedding-0.6B" embed/default-model)
      "default embedder must match the TEI deployment model (ADR-010)"))

(deftest embed-batch-chunks-to-max-batch
  (testing "requests are split into <= *max-batch* inputs, order preserved"
    (let [calls (atom [])]
      (with-redefs [embed/config (constantly {:url "http://tei:80" :model "m" :api-key nil})
                    embed/post-json (fn [_ _ body]
                                      (let [in (:input body)]
                                        (swap! calls conj (count in))
                                        {:data (map-indexed (fn [i t] {:index i :embedding [t]}) in)}))]
        (binding [embed/*max-batch* 2]
          (let [out (embed/embed-batch ["a" "b" "c" "d" "e"])]
            (is (= [["a"] ["b"] ["c"] ["d"] ["e"]] out) "all vectors, in input order")
            (is (= [2 2 1] @calls) "chunked 2+2+1, never above max-batch")))))))
