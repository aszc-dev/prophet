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
