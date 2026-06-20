(ns prophet.extract.log
  "Deterministic extractor for experiment logs (jsonl). Each row -> one
   `experiment` node. The lab's log already encodes hypothesis/setup/result/
   decision, so this yields the most graph for the least work and needs no LLM
   (ingest-repo.md §2)."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]))

(defn- basename-noext
  "Last path segment with a trailing file extension stripped, for matching a
   recipe/config reference to its node alias."
  [s]
  (-> (str s) (str/split #"/") last (str/replace #"\.[a-z0-9]+$" "")))

(defn- row-key
  "Stable natural key for a row, independent of commit sha (so re-ingest reuses
   the node's ULID). Prefers an explicit id field; falls back to line ordinal."
  [repo path row i]
  (let [natural (or (:id row) (:run row) (:run_id row) (str "row" i))]
    (str repo ":" path "#" natural)))

(defn- prov-ref
  "Per-row provenance: the file ref plus a line anchor."
  [base-ref i]
  (str base-ref "#L" (inc i)))

(defn- observations [row ref]
  (->> [[:result   (:result row)]
        [:decision (:decision row)]
        [:cost     (:cost row)]]
       (keep (fn [[k v]]
               (when (and v (not (str/blank? (str v))))
                 {:date (or (:date row) (:when row) "")
                  :ref  ref
                  :text (str (name k) ": " v)})))
       vec))

(defn row->node
  "One jsonl row + context -> an experiment node map (pre-resolution).
   :link_hints carry structural targets the resolver wires into :links later."
  [{:keys [repo path moc base-ref]} row i]
  (let [ref (prov-ref base-ref i)]
    {:type        :experiment
     :title       (or (:title row) (:name row) (:hypothesis row) (str path " row " (inc i)))
     :status      :current
     :moc         (or moc [])
     :tags        (vec (:tags row))
     :visibility  (keyword (or (:visibility row) "public"))
     :source_key  (row-key repo path row i)
     :provenance  [{:source :git :ref ref}]
     :links       {}
     :link_hints  (cond-> {}
                    (:dataset row) (assoc :uses-dataset [(:dataset row)])
                    (:recipe row)  (assoc :uses-recipe  [(basename-noext (:recipe row))])
                    (:model row)   (assoc :mentions     [(:model row)]))
     :state       (or (:hypothesis row) nil)
     :observations (observations row ref)}))

(defn extract
  "RawItem (kind :log) -> seq of experiment node maps."
  [{:keys [body ref meta]}]
  (let [{:keys [repo path]} meta
        ;; default MOC from the path's top content/cards segment if present
        moc (when path
              (some-> (re-find #"(?:content|cards)/([^/]+)/" path) second vector))
        ctx {:repo repo :path path :moc (or moc []) :base-ref ref}]
    (->> (str/split-lines (or body ""))
         (map-indexed (fn [i line] [i (str/trim line)]))
         (remove (fn [[_ line]] (str/blank? line)))
         (map (fn [[i line]] (row->node ctx (json/read-str line :key-fn keyword) i)))
         vec)))
