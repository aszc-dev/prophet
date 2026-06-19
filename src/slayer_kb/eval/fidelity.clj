(ns slayer-kb.eval.fidelity
  "Layer-1 extraction-quality linter. A pure scan over the note store that
   classifies every observation as real content or a stub, then emits a per-type
   and corpus scorecard. No index, no source re-parse, no LLM — a fast, honest
   baseline of how much of what we 'extracted' actually carries information.

   It targets the failure mode of the prose extractors (extract/page): a markdown
   section is collapsed to `heading — first-line`, so tables, code blocks and
   list/code bodies introduced by a trailing colon are dropped and the observation
   becomes a pointer, not a fact.

   Stub classes:
     :stub-empty   nothing meaningful after the heading separator
     :stub-fence   body is a bare code fence (``` / ```lang) — code block dropped
     :stub-table   body is a single table row/separator — rest of the table dropped
     :stub-html    body is a lone html tag
     :dangling     body ends in ':' — the block it introduced (list/code/table) is gone
   Everything else is :ok.

   fidelity = ok / total. strict_fidelity additionally counts :dangling as a miss."
  (:require [clojure.string :as str]
            [slayer-kb.store.node :as store]))

;; --- classification --------------------------------------------------------

;; The page extractor joins `heading " — " body` with an em dash. Split on it to
;; isolate the captured body; key:value observations (log/card/json) have no em
;; dash, so the whole text is treated as the body and judged on its own merits.
(def ^:private em-sep " — ")

(defn- body-of [text]
  (let [t (str/trim (str text))
        i (str/index-of t em-sep)]
    (str/trim (if i (subs t (+ i (count em-sep))) t))))

(def ^:private fence-re #"(?s)^`{3,}[a-zA-Z0-9+\-]*$")
(def ^:private table-sep-re #"^[\s|:\-]+$")
(def ^:private html-re #"^<[^>]+>$")

(defn classify
  "Observation text -> classification keyword."
  [text]
  (let [b (body-of text)]
    (cond
      (str/blank? b)                            :stub-empty
      (re-matches fence-re b)                   :stub-fence
      (and (re-matches table-sep-re b)
           (re-find #"[|\-]" b))                :stub-table
      (str/starts-with? b "|")                  :stub-table
      (re-matches html-re b)                    :stub-html
      (str/ends-with? b ":")                    :dangling
      :else                                     :ok)))

(def ^:private hard-stubs #{:stub-empty :stub-fence :stub-table :stub-html})

;; --- analysis --------------------------------------------------------------

(defn- node-rows
  "Flatten the store into one row per observation, tagged with its class."
  []
  (for [{:keys [file node]} (store/all-notes)
        o (:observations node)]
    {:type  (some-> (:type node) name)
     :title (:title node)
     :file  (.getPath ^java.io.File file)
     :class (classify (:text o))
     :text  (:text o)}))

(defn- pct [x] (format "%.1f%%" (* 100.0 (double x))))

(defn report
  "Compute the full scorecard as plain data (for CI / scorecard commits)."
  []
  (let [rows  (node-rows)
        total (count rows)
        freq  (frequencies (map :class rows))
        ok    (get freq :ok 0)
        hard  (reduce + (map #(get freq % 0) hard-stubs))
        dang  (get freq :dangling 0)
        ;; per node type
        by-type (->> (group-by :type rows)
                     (map (fn [[t rs]]
                            (let [tt (count rs)
                                  to (count (filter #(= :ok (:class %)) rs))]
                              [t {:total tt :ok to
                                  :fidelity (if (pos? tt) (/ (double to) tt) 1.0)}])))
                     (into (sorted-map)))
        ;; worst nodes by per-node fidelity (>=3 obs, to avoid noise)
        by-node (->> (group-by (juxt :file :title :type) rows)
                     (map (fn [[[file title type] rs]]
                            (let [tt (count rs)
                                  to (count (filter #(= :ok (:class %)) rs))]
                              {:file file :title title :type type
                               :total tt :ok to
                               :fidelity (if (pos? tt) (/ (double to) tt) 1.0)})))
                     (filter #(>= (:total %) 3))
                     (sort-by (juxt :fidelity (comp - :total))))]
    {:total           total
     :ok              ok
     ;; lenient: dangling bodies carry partial info, count them as half-credit hits
     :fidelity        (if (pos? total) (/ (double (+ ok dang)) total) 1.0)
     ;; strict: only clean observations count
     :strict-fidelity (if (pos? total) (/ (double ok) total) 1.0)
     :hard-stubs      hard
     :dangling        dang
     :by-class        freq
     :by-type         by-type
     :worst-nodes     (vec (take 10 by-node))
     :samples         (->> rows
                           (remove #(= :ok (:class %)))
                           (group-by :class)
                           (map (fn [[c rs]] [c (mapv :text (take 3 rs))]))
                           (into {}))}))

(defn print-report!
  "Human-readable scorecard to stdout."
  [{:keys [total ok fidelity strict-fidelity hard-stubs dangling
           by-class by-type worst-nodes samples]}]
  (println)
  (println "=== Extraction fidelity (layer 1) ===")
  (println (format "observations: %d  ok: %d  hard-stubs: %d  dangling: %d"
                   total ok hard-stubs dangling))
  (println (format "fidelity: %s (lenient, dangling=partial)   strict (ok only): %s"
                   (pct fidelity) (pct strict-fidelity)))
  (println)
  (println "by class:")
  (doseq [[c n] (sort-by (comp - val) by-class)]
    (println (format "  %-12s %5d  %s" (name c) n (pct (/ (double n) (max 1 total))))))
  (println)
  (println "by node type (fidelity):")
  (doseq [[t {:keys [total ok fidelity]}] by-type]
    (println (format "  %-12s %s  (%d/%d)" (or t "?") (pct fidelity) ok total)))
  (println)
  (println "worst nodes (>=3 obs):")
  (doseq [{:keys [fidelity ok total title type file]} worst-nodes]
    (println (format "  %s  %d/%d  [%s] %s" (pct fidelity) ok total type title))
    (println (format "             %s" file)))
  (println)
  (println "stub samples:")
  (doseq [[c texts] samples]
    (println (format "  %s:" (name c)))
    (doseq [t texts]
      (println (format "    %s" (let [t (str t)]
                                  (if (> (count t) 100) (str (subs t 0 100) "…") t))))))
  (println))

(defn scan!
  "Entry point: compute + print, return the data map."
  []
  (doto (report) print-report!))
