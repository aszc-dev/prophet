(ns slayer-kb.extract.common
  "Shared deterministic parsing helpers for source artifacts (not for our own
   notes — see store.node for that side)."
  (:require [clojure.string :as str]
            [clj-yaml.core :as yaml]))

(defn split-frontmatter
  "Split a source markdown file into [frontmatter-map body]. Returns [nil body]
   when there is no `--- ... ---` block."
  [^String content]
  (if-let [[_ fm body] (re-matches #"(?s)\s*---\n(.*?)\n---\n?(.*)" content)]
    [(yaml/parse-string fm :keywords true) body]
    [nil content]))

(defn sections
  "Split a markdown body into sections by ATX headings (## or ###).
   Returns a seq of {:heading <str|nil> :level <int|0> :text <str>}. Content
   before the first heading is a section with :heading nil."
  [^String body]
  (let [lines (str/split-lines (or body ""))]
    (loop [ls lines, cur {:heading nil :level 0 :lines []}, out []]
      (if (empty? ls)
        (->> (conj out cur)
             (map #(assoc % :text (str/trim (str/join "\n" (:lines %)))))
             (remove #(str/blank? (:text %)))
             vec)
        (let [l (first ls)]
          (if-let [[_ hashes heading] (re-matches #"(#{2,3})\s+(.*)" l)]
            (recur (rest ls)
                   {:heading (str/trim heading) :level (count hashes) :lines []}
                   (conj out cur))
            (recur (rest ls) (update cur :lines conj l) out)))))))

(defn anchor
  "Hugo/GitHub-style heading anchor for a provenance ref fragment."
  [heading]
  (when heading
    (-> heading str/lower-case
        (str/replace #"[^a-z0-9\s-]" "")
        (str/replace #"\s+" "-"))))
