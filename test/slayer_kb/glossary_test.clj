(ns slayer-kb.glossary-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slayer-kb.glossary :as glossary]
            [slayer-kb.index.chat :as chat]
            [slayer-kb.store.node :as store]))

(defn- with-temp-kb [f]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/kb-gloss-" (System/nanoTime))]
    (binding [store/*kb-root* tmp]
      (try (f) (finally (doseq [x (reverse (file-seq (io/file tmp)))] (.delete x)))))))

;; --- concept sourcing (Part B) ---------------------------------------------

(deftest sources-entity-jargon-not-just-tags
  ;; the real jargon (LLMzSzŁ) is never a tag — it must be sourced from the entity
  ;; node and from prose, and the concept must link to the node that DEFINES it.
  (let [bench {:id "01BENCH" :type :benchmark :title "LLMzSzŁ"
               :aliases ["llmzszl" "LLMzSzŁ"] :state "egzaminy państwowe CKE · 154 domeny"
               :provenance [{:source :git :ref "git:r@s:benchmarks.json#llmzszl"}] :observations []}
        src   {:id "01SRC" :type :source :title "Plan slayer"
               :provenance [{:source :git :ref "git:r@s:plan.md"}]
               :observations [{:ref "git:r@s:plan.md#x" :text "celujemy w jedyną oś Bielika (LLMzSzŁ)"}]}
        by-t  (into {} (map (juxt :title identity)) (glossary/candidates [bench src]))
        c     (by-t "LLMzSzŁ")]
    (is c "entity/jargon term becomes a concept though it is no node's tag")
    (is (str/starts-with? (:source_key c) "glossary:term:") "non-tag term -> term key")
    (is (= "glossary:term:llmzszl" (:source_key c)) "diacritic-folded stable key")
    (is (contains? (set (get-in c [:links :defined-by])) "01BENCH")
        "concept links to the entity that defines it")
    (is (= #{"01BENCH" "01SRC"} (set (get-in c [:links :mentions]))) "both users mentioned")
    (is (= :draft (:status c)))))

(deftest defining-link-from-title-subject
  ;; a source doc whose title is ABOUT the term grounds it (HELDOUT.md pattern).
  (let [doc {:id "01HELD" :type :source :title "Prywatny held-out — świeże arkusze"
             :provenance [{:source :git :ref "git:r@s:bench/HELDOUT.md"}]
             :observations [{:ref "git:r@s:bench/HELDOUT.md#co-to-jest"
                             :text "Co to jest — najnowsze roczniki CKE/OKE spoza publicznych datasetów"}]}
        ds  {:id "01DS" :type :dataset :title "Prywatny held-out"
             :aliases ["prywatny_held_out"] :state "held-out: świeże arkusze, anti-benchmaxxing"
             :provenance [{:source :git :ref "git:r@s:datasety.json#prywatny_held_out"}] :observations []}
        by-t (into {} (map (juxt :title identity)) (glossary/candidates [doc ds]))
        c    (by-t "held-out")]
    (is c "hyphenated jargon term with a defining node becomes a concept")
    (is (= #{"01HELD" "01DS"} (set (get-in c [:links :defined-by])))
        "both title-subject nodes define the term")))

(deftest tag-concept-keeps-key-but-gains-defining-link
  ;; existing tag concepts keep their source_key (no id churn) yet now link to the
  ;; entity whose title carries the tag-term, so they can finally ground.
  (let [a {:id "01A" :type :experiment :title "exp on cke" :tags ["cke" "eval"]
           :provenance [{:source :git :ref "git:r@s:a"}] :observations []}
        b {:id "01B" :type :experiment :title "another" :tags ["cke"]
           :provenance [{:source :git :ref "git:r@s:b"}] :observations []}
        ds {:id "01C" :type :dataset :title "Arkusze CKE/OKE" :aliases ["cke"]
            :state "arkusze egzaminacyjne CKE" :tags []
            :provenance [{:source :git :ref "git:r@s:c"}] :observations []}
        by-t (into {} (map (juxt :title identity)) (glossary/candidates [a b ds]))
        c    (or (by-t "cke") (by-t "CKE"))]
    (is c)
    (is (= "glossary:tag:cke" (:source_key c)) "tag-sourced term keeps its tag key")
    (is (contains? (set (get-in c [:links :defined-by])) "01C")
        "entity whose title/alias is the term now grounds the tag-concept")))

;; --- jargon noise filter (acronym lane) ------------------------------------

(deftest acronym-lane-rejects-code-and-snake-fragments
  ;; the dominant noise source: `flatten-body` folds code/data dumps into prose, so
  ;; the acronym lane harvested fragments of UPPER_SNAKE constants and pipe-tagged
  ;; morpheme labels. Boundaries + code-span stripping must drop those while real
  ;; standalone prose acronyms still become concepts.
  (let [a {:id "01A" :type :source :title "Morph report"
           :provenance [{:source :git :ref "git:r@s:morph.md"}]
           :observations [{:ref "git:r@s:morph.md#x"
                           :text (str "gold: ps|ROOT_ALLOMORPH em|INST_SG ; env "
                                      "GEN_DEFAULT; `DATASET_MANIFEST.md`. Bench: MMLU, KLEJ.")}]}
        b {:id "01B" :type :source :title "Plan"
           :provenance [{:source :git :ref "git:r@s:plan.md"}]
           :observations [{:ref "git:r@s:plan.md#y"
                           :text "Ewaluacja na MMLU i KLEJ; pred psem|ROOT m|INST."}]}
        titles (set (map :title (glossary/candidates [a b])))]
    (is (contains? titles "MMLU") "standalone prose acronym kept")
    (is (contains? titles "KLEJ") "standalone prose acronym kept")
    (doseq [noise ["ROOT" "ALLOMORPH" "INST" "DATASET" "DEFAULT" "MANIFEST"]]
      (is (not (contains? titles noise))
          (str noise " is a code/snake fragment, not a concept")))))

;; --- prune orphaned concepts -----------------------------------------------

(deftest build-prunes-orphaned-concepts
  ;; a concept minted by an earlier, looser heuristic (no longer a candidate) is a
  ;; derivation artifact and must be removed on rebuild; definition-less only.
  (with-temp-kb
    (fn []
      (let [ds {:type :dataset :title "Egzaminy CKE" :status :current :source_key "r:ds#a"
                :tags ["cke"] :aliases ["cke"] :provenance [{:source :git :ref "git:r@s:a#cke"}]
                :state "CKE to komisja egzaminacyjna." :observations []}
            bn {:type :benchmark :title "Bench CKE" :status :current :source_key "r:bn#b"
                :tags ["cke"] :provenance [{:source :git :ref "git:r@s:b#cke"}] :observations []}]
        (store/upsert! ds)
        (store/upsert! bn)
        (store/upsert! {:type :concept :title "ROOT" :status :draft :source_key "glossary:term:root"
                        :moc ["glosariusz"] :tags [] :aliases ["ROOT"] :visibility :public
                        :provenance [{:source :git :ref "git:r@s:a#cke"}]
                        :links {:mentions ["x"]} :observations []})
        (let [tally  (glossary/build!)
              titles (->> (store/all-notes) (map :node)
                          (filter #(= "concept" (name (:type %)))) (map :title) set)]
          (is (pos? (:pruned tally)) "orphan concept pruned")
          (is (not (contains? titles "ROOT")) "orphan gone from store")
          (is (contains? titles "CKE") "live candidate kept"))))))

;; --- grounding passages (Part C) -------------------------------------------

(deftest defining-node-grounds-unconditionally
  ;; THE load-bearing change: a defining node's title + state ground the term even
  ;; when that prose does not literally repeat the term (entity opis pattern).
  (let [by-id {"01BENCH" {:id "01BENCH" :title "LLMzSzŁ"
                          :state "egzaminy państwowe CKE · 154 domeny"
                          :provenance [{:source :git :ref "git:r@s:benchmarks.json#llmzszl"}]
                          :observations []}}
        c  {:title "LLMzSzŁ" :links {:defined-by ["01BENCH"] :mentions ["01BENCH"]}}
        ps (glossary/passages-for c by-id)]
    (is (some #(= "LLMzSzŁ" (:text %)) ps) "title is a grounding passage")
    (is (some #(str/includes? (:text %) "egzaminy") ps)
        "entity opis grounds the term even though it omits the literal term")))

(deftest weak-mention-needs-the-term
  ;; a node linked only as a weak mention contributes a passage ONLY for the units
  ;; that actually name the term — no fabrication from unrelated lines.
  (let [a {:id "01A" :state "CKE to Centralna Komisja Egzaminacyjna." :title "x"
           :provenance [{:source :git :ref "git:r@s:a#cke"}]
           :observations [{:ref "git:r@s:a#cke" :text "kategoria: eval"}]}
        c {:title "CKE" :links {:mentions ["01A"]}}        ; no defined-by
        ps (glossary/passages-for c {"01A" a})]
    (is (= 1 (count ps)) "only the State line names CKE; the off-topic obs is dropped")
    (is (= "git:r@s:a#cke" (:ref (first ps))))))

;; --- define! discipline (unchanged invariants) -----------------------------

(def ^:private using-a
  {:type :dataset :title "Egzaminy CKE" :status :current :source_key "r:ds#a"
   :tags ["cke" "eval"] :aliases ["cke"] :provenance [{:source :git :ref "git:r@s:a#cke"}]
   :state "CKE to Centralna Komisja Egzaminacyjna; jej egzaminy są źródłem zadań."
   :observations [{:date "" :ref "git:r@s:a#cke" :text "kategoria: eval"}]})

(def ^:private using-b
  {:type :benchmark :title "Bench CKE" :status :current :source_key "r:bn#b"
   :tags ["cke" "mcq"] :aliases ["cke"] :provenance [{:source :git :ref "git:r@s:b#cke"}]
   :state "Zadania CKE wykorzystywane jako benchmark." :observations []})

(defn- seed-and-build! []
  (store/upsert! using-a)
  (store/upsert! using-b)
  (glossary/build!))

(defn- cke-concept []
  (->> (store/all-notes) (map :node)
       (some #(when (and (= "concept" (name (:type %)))
                         (= "cke" (str/lower-case (str (:title %))))) %))))

(deftest define-inert-without-endpoint
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (binding [chat/*disabled* true]
        (let [r (glossary/define!)]
          (is (:inert r) "no chat endpoint -> inert, no generation")
          (is (nil? (:definition (cke-concept))) "concept left undefined")
          (is (= :draft (keyword (:status (cke-concept)))) "stays draft"))))))

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
                c1 (cke-concept)]
            (is (pos? (:defined r1)) "groundable concept defined")
            (is (= "CKE to Centralna Komisja Egzaminacyjna." (:definition c1)))
            (is (= :draft (keyword (:status c1))) "human still verifies -> draft")
            (testing "generate-once: re-run is a verified no-op, no regeneration"
              (let [before @calls
                    r2 (glossary/define!)
                    c2 (cke-concept)]
                (is (pos? (:skipped r2)) "already-defined concept skipped")
                (is (= before @calls) "model NOT called again for defined concept")
                (is (= (:content_hash c1) (:content_hash c2)) "no content-hash churn")))))))))

(deftest define-rejects-ungrounded-and-hallucinated
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})]
        (testing "grounded:false -> no definition (no fabrication)"
          (with-redefs [chat/complete-json (fn [_] {:definition "" :grounded false :refs []})]
            (is (pos? (:ungrounded (glossary/define!))))
            (is (nil? (:definition (cke-concept))))))
        (testing "cited ref not among supplied -> rejected as hallucinated provenance"
          (with-redefs [chat/complete-json (fn [_] {:definition "Made up." :grounded true
                                                    :refs ["git:r@s:NOPE#x"]})]
            (is (pos? (:ungrounded (glossary/define!))))
            (is (nil? (:definition (cke-concept))))))))))

(deftest define-accepts-bracketed-ref-echo
  ;; the model often echoes the whole "[ref] text" passage line instead of the bare
  ;; ref; that must still validate as grounded (ref substring), not be rejected.
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})
                    chat/complete-json
                    (fn [_] {:definition "CKE to Centralna Komisja Egzaminacyjna."
                             :grounded true
                             :refs ["[git:r@s:a#cke] CKE to Centralna Komisja Egzaminacyjna."]})]
        (is (pos? (:defined (glossary/define!))) "bracketed-ref echo still grounds")
        (is (seq (str (:definition (cke-concept)))))))))

(deftest build-preserves-definition-no-churn
  (with-temp-kb
    (fn []
      (seed-and-build!)
      (with-redefs [chat/config (constantly {:url "x" :model "m" :api-key nil})
                    chat/complete-json (fn [_] {:definition "CKE to komisja egzaminacyjna."
                                                :grounded true :refs ["git:r@s:a#cke"]})]
        (glossary/define!))
      (let [before (cke-concept)
            tally  (glossary/build!)
            after  (cke-concept)]
        (is (= "CKE to komisja egzaminacyjna." (:definition after)) "definition preserved")
        (is (= (:content_hash before) (:content_hash after)) "no hash churn")
        (is (pos? (:unchanged tally)) "re-build is a no-op for the defined concept")))))
