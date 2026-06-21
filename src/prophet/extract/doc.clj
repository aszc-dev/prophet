(ns prophet.extract.doc
  "Plain narrative markdown (no Hugo frontmatter): data-lineage / plan / proposal
   docs. One `source` node per file; the H1 is the title, H2/H3 headings become
   provenance-bearing observations. Deterministic."
  (:require [clojure.string :as str]
            [prophet.extract.common :as c]))

(defn- h1 [body]
  (some->> (str/split-lines (or body ""))
           (some #(second (re-matches #"#\s+(.*)" %)))))

(defn extract
  "RawItem (kind :doc) -> [node]."
  [{:keys [body ref meta]}]
  (let [path  (:path meta)
        [front prose] (c/split-frontmatter body)
        body* (or prose body)
        secs  (c/sections body*)
        intro (some #(when (nil? (:heading %)) (:text %)) secs)
        fname (str/replace (or (last (str/split (str path) #"/")) "") #"\.md$" "")
        ;; The lead paragraph (or front-matter summary) is the doc's definition; it
        ;; becomes a :definition observation carrying the node's ref, NOT a State
        ;; field — State originates only from observations (ADR-017). This also lets
        ;; the glossary ground a concept whose definer is this doc.
        lead  (or (:summary front) (some-> intro c/flatten-body not-empty))
        def-obs (when lead [{:date "" :ref ref :kind :definition :text lead}])
        sec-obs (->> secs
                     (filter :heading)
                     (map (fn [{:keys [heading text]}]
                            {:date "" :ref (str ref "#" (c/anchor heading))
                             :text (str heading " — " (c/flatten-body text))})))]
    [{:type        :source
      :title       (or (:title front) (h1 body*) fname)
      :status      :current
      :moc         (vec (:tags front))
      :tags        (vec (:tags front))
      :visibility  :public
      :source_key  (str (:repo meta) ":" path)
      :provenance  [{:source :git :ref ref}]
      :links       {}
      :observations (vec (concat def-obs sec-obs))}]))
