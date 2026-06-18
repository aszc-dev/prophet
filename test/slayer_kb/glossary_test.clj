(ns slayer-kb.glossary-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [slayer-kb.glossary :as glossary]
            [slayer-kb.index.chat :as chat]
            [slayer-kb.store.node :as store]))

(defn- with-temp-kb [f]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/kb-gloss-" (System/nanoTime))]
    (binding [store/*kb-root* tmp]
      (try (f) (finally (doseq [x (reverse (file-seq (io/file tmp)))] (.delete x)))))))

;; Two nodes both tagged "cke", whose State prose actually explains the term.
(def ^:private using-a
  {:type :dataset :title "Egzaminy CKE" :status :current :source_key "r:ds#a"
   :tags ["cke" "eval"] :provenance [{:source :git :ref "git:r@s:a#cke"}]
   :state "CKE to Centralna Komisja Egzaminacyjna; jej egzaminy są źródłem zadań."
   :observations [{:date "" :ref "git:r@s:a#cke" :text "kategoria: eval"}]})

(def ^:private using-b
  {:type :benchmark :title "Bench CKE" :status :current :source_key "r:bn#b"
   :tags ["cke" "mcq"] :provenance [{:source :git :ref "git:r@s:b#cke"}]
   :state "Zadania CKE wykorzystywane jako benchmark." :observations []})

(defn- seed-and-build! []
  (store/upsert! using-a)
  (store/upsert! using-b)
  (glossary/build!))

(defn- concept-node []
  (->> (store/all-notes) (map :node)
       (some #(when (= "cke" (:title %)) %))))

(deftest passages-only-from-mentioning-units
  ;; the State prose mentions the term -> passage; the "kategoria: eval"
  ;; observation does not -> excluded. No fabrication from unrelated text.
  (let [users [(assoc using-a :body (#'store/render-body using-a))
               (assoc using-b :body (#'store/render-body using-b))]
        ps    (glossary/passages-for "cke" users)]
    (is (= 2 (count ps)) "two State passages, the off-topic observation dropped")
    (is (every? :ref ps) "each passage carries its source ref")
    (is (= #{"git:r@s:a#cke" "git:r@s:b#cke"} (set (map :ref ps))))
    (is (every? #(re-find #"(?i)cke" (:text %)) ps))))

(deftest define-inert-without-endpoint
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (binding [chat/*disabled* true]
        (let [r (glossary/define!)]
          (is (:inert r) "no chat endpoint -> inert, no generation")
          (is (nil? (:definition (concept-node))) "concept left undefined")
          (is (= :draft (keyword (:status (concept-node)))) "stays draft"))))))

(deftest define-grounded-then-idempotent
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (let [calls (atom 0)]
        (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})
                      chat/complete-json
                      (fn [_] (swap! calls inc)
                        {:definition "CKE to Centralna Komisja Egzaminacyjna."
                         :grounded true :refs ["git:r@s:a#cke"]})]
          (let [r1 (glossary/define!)
                c1 (concept-node)]
            (is (= 1 (:defined r1)) "groundable concept defined")
            (is (= 1 @calls) "model called once")
            (is (= "CKE to Centralna Komisja Egzaminacyjna." (:definition c1)))
            (is (= :draft (keyword (:status c1))) "human still verifies -> draft")
            (testing "generate-once: re-run is a verified no-op, no regeneration"
              (let [r2 (glossary/define!)
                    c2 (concept-node)]
                (is (= 1 (:skipped r2)) "already defined -> skipped")
                (is (= 1 @calls) "model NOT called again")
                (is (= (:content_hash c1) (:content_hash c2)) "no content-hash churn")))))))))

(deftest define-rejects-ungrounded-and-hallucinated
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})]
        (testing "grounded:false -> no definition (no fabrication)"
          (with-redefs [chat/complete-json
                        (fn [_] {:definition "" :grounded false :refs []})]
            (is (= 1 (:ungrounded (glossary/define!))))
            (is (nil? (:definition (concept-node))))))
        (testing "cited ref not among supplied -> rejected as hallucinated provenance"
          (with-redefs [chat/complete-json
                        (fn [_] {:definition "Made up." :grounded true
                                 :refs ["git:r@s:NOPE#x"]})]
            (is (= 1 (:ungrounded (glossary/define!))))
            (is (nil? (:definition (concept-node))))))))))

(deftest build-preserves-definition-no-churn
  ;; glossary:build does not set :definition; re-running it after define! must NOT
  ;; drop the definition on disk (append-only) -> the concept stays :unchanged.
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})
                    chat/complete-json
                    (fn [_] {:definition "CKE to komisja egzaminacyjna."
                             :grounded true :refs ["git:r@s:a#cke"]})]
        (glossary/define!))
      (let [before (concept-node)
            tally  (glossary/build!)
            after  (concept-node)]
        (is (= "CKE to komisja egzaminacyjna." (:definition after)) "definition preserved")
        (is (= (:content_hash before) (:content_hash after)) "no hash churn")
        (is (pos? (:unchanged tally)) "re-build is a no-op for the defined concept")))))
