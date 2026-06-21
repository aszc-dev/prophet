(ns prophet.synthesis-test
  "Extractive State synthesis (ADR-016): selection-only, deterministic, provenance-
   bound. Refs use the configured `slayer` shortname so they resolve; `git:ghost@…`
   is an unconfigured repo and therefore an unresolvable (orphan) ref."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.store.node :as store]
            [prophet.synthesis :as synthesis]))

(defn- rm-rf [f]
  (doseq [c (reverse (file-seq (io/file f)))] (.delete c)))

;; --- pure chokepoint -------------------------------------------------------

(deftest extractive-selects-top-2-verbatim
  (let [node {:type :dataset :title "T" :status :current
              :observations [{:date "" :ref "git:slayer@s:a.md" :text "first claim"}
                             {:date "" :ref "git:slayer@s:b.md" :text "second claim"}
                             {:date "" :ref "git:slayer@s:c.md" :text "third claim"}]}
        {:keys [status state]} (synthesis/synthesize-node node)]
    (is (= :filled status))
    (is (= "- [git:slayer@s:a.md] first claim\n- [git:slayer@s:b.md] second claim" state)
        "top-2 observations, verbatim, each with its ref")
    (is (not (str/includes? state "third")) "selection stops at the top-2")))

(deftest already-filled-state-is-skipped
  ;; a node carrying extraction-derived State must not be clobbered
  (let [node {:type :page :title "T" :status :current :state "source summary"
              :observations [{:date "" :ref "git:slayer@s:a.md" :text "x"}]}
        {:keys [status node]} (synthesis/synthesize-node node)]
    (is (= :skipped status))
    (is (= "source summary" (:state node)))))

(deftest no-observations-stays-pending
  (let [{:keys [status]} (synthesis/synthesize-node
                          {:type :page :title "T" :status :current :observations []})]
    (is (= :pending status))))

(deftest orphan-claim-leaves-node-pending
  ;; the selected claim's ref does not resolve -> node is NOT filled (provenance guard)
  (let [node {:type :dataset :title "T" :status :current
              :observations [{:date "" :ref "git:ghost@s:a.md" :text "unresolvable"}]}
        {:keys [status node]} (synthesis/synthesize-node node)]
    (is (= :orphan status))
    (is (nil? (:state node)) "an orphan claim is never written into State")))

;; --- the pass over a real store --------------------------------------------

(deftest run-is-idempotent-and-orphan-free
  (let [dir (str (System/getProperty "java.io.tmpdir") "/kb-synth-" (System/nanoTime))]
    (binding [store/*kb-root* dir]
      (try
        (let [{file :file} (store/upsert!
                            {:type :dataset :title "Held Out" :status :current
                             :source_key "slayer:x"
                             :provenance [{:source :git :ref "git:slayer@s:x.md"}]
                             :observations [{:date "" :ref "git:slayer@s:x.md"
                                             :text "private held-out exam sheets"}]})
              t1     (synthesis/run!)
              bytes1 (slurp file)
              t2     (synthesis/run!)
              bytes2 (slurp file)]
          (is (= 1 (:filled t1)) "the pending node is filled")
          (is (zero? (:filled t2)) "re-run synthesizes nothing")
          (is (= bytes1 bytes2) "re-run is byte-identical (no churn)")
          (is (str/includes? bytes1 "- [git:slayer@s:x.md] private held-out exam sheets")
              "State carries the claim verbatim with its ref")
          (is (empty? (synthesis/orphans)) "every synthesized claim resolves"))
        (finally (rm-rf dir))))))

(deftest orphans-detects-an-unresolvable-state-ref
  ;; the CI gate's basis: a State claim whose ref does not resolve is flagged
  (let [dir (str (System/getProperty "java.io.tmpdir") "/kb-orph-" (System/nanoTime))]
    (binding [store/*kb-root* dir]
      (try
        (store/upsert!
         {:type :page :title "Bad" :status :current :source_key "slayer:bad"
          :provenance [{:source :git :ref "git:slayer@s:bad.md"}]
          :state "- [discord:secret#123] leaked claim"
          :observations [{:date "" :ref "git:slayer@s:bad.md" :text "obs"}]})
        (let [orph (synthesis/orphans)]
          (is (= 1 (count orph)))
          (is (= "discord:secret#123" (:ref (first orph)))))
        (finally (rm-rf dir))))))
