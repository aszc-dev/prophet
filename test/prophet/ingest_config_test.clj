(ns prophet.ingest-config-test
  "P0-2: the deploy bug was ingesting slayer with the Hugo default kind-rules
   instead of the slayer config, yielding ~0 nodes. These encode the root cause:
   slayer's JSON lives under public/data/, which only the slayer rules classify."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.adapters.repo :as repo]
            [prophet.ingest :as ingest]
            [prophet.index.embed :as embed]
            [prophet.store.node :as store]))

(def ^:private fixture "test/resources/slayer-fixture")
(def ^:private slayer-rules (:kind-rules (ingest/load-config "slayer")))

(deftest classify-public-data-json-needs-slayer-rules
  (testing "slayer rules classify public/data/*.json as :json"
    (is (= :json (repo/classify slayer-rules "public/data/datasety.json"))))
  (testing "the Hugo defaults do NOT — this is exactly why the default ingest was empty"
    (is (nil? (repo/classify repo/default-kind-rules "public/data/datasety.json")))))

(deftest load-config-is-explicit-never-derived
  (testing "a known source resolves to its config"
    (is (= "slayer" (:shortname (ingest/load-config "slayer")))))
  (testing "an unknown name throws rather than falling back to defaults (ADR-018)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown source config"
                          (ingest/load-config "no-such-source")))))

(deftest ingest-needs-the-right-config
  (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "prophet-icfg-" (System/nanoTime))))]
    (binding [embed/*disabled* true]
      (testing "with the slayer config the JSON datasets become typed nodes"
        (binding [store/*kb-root* (str tmp "/with")]
          (let [res   (ingest/ingest-repo! fixture "slayer")
                types (set (map #(str (:type (:node %))) (store/all-notes)))]
            (is (pos? (:nodes res)))
            (is (contains? types "dataset") "datasety.json -> :dataset nodes"))))
      (testing "an unknown config fails the ingest loudly — no silent near-empty
                corpus from a derived/wrong name (ADR-018)"
        (binding [store/*kb-root* (str tmp "/unknown")]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown source config"
                                (ingest/ingest-repo! fixture "slayer-fixture"))))))))

(deftest source-key-prefix-comes-from-the-config-not-the-dir
  (testing "ingesting a dir named 'slayer-fixture' under config 'slayer' keys on
            'slayer:' — so source_keys and provenance refs are stable regardless
            of the clone directory name (the gold-set/Gate-B trap)"
    (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "prophet-sk-" (System/nanoTime))))]
      (binding [embed/*disabled* true
                store/*kb-root* (str tmp "/sk")]
        (ingest/ingest-repo! fixture "slayer")
        (let [keys (keep (comp :source_key :node) (store/all-notes))]
          (is (seq keys))
          (is (some #(str/starts-with? % "slayer:") keys)
              "source_keys use the config shortname")
          (is (not-any? #(str/starts-with? % "slayer-fixture:") keys)
              "the clone-dir basename must not leak into source_keys"))))))
