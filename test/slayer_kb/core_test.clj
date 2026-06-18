(ns slayer-kb.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [slayer-kb.adapters.repo :as repo]
            [slayer-kb.extract.log :as log]
            [slayer-kb.extract.page :as page]
            [slayer-kb.extract.card :as card]
            [slayer-kb.extract.config :as config]
            [slayer-kb.resolve.link :as resolve]
            [slayer-kb.store.node :as store]))

(deftest glob-classification
  (let [r repo/default-kind-rules]
    (is (= :log    (repo/classify r "content/eksperymenty/eksperymenty.jsonl")))
    (is (= :page   (repo/classify r "content/datasety/style.md")))
    (is (= :card   (repo/classify r "cards/style-sft-1.6k.md")))
    (is (= :config (repo/classify r "configs/style27b.yaml")))
    (is (= :data   (repo/classify r "weights/model.safetensors")))
    (is (nil?      (repo/classify r "README.md")))
    (testing "* does not cross a slash"
      (is (nil? (repo/classify [["content/*.md" :page]] "content/sub/x.md"))))))

(deftest log-extract-shape
  (let [item {:kind :log
              :ref  "git:recipes@abc123:content/eksperymenty/log.jsonl"
              :meta {:repo "recipes" :path "content/eksperymenty/log.jsonl"}
              :body (str "{\"id\":\"e1\",\"title\":\"exp one\",\"dataset\":\"ds-a\","
                         "\"result\":\"score 9\",\"decision\":\"ship\"}\n\n")}
        [n :as nodes] (log/extract item)]
    (is (= 1 (count nodes)) "blank lines skipped")
    (is (= :experiment (:type n)))
    (is (= "recipes:content/eksperymenty/log.jsonl#e1" (:source_key n))
        "source_key is sha-free for stable re-ingest")
    (is (= ["ds-a"] (get-in n [:link_hints :uses-dataset])))
    (is (every? #(re-find #"#L1$" (:ref %)) (:observations n))
        "observations carry a line-anchored ref")))

(deftest card-extract-seeds-alias
  (let [[n] (card/extract
             {:kind :card :ref "git:r@s:cards/datasety/ds.md"
              :meta {:repo "r" :path "cards/datasety/ds.md"}
              :body "---\ntype: dataset\nname: style-sft-1.6k\nid: ssk\nlicense: CC-BY-4.0\n---\nprose"})]
    (is (= :dataset (:type n)))
    (is (= "style-sft-1.6k" (:title n)))
    (is (contains? (set (:aliases n)) "style-sft-1.6k") "card seeds canonical alias")
    (is (some #(re-find #"license: CC-BY-4.0" (:text %)) (:observations n)))))

(deftest page-section-mapping
  (let [[n] (page/extract
             {:kind :page :ref "git:r@s:content/glosariusz/llmzszl.md"
              :meta {:repo "r" :path "content/glosariusz/llmzszl.md"}
              :body "---\ntitle: LLMzSzL\n---\nintro text\n## Definicja\nfoo\n## Uzycie\nbar"})]
    (is (= :concept (:type n)) "glosariusz -> concept")
    (is (= "LLMzSzL" (:title n)))
    (is (= 2 (count (:observations n))) "H2 sections -> observations")
    (is (every? #(re-find #"#" (:ref %)) (:observations n)) "section refs carry an anchor")))

(deftest config-recipe-and-toml-skip
  (let [[n] (config/extract
             {:kind :config :title "style27b.yaml" :ref "git:r@s:configs/style27b.yaml"
              :meta {:repo "r" :path "configs/style27b.yaml"}
              :body "name: style27b\nmethod: dpo\nlr: 0.000005\ndataset: style-sft-1.6k"})]
    (is (= :recipe (:type n)))
    (is (= ["style-sft-1.6k"] (get-in n [:link_hints :uses-dataset])))
    (is (some #(re-find #"method: dpo" (:text %)) (:observations n))))
  (is (empty? (config/extract {:kind :config :title "x.toml"
                               :meta {:path "configs/x.toml"} :body "a=1"}))
      "TOML deferred"))

(deftest md-roundtrip
  (let [node {:id "01TESTID0000000000000000XY" :type :experiment
              :title "exp one" :status :current :moc ["m"]
              :provenance [{:source :git :ref "git:r@s:p#L1"}]
              :observations [{:date "2026-06-18" :ref "git:r@s:p#L1" :text "result: 9"}]}
        back (store/md->node (store/node->md node))]
    (is (= "01TESTID0000000000000000XY" (:id back)))
    (is (= "exp one" (:title back)))
    (is (= 1 (count (:observations back))))
    (is (= "result: 9" (:text (first (:observations back)))))))

(deftest resolver-structural-links
  (let [card {:type :dataset :title "style-sft-1.6k" :aliases ["style-sft-1.6k"]
              :source_key "r:cards/ds.md" :links {} :link_hints {}}
        exp  {:type :experiment :title "exp" :source_key "r:log#e1" :links {}
              :link_hints {:uses-dataset ["style-sft-1.6k"] :uses-recipe ["ghost"]}}
        [c e] (resolve/prepare [card exp] [])]
    (is (string? (:id c)))
    (is (= [(:id c)] (get-in e [:links :uses-dataset])) "hint resolves to card id")
    (is (= [{:rel :uses-recipe :hint "ghost"}] (:unresolved e))
        "unresolved hint kept for visibility, not linked")
    (is (nil? (:link_hints e)) "hints consumed")))

(deftest resolver-reuses-existing-id
  (let [existing [{:id "01EXISTING0000000000000000" :source_key "r:log#e1"}]
        [n] (resolve/prepare [{:type :experiment :title "exp"
                               :source_key "r:log#e1" :links {} :link_hints {}}]
                             existing)]
    (is (= "01EXISTING0000000000000000" (:id n)) "id reused via source_key")))

(deftest deterministic-id-reuse
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/kb-test-" (System/nanoTime))]
    (binding [store/*kb-root* tmp]
      (try
        (let [base {:type :experiment :title "exp" :status :current
                    :source_key "recipes:log#e1"
                    :provenance [{:source :git :ref "git:r@s1:log#L1"}]
                    :observations []}
              r1 (store/upsert! base)
              ;; same source_key, new sha in provenance -> reuse id, content changes
              r2 (store/upsert! (assoc base :provenance
                                       [{:source :git :ref "git:r@s2:log#L1"}]
                                       :tags ["x"]))]
          (is (= :created (:status r1)))
          (is (= (get-in r1 [:node :id]) (get-in r2 [:node :id]))
              "ULID reused across re-ingest via source_key")
          (is (= :unchanged (:status (store/upsert! (:node r2))))
              "re-writing identical payload is a no-op"))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))] (.delete f)))))))
