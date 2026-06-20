(ns prophet.extract.page
  "Hugo content pages -> nodes. The Hugo section (content/<section>/...) maps to a
   MOC + a node type; H2/H3 headings become provenance-bearing observations.
   Deterministic, no LLM (ingest-repo.md §2)."
  (:require [clojure.string :as str]
            [prophet.extract.common :as c]))

;; Hugo section -> {:type :moc}. Data table; the lab tunes it.
(def default-section-map
  "Hugo section name -> {:type :moc} for pages; falls back to :concept when unmapped."
  {"eksperymenty" {:type :experiment :moc "trening"}
   "datasety"     {:type :dataset    :moc "dane"}
   "benchmarks"   {:type :benchmark  :moc "ewaluacja"}
   "recepty"      {:type :recipe     :moc "trening"}
   "glosariusz"   {:type :concept    :moc "glosariusz"}
   "decyzje"      {:type :decision   :moc "decyzje"}})

(defn- section-of [path]
  (some-> (re-find #"content/([^/]+)/" (str path)) second))

(defn extract
  "RawItem (kind :page) -> [node]. One node per page; sections -> observations."
  ([item] (extract item default-section-map))
  ([{:keys [body ref meta]} section-map]
   (let [path     (:path meta)
         section  (section-of path)
         {:keys [type moc]} (get section-map section {:type :concept :moc section})
         [front prose] (c/split-frontmatter body)
         secs     (c/sections prose)
         intro    (some #(when (nil? (:heading %)) (:text %)) secs)
         obs      (->> secs
                       (filter :heading)
                       (map (fn [{:keys [heading text]}]
                              {:date (or (:date front) "")
                               :ref  (str ref "#" (c/anchor heading))
                               :text (str heading " — " (c/flatten-body text))})))]
     [{:type        type
       :title       (or (:title front) (str/replace (or (last (str/split (str path) #"/")) "") #"\.md$" ""))
       :status      (keyword (or (:status front) "current"))
       :moc         (vec (distinct (remove nil? (concat (when moc [moc]) (:moc front) (:tags front)))))
       :tags        (vec (:tags front))
       :visibility  (keyword (or (:visibility front) "public"))
       :source_key  (str (:repo meta) ":" path)
       :provenance  [{:source :git :ref ref}]
       :links       {}
       :link_hints  (cond-> {}
                      (:dataset front) (assoc :uses-dataset [(:dataset front)])
                      (:recipe front)  (assoc :uses-recipe  [(:recipe front)]))
       :state       (or (:summary front) intro)
       :observations (vec obs)}])))
