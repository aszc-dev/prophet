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

;; --- section body -> single-line observation text --------------------------
;; Observations are line-based in the store (store.node/obs-re), so a section's
;; multi-line markdown body must collapse to one information-bearing line. We do
;; it structure-aware so the captured text carries the content instead of a bare
;; pointer: tables keep their cells, code blocks keep their code, prose stays
;; prose. (Per-row / per-block fan-out into distinct observations is future work.)

(defn- strip-edge-pipes [s]
  (-> (str/trim s) (str/replace #"^\|" "") (str/replace #"\|$" "")))

(defn- table-cells [l]
  (->> (str/split (strip-edge-pipes l) #"\|") (mapv str/trim)))

(defn- table-row? [l] (str/starts-with? (str/trim l) "|"))

(defn- table-sep-row?
  "A markdown table separator like `|---|:--:|` — cells are only -, :, space."
  [l]
  (every? #(re-matches #"[\s:-]*" %) (table-cells l)))

(defn- fence? [l] (re-matches #"\s*`{3,}.*" l))

(defn flatten-body
  "Collapse a markdown section body to one readable, content-bearing line.
   Tables render as `cell | cell` rows (separator rows dropped, no leading pipe);
   fenced code keeps its inner lines; prose lines are joined. Whitespace is
   normalized. Empty in -> empty out."
  [text]
  (loop [ls (str/split-lines (or text "")), in-code? false, out []]
    (if (empty? ls)
      (-> (str/join " " out) (str/replace #"\s+" " ") str/trim)
      (let [l (first ls)]
        (cond
          (fence? l)            (recur (rest ls) (not in-code?) out)
          in-code?              (recur (rest ls) in-code? (conj out (str/trim l)))
          (table-row? l)        (recur (rest ls) in-code?
                                       (if (table-sep-row? l)
                                         out
                                         (conj out (str/join " | " (table-cells l)))))
          :else                 (recur (rest ls) in-code? (conj out (str/trim l))))))))

(defn anchor
  "Hugo/GitHub-style heading anchor for a provenance ref fragment."
  [heading]
  (when heading
    (-> heading str/lower-case
        (str/replace #"[^a-z0-9\s-]" "")
        (str/replace #"\s+" "-"))))
