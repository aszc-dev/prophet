(ns slayer-kb.adapters.repo
  "RepoAdapter: a source git repo (working tree + HEAD sha) -> RawItem seq.
   The first SourceAdapter implementation. Nothing source-specific may cross the
   RawItem boundary; a later DiscordAdapter reuses every downstream stage."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [slayer-kb.util :as util]))

(defprotocol SourceAdapter
  (discover [this]       "-> seq of refs (repo-relative paths) that exist")
  (fetch    [this ref]   "-> RawItem for a ref")
  (changed  [this since] "-> seq of refs changed since a cursor (sha)"))

;; path -> kind rules. Data, not code: the lab tunes these without touching the
;; engine (ingest-repo.md §1). First matching glob wins.
(def default-kind-rules
  [["content/**/*.md"        :page]
   ["cards/**/*.md"          :card]
   ["cards/**/*.yaml"        :card]
   ["**/eksperymenty*.jsonl" :log]
   ["**/*.jsonl"             :log]
   ["configs/**/*.yaml"      :config]
   ["configs/**/*.toml"      :config]
   ["src/**/*"               :code]
   ["**/*.safetensors"       :data]
   ["**/*.gguf"              :data]
   ["**/*.bin"               :data]])

(defn- glob->re
  "Translate a restricted glob (** **/ * ?) to an anchored regex. Literal regex
   metachars are escaped; only the glob wildcards are interpreted."
  [glob]
  (let [n (count glob)]
    (loop [i 0, sb (StringBuilder. "^")]
      (if (>= i n)
        (re-pattern (str sb "$"))
        (let [c (.charAt glob i)]
          (cond
            (and (= c \*) (< (inc i) n) (= (.charAt glob (inc i)) \*))
            (if (and (< (+ i 2) n) (= (.charAt glob (+ i 2)) \/))
              (recur (+ i 3) (.append sb "(?:.*/)?"))
              (recur (+ i 2) (.append sb ".*")))
            (= c \*) (recur (inc i) (.append sb "[^/]*"))
            (= c \?) (recur (inc i) (.append sb "[^/]"))
            (#{\. \( \) \[ \] \{ \} \+ \^ \$ \\ \|} c)
            (recur (inc i) (-> sb (.append \\) (.append c)))
            :else (recur (inc i) (.append sb c))))))))

(defn classify
  "First kind whose glob matches the repo-relative path, or nil."
  [rules path]
  (some (fn [[glob kind]] (when (re-matches (glob->re glob) path) kind)) rules))

(defn- git [dir & args]
  (let [{:keys [exit out err]} (apply sh/sh "git" "-C" (str dir) args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :err err})))
    (str/trim out)))

(defn head-sha [dir] (git dir "rev-parse" "HEAD"))

(defn- repo-name [dir] (.getName (io/file (str dir))))

(defrecord RepoAdapter [dir rules]
  SourceAdapter
  (discover [_]
    (let [root (io/file dir)]
      (->> (file-seq root)
           (filter #(.isFile %))
           (map #(-> (.toPath root) (.relativize (.toPath %)) str))
           (remove #(str/starts-with? % ".git/"))
           (filter #(classify rules %))
           sort)))

  (fetch [_ ref]
    (let [kind (classify rules ref)
          file (io/file dir ref)
          body (when-not (= kind :data) (slurp file)) ; blobs: reference-only
          sha  (head-sha dir)
          prov (str "git:" (repo-name dir) "@" sha ":" ref)]
      {:id           (util/sha256 (str "git:" ref))
       :source       :git
       :ref          prov
       :kind         kind
       :title        ref
       :body         (or body "")
       :meta         {:repo (repo-name dir) :sha sha :path ref}
       :content-hash (util/sha256 (str (or body ref)))
       :fetched-at   (str (java.time.Instant/now))}))

  (changed [_ since]
    (->> (git dir "diff" "--name-only" (str since "..HEAD"))
         str/split-lines
         (remove str/blank?)
         (filter #(classify rules %)))))

(defn adapter
  ([dir] (adapter dir default-kind-rules))
  ([dir rules] (->RepoAdapter dir rules)))
