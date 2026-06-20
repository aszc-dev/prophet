(ns prophet.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [prophet.adapters.repo :as repo]
            [prophet.extract.log :as log]
            [prophet.extract.page :as page]
            [prophet.extract.card :as card]
            [prophet.extract.config :as config]
            [prophet.extract.json :as ejson]
            [prophet.resolve.link :as resolve]
            [prophet.glossary :as glossary]
            [prophet.extract.common :as common]
            [prophet.util :as util]
            [prophet.store.node :as store]))

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

(deftest json-spec-extract-maps-foreign-fields
  ;; mirrors slayer's datasety.json: a list of records with Polish field names
  (let [spec {:node-type :dataset :iter :list :id :id :title :nazwa :state :opis
              :tags :tagi :moc ["dane"] :aliases [:id :nazwa]
              :observations [:rozmiar :licencja]}
        item {:kind :json :ref "git:slayer@s:public/data/datasety.json"
              :meta {:repo "slayer" :path "public/data/datasety.json"}
              :body (str "[{\"id\":\"llmzszl\",\"nazwa\":\"LLMzSzŁ\",\"opis\":\"egzaminy CKE\","
                         "\"rozmiar\":\"18 821\",\"licencja\":\"publiczny\",\"tagi\":[\"eval\",\"pl\"]}]")}
        [n] (ejson/extract item spec)]
    (is (= :dataset (:type n)))
    (is (= "LLMzSzŁ" (:title n)) "title mapped from :nazwa")
    (is (= "egzaminy CKE" (:state n)) "state mapped from :opis")
    (is (= #{"llmzszl" "LLMzSzŁ"} (set (:aliases n))) "aliases seed canonical names")
    (is (= "slayer:public/data/datasety.json#llmzszl" (:source_key n)) "sha-free, id-anchored key")
    (is (every? #(re-find #"#llmzszl$" (:ref %)) (:observations n)) "per-record provenance anchor")
    (is (some #(= "rozmiar: 18 821" (:text %)) (:observations n)))
    (is (= ["eval" "pl"] (:tags n))))
  (is (empty? (ejson/extract {:kind :json :meta {:path "x.json"} :body "[]"} nil))
      "no spec -> skipped"))

(deftest glossary-candidates-from-recurring-tags
  (let [nodes [{:id "01A" :type :dataset :title "DS-A" :tags ["eval" "pl"]
                :provenance [{:source :git :ref "git:r@s:a"}]}
               {:id "01B" :type :benchmark :title "B-B" :tags ["eval" "mcq"]
                :provenance [{:source :git :ref "git:r@s:b"}]}
               {:id "01C" :type :experiment :title "E-C" :tags ["eval"]
                :provenance [{:source :git :ref "git:r@s:c"}]}]
        cs (glossary/candidates nodes)
        by-title (into {} (map (juxt :title identity) cs))]
    (is (contains? by-title "eval") "tag on >=2 nodes -> concept")
    (is (not (contains? by-title "pl"))  "2-char term filtered")
    (is (not (contains? by-title "mcq")) "tag on only 1 node skipped")
    (let [eval (by-title "eval")]
      (is (= :concept (:type eval)))
      (is (= :draft (:status eval)) "definition pending")
      (is (= ["01A" "01B" "01C"] (get-in eval [:links :mentions])) "used-in links")
      (is (seq (:provenance eval)) "grounded in source refs of using nodes")
      (is (= "glossary:tag:eval" (:source_key eval)) "stable key -> idempotent rebuild"))))

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

(defn- with-temp-kb [f]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/kb-test-" (System/nanoTime))]
    (binding [store/*kb-root* tmp]
      (try (f) (finally (doseq [x (reverse (file-seq (io/file tmp)))] (.delete x)))))))

(deftest upsert-idempotent-across-reparse
  ;; regression: clj-yaml parses a stored node back as LazySeq/OrderedMap with
  ;; string scalars (:git -> "git"); the canonical content-hash must treat that as
  ;; identical to the fresh keyword/vector form, so re-upsert is a no-op.
  (with-temp-kb
    (fn []
      (let [node {:type :benchmark :title "LLMzSzŁ" :status :current
                  :source_key "r:b#llmzszl" :aliases ["llmzszl" "LLMzSzŁ"]
                  :provenance [{:source :git :ref "git:r@s:b#llmzszl"}]
                  :observations [{:date "2026-06-04" :ref "git:r@s:b#llmzszl" :text "Q9: 58.2"}]}
            r1   (store/upsert! node)
            back (store/md->node (slurp (:file r1)))]
        (is (= :created (:status r1)))
        (is (= :unchanged (:status (store/upsert! back)))
            "re-upsert of the reparsed node must not churn")))))

(deftest upsert-converges-in-one-pass-without-date
  ;; regression (the two-pass wart): fresh extraction emits :date "" while a disk
  ;; round-trip parses an absent date back as nil. canonical collapses "" -> nil so
  ;; the very first re-upsert is already a no-op (was: churned once before settling).
  (with-temp-kb
    (fn []
      (let [node {:type :source :title "Doc" :status :current :source_key "r:d#x"
                  :provenance [{:source :git :ref "git:r@s:d"}]
                  :observations [{:date "" :ref "git:r@s:d#s" :text "section body"}]}
            r1   (store/upsert! node)
            back (store/md->node (slurp (:file r1)))]
        (is (= :created (:status r1)))
        (is (nil? (:date (first (:observations back)))) "absent date parses back as nil")
        (is (= :unchanged (:status (store/upsert! back)))
            "no-date node converges in ONE re-upsert, no \"\"/nil hash flip")))))

(deftest upsert-merges-observations-append-only
  ;; invariant #3: a re-extract from one source must not drop observations another
  ;; source appended to the same node.
  (with-temp-kb
    (fn []
      (let [base {:type :benchmark :title "B" :status :current :source_key "r:b#x"
                  :provenance [{:source :git :ref "git:r@s:b#x"}]}
            _ (store/upsert! (assoc base :observations
                                    [{:date "" :ref "git:r@s:card#x" :text "license: MIT"}]))
            r (store/upsert! (assoc base :observations
                                    [{:date "2026-06-04" :ref "git:r@s:lb#x" :text "Q9: 58.2"}]))
            n (store/md->node (slurp (:file r)))]
        (is (= :updated (:status r)))
        (is (= #{"license: MIT" "Q9: 58.2"} (set (map :text (:observations n))))
            "card observation preserved, leaderboard observation appended")))))

(deftest flatten-body-captures-blocks
  (testing "table -> cells, no leading pipe (was :stub-table)"
    (let [t (common/flatten-body "| pole | znaczenie |\n|---|---|\n| id | klucz |")]
      (is (not (string/starts-with? t "|")) "no leading pipe")
      (is (re-find #"pole \| znaczenie" t))
      (is (re-find #"id \| klucz" t) "data rows kept")
      (is (not (re-find #"---" t)) "separator row dropped")))
  (testing "fenced code -> inner lines, no bare fence (was :stub-fence)"
    (let [t (common/flatten-body "```bash\npip install -e .\n```")]
      (is (= "pip install -e ." t))))
  (testing "colon-introduced block -> body no longer ends in ':' (was :dangling)"
    (let [t (common/flatten-body "Required fields:\n- id\n- surface")]
      (is (not (clojure.string/ends-with? t ":")))
      (is (re-find #"id" t))))
  (testing "empty in -> empty out"
    (is (= "" (common/flatten-body "")))
    (is (= "" (common/flatten-body nil)))))

(deftest stable-ulid-deterministic
  (let [k "slayer:DATASET_MANIFEST.md#schema"
        a (util/stable-ulid k)]
    (is (= a (util/stable-ulid k)) "same source_key -> same id")
    (is (= 26 (count a)) "ULID-shaped: 26 chars")
    (is (re-matches #"[0-9A-HJKMNP-TV-Z]{26}" a) "Crockford base32")
    (is (not= a (util/stable-ulid (str k "x"))) "different key -> different id")))

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
