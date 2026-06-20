(ns prophet.provenance
  "Render a stored provenance ref into a clickable, commit-pinned source URL.
   READ-PATH ONLY: URLs are DERIVED at query time from the ref, never persisted —
   the md+YAML files stay the source of truth (invariant #1). An unknown scheme or
   an unconfigured repo yields nil; the raw ref still shows, so nothing is lost."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private github-repo
  "org/repo for a repo shortname, from resources/sources/<shortname>.edn :github,
   or nil when the source is unconfigured (graceful — the raw ref still shows).
   New repos slot in by setting one field; no hardcoding in the query layer."
  (memoize
   (fn [shortname]
     (when-let [res (io/resource (str "sources/" shortname ".edn"))]
       (:github (edn/read-string (slurp res)))))))

;; git:<repo>@<sha>:<path>#<anchor> — anchor optional. The file@sha is the
;; load-bearing part; the anchor is best-effort (GitHub only renders heading
;; anchors for markdown, so we attach it only for .md paths).
(def ^:private git-re #"^git:([^@]+)@([^:]+):([^#]+)(?:#(.+))?$")

(defn- git-url [ref]
  (when-let [[_ repo sha path anchor] (re-matches git-re ref)]
    (when-let [gh (github-repo repo)]
      ;; keep heading anchors for markdown (GitHub renders those) and line anchors
      ;; (Lnn) for any file; drop record/section anchors that wouldn't resolve.
      (let [keep? (and anchor (or (str/ends-with? path ".md") (re-matches #"L\d+" anchor)))]
        (str "https://github.com/" gh "/blob/" sha "/" path
             (when keep? (str "#" anchor)))))))

(defn ref->url
  "URL for a provenance ref, or nil for an unknown/unmappable scheme. The single
   source of truth for ref->URL (the web renderer delegates here). git -> GitHub
   blob at the pinned sha; web:/http(s) -> the url itself; discord/unknown -> nil."
  [ref]
  (let [ref (str ref)]
    (cond
      (str/starts-with? ref "git:")  (git-url ref)
      (str/starts-with? ref "web:")  (subs ref 4)
      (str/starts-with? ref "http")  ref
      :else nil)))
