(ns prophet.extract.json-test
  "WI-1 / ADR-017: the JSON extractor must materialize the scientific payload, not
   just top-level scalars. The live RED was `experiments.json#v3-trained` served
   with 4 observations (base/method/status/date) — the result (66.8), the decision
   (the `note`), the config and the cost all dropped. These assert the field ->
   kind-tagged observation mapping over a fixture mirroring that record."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.ingest :as ingest]
            [prophet.extract.json :as ejson]))

(def ^:private cfg (ingest/load-config "slayer"))

(defn- spec [path] (get-in cfg [:json-specs path]))

(defn- extract [path]
  (ejson/extract {:body (slurp (io/file "test/resources/slayer-fixture" path))
                  :ref  (str "git:slayer@deadbeef:" path)
                  :meta {:repo "slayer" :path path}}
                 (spec path)))

(defn- by-id [nodes id]
  (first (filter #(str/ends-with? (:source_key %) (str "#" id)) nodes)))

(defn- obs-of [node kind]
  (filter #(= kind (:kind %)) (:observations node)))

(defn- texts [obs] (map :text obs))

(deftest experiment-materializes-result-and-decision
  (let [n (by-id (extract "public/results/experiments.json") "demo-trained")]
    (testing "the result row is a :result observation, verbatim, source order"
      (let [results (texts (obs-of n :result))]
        (is (some #(str/includes? % "66.8") results) "headline acc is present")
        (is (= "demo — DemoBench acc: 66.8" (first results))
            "eval.tasks fan out one per key in source order")))
    (testing "the note is a :decision observation, verbatim (never split)"
      (let [d (texts (obs-of n :decision))]
        (is (= 1 (count d)))
        (is (str/includes? (first d) "+3.3 nad baza"))
        (is (str/includes? (first d) "SFT-first wystarcza") "full text, not first sentence")))
    (testing "config: rolled mix (val desc) and train_cfg (key asc)"
      (let [c (texts (obs-of n :config))]
        (is (some #(= "miks: destylacja 800 · EN retencja 300 · styl 100" %) c))
        (is (some #(= "train_cfg: LR 5e-5 cosine · early-stop patience 2 · epoki 2" %) c))
        (is (some #(= "n_examples: 1200" %) c))))
    (testing "derived final eval-loss from curves"
      (is (some #(= "eval-loss końcowy: 0.4413" %) (texts (obs-of n :result)))))
    (testing "meta keeps harness/cost; State is never set by extraction"
      (is (some #(str/includes? % "harness:") (texts (obs-of n :meta))))
      (is (some #(str/includes? % "H100") (texts (obs-of n :meta))) "log_note retained")
      (is (nil? (:state n)) "no field reaches State from the extractor"))))

(deftest result-less-experiment-is-legitimate
  (let [n (by-id (extract "public/results/experiments.json") "demo-planned")]
    (is (empty? (obs-of n :result)) "eval has only a harness -> no result")
    (is (seq (obs-of n :method)) "method still captured")
    (is (not-any? #(str/includes? % "baza:") (texts (:observations n)))
        "base '—' placeholder is skipped, not emitted")))

(deftest benchmark-definition-and-compound-meta
  (let [n (by-id (extract "public/data/benchmarks.json") "demo-bench")]
    (is (= ["egzaminy demo CKE · 154 domeny"] (texts (obs-of n :definition))))
    (is (some #(= "metryka: accuracy MCQ (accuracy)" %) (texts (obs-of n :meta))))
    (is (some #(str/includes? % "huggingface.co") (texts (obs-of n :link))))
    (is (not-any? #(str/includes? % "repo") (texts (:observations n)))
        "null fields (repo, uwagi_review) emit nothing")))

(deftest matrix-rows-pair-with-cols
  (let [n (by-id (extract "public/results/matrix.json") "porownania-likelihood-demo")]
    (is (= "DemoBench (egzaminy): Qwen-Demo-7B (baza) 58.5 · demo v1 65.0 · demo v3 —"
           (first (texts (obs-of n :result))))
        "row vals pair with cols; null -> placeholder")
    (is (seq (obs-of n :decision)) "section note is a decision")
    (is (some #(str/includes? % "protokół:") (texts (obs-of n :meta))))))
