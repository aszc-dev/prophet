(ns slayer-kb.extract.card
  "Dataset / benchmark / model cards -> typed anchor nodes. Cards are the
   canonical-name authority: their titles/ids seed the alias table that later
   sources resolve against (ingest-repo.md §5). Deterministic, no LLM."
  (:require [clojure.string :as str]
            [slayer-kb.extract.common :as c]))

(defn- card-meta
  "A card is either YAML frontmatter + prose (.md) or a pure YAML doc (.yaml)."
  [{:keys [body title]}]
  (if (str/ends-with? (str title) ".yaml")
    (first (c/split-frontmatter (str "---\n" body "\n---\n")))
    (first (c/split-frontmatter body))))

(defn- observations [m ref]
  (->> [[:license         (:license m)]
        [:lineage         (:lineage m)]
        [:decontamination (:decontamination m)]
        [:size            (:size m)]
        [:metrics         (some-> (:metrics m) pr-str)]]
       (keep (fn [[k v]]
               (when (and v (not (str/blank? (str v))))
                 {:date (or (:date m) "") :ref ref
                  :text (str (name k) ": " v)})))
       vec))

(defn extract
  "RawItem (kind :card) -> [node]. Single node per card."
  [{:keys [ref meta] :as item}]
  (let [m    (card-meta item)
        path (:path meta) repo (:repo meta)
        name* (or (:name m) (:title m) (:id m) path)
        typ  (keyword (or (:type m) "dataset"))]
    [{:type        typ
      :title       name*
      :status      :current
      :moc         (or (some-> (re-find #"cards/([^/]+)/" (str path)) second vector) [])
      :tags        (vec (:tags m))
      :visibility  (keyword (or (:visibility m) "public"))
      :source_key  (str repo ":" path)
      :aliases     (->> [name* (:id m) (:short m)] (remove nil?) distinct vec)
      :provenance  [{:source :git :ref ref}]
      :links       {}
      :link_hints  (cond-> {}
                     (:derived_from m) (assoc :derives-from
                                              (if (coll? (:derived_from m))
                                                (vec (:derived_from m))
                                                [(:derived_from m)]))
                     (:benchmark m)    (assoc :measured-by [(:benchmark m)]))
      :state       (:description m)
      :observations (observations m ref)}]))
