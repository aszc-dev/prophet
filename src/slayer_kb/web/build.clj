(ns slayer-kb.web.build
  "Generate a roamable Hugo site from kb/. The content dir is fully derived — wiped
   and rebuilt every run, never hand-edited or written back (like the index). The
   generator does the load-bearing work deterministically: provenance -> source
   URLs, backlinks precomputed from the links graph, [[ULID]] wikilink rewrite via
   an id->permalink map, and a per-node local (ego) graph. Synthesis is NOT done
   here (that is v1.5); the existing State/Observations render as-is."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-yaml.core :as yaml]
            [slayer-kb.store.node :as store]))

(def web-root "web")

;; repo -> GitHub org, for turning a pinned git ref into a blob URL.
(def ^:private repo-org {"slayer" "slayerlabs" "recipes" "slayerlabs"})
(def ^:private default-org "slayerlabs")

(defn permalink [id] (str "/node/" id "/"))

;; --- provenance ref -> clickable source URL -------------------------------

(defn prov->url
  "git:repo@sha:path#anchor -> GitHub blob URL at the pinned sha (line anchors
   kept; section/record anchors dropped — the file still opens). http(s) refs pass
   through. discord/unknown -> nil (no public URL in v0.5)."
  [ref]
  (cond
    (str/starts-with? ref "http") ref
    (str/starts-with? ref "git:")
    (when-let [[_ repo sha rest] (re-matches #"git:([^@]+)@([^:]+):(.+)" ref)]
      (let [[path anchor] (str/split rest #"#" 2)
            org  (get repo-org repo default-org)
            frag (when (and anchor (re-matches #"L\d+" anchor)) (str "#" anchor))]
        (str "https://github.com/" org "/" repo "/blob/" sha "/" path frag)))
    :else nil))

(defn- ref-label
  "Compact display label for a provenance ref: drop the scheme and shorten a git
   sha to 7 chars (the full sha stays in the link URL)."
  [ref]
  (-> ref
      (str/replace #"^git:" "")
      (str/replace #"^https?://" "")
      (str/replace #"@([0-9a-f]{7})[0-9a-f]+:" "@$1:")))

;; --- graph maps ------------------------------------------------------------

(defn- id->meta [nodes]
  (into {} (map (fn [{:keys [id title type]}]
                  [id {:title title :type type :url (permalink id)}])
                nodes)))

(defn- backlinks
  "dst-id -> [{:src-id :title :url :rel}], precomputed from every node's links."
  [nodes meta]
  (reduce (fn [acc {:keys [id links]}]
            (reduce-kv (fn [acc rel dsts]
                         (reduce (fn [acc dst]
                                   (update acc dst (fnil conj [])
                                           {:id id :title (get-in meta [id :title])
                                            :url (permalink id) :rel (name rel)}))
                                 acc dsts))
                       acc links))
          {} nodes))

;; --- markdown rendering ----------------------------------------------------

(defn- link-md [{:keys [title url]}] (str "[" (or title "?") "](" url ")"))

(defn- rewrite-wikilinks
  "[[ULID]] -> [title](/node/ULID/) using the id index. Leaves unknown ids as text."
  [s meta]
  (when s
    (str/replace s #"\[\[([0-9A-HJKMNP-TV-Z]{26})\]\]"
                 (fn [[_ id]]
                   (if-let [m (meta id)] (link-md m) (str "`" id "`"))))))

(defn- observations-md [observations meta]
  (when (seq observations)
    (str "## Observations\n\n"
         (str/join "\n"
                   (for [{:keys [date ref text]} observations]
                     (let [url (prov->url ref)]
                       (str "- " (when (seq (str date)) (str "**" date "** "))
                            (rewrite-wikilinks text meta)
                            (when url (str " [↗](" url ")"))))))
         "\n")))

(defn- provenance-md [provenance]
  (when (seq provenance)
    ;; blank lines around the list so Goldmark parses it as markdown, not as the
    ;; literal contents of the raw-HTML <div> block.
    (str "## Provenance\n\n<div class=\"prov\">\n\n"
         (str/join "\n"
                   (for [{:keys [ref]} provenance]
                     (let [url (prov->url ref)]
                       (str "- " (if url (str "[`" (ref-label ref) "`](" url ")")
                                     (str "`" (ref-label ref) "`"))))))
         "\n\n</div>\n")))

(defn- related-md [links meta]
  (let [groups (for [[rel dsts] links :when (seq dsts)]
                 (str "**" (name rel) ":** "
                      (str/join ", " (map #(link-md (meta %)) dsts))))]
    (when (seq groups)
      (str "## Related\n\n" (str/join "  \n" groups) "\n"))))

(defn- backlinks-md [bls]
  (when (seq bls)
    (str "## Referenced by\n\n"
         (str/join "\n" (for [{:keys [url title rel]} bls]
                          (str "- [" title "](" url ") <span class=\"count\">(" rel ")</span>")))
         "\n")))

(defn- egograph-md [node links meta bls]
  (let [out (for [[rel dsts] links, d dsts] {:rel (name rel) :m (meta d)})
        in  (for [{:keys [rel title url]} bls] {:rel rel :m {:title title :url url}})]
    (when (or (seq out) (seq in))
      (str "## Local graph\n\n<div class=\"egograph\">\n\n"
           "<span class=\"center\">" (:title node) "</span>\n\n"
           (str/join "\n"
                     (concat
                      (for [{:keys [rel m]} out] (str "- → *" rel "* " (link-md m)))
                      (for [{:keys [rel m]} in]  (str "- ← *" rel "* " (link-md m)))))
           "\n\n</div>\n"))))

(defn node->page
  "A node + graph context -> {:path <rel> :content <md-with-frontmatter>}."
  [{:keys [id title type status moc tags state links observations] :as node} meta bls]
  (let [front {:title (str title)
               :date  (str (:updated node "2026-01-01"))
               :url   (permalink id)
               :nodetype (name type)
               :status (name (or status :current))
               :moc (vec moc)
               :tags (vec tags)}
        body  (->> [(rewrite-wikilinks state meta)
                    (observations-md observations meta)
                    (related-md links meta)
                    (backlinks-md bls)
                    (egograph-md node links meta bls)
                    (provenance-md (:provenance node))]
                   (remove nil?)
                   (str/join "\n\n"))]
    {:path (str "node/" id ".md")
     :content (str "---\n" (yaml/generate-string front :dumper-options {:flow-style :block})
                   "---\n\n" body "\n")}))

;; --- aggregate pages -------------------------------------------------------

(defn- moc-pages [nodes]
  (let [by-moc (reduce (fn [m n] (reduce #(update %1 %2 (fnil conj []) n) m (:moc n)))
                       {} nodes)]
    (cons
     {:path "moc/_index.md"
      :content (str "---\ntitle: MOCs\nurl: /moc/\n---\n\n"
                    "Maps of content — top-level facets.\n\n"
                    (str/join "\n" (for [[facet ns] (sort-by key by-moc)]
                                     (str "- [" facet "](/moc/" facet "/) <span class=\"count\">("
                                          (count ns) ")</span>")))
                    "\n")}
     (for [[facet ns] by-moc]
       {:path (str "moc/" facet ".md")
        :content
        (str "---\ntitle: \"MOC · " facet "\"\nurl: /moc/" facet "/\n---\n\n"
             (str/join "\n\n"
                       (for [[typ group] (sort-by key (group-by #(name (:type %)) ns))]
                         (str "## " typ "\n\n"
                              (str/join "\n" (for [n (sort-by :title group)]
                                               (str "- [" (:title n) "](" (permalink (:id n)) ")"))))))
             "\n")}))))

(defn- glossary-page [nodes]
  (let [concepts (->> nodes (filter #(= :concept (keyword (:type %)))) (sort-by :title))]
    {:path "glosariusz.md"
     :content
     (str "---\ntitle: Glossary\nurl: /glosariusz/\n---\n\n"
          "Vocabulary terms, each linked to the nodes that use it.\n\n"
          (str/join "\n" (for [c concepts]
                           (str "- [" (:title c) "](" (permalink (:id c)) ") "
                                "<span class=\"count\">(used in "
                                (count (get-in c [:links :mentions])) ")</span>")))
          "\n")}))

(defn- home-page [nodes]
  (let [by-type (->> nodes (group-by #(name (:type %))) (sort-by key))]
    {:path "_index.md"
     :content
     (str "---\ntitle: Slayer KB\n---\n\n"
          "## Browse\n\n- [Glossary](/glosariusz/)\n- [MOCs](/moc/)\n\n"
          "## Nodes by type\n\n"
          (str/join "\n" (for [[typ ns] by-type]
                           (str "- **" typ "** <span class=\"count\">(" (count ns) ")</span>")))
          "\n")}))

;; --- build -----------------------------------------------------------------

(defn- emit! [dir {:keys [path content]}]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)))

(defn- wipe! [dir]
  (when (.exists (io/file dir))
    (doseq [f (reverse (file-seq (io/file dir)))] (.delete f))))

(defn build!
  "Generate the site content from kb/ and run Hugo. Public nodes go to content/;
   internal nodes to content-internal/ (stubbed, unsecured — no auth in v0.5)."
  []
  (let [nodes  (mapv :node (store/all-notes))
        meta   (id->meta nodes)
        bls    (backlinks nodes meta)
        pub    (filterv #(not= :internal (keyword (:visibility %))) nodes)
        intern (filterv #(= :internal (keyword (:visibility %))) nodes)
        content-dir (str web-root "/content")
        intern-dir  (str web-root "/content-internal")]
    (wipe! content-dir) (wipe! intern-dir)
    (doseq [n pub] (emit! content-dir (node->page n meta (bls (:id n)))))
    (doseq [p (moc-pages pub)] (emit! content-dir p))
    (emit! content-dir (glossary-page pub))
    (emit! content-dir (home-page pub))
    (doseq [n intern] (emit! intern-dir (node->page n meta (bls (:id n)))))
    (let [{:keys [exit out err]}
          (sh/sh "hugo" "--source" web-root "--destination" "public"
                 "--cleanDestinationDir")]
      (when-not (zero? exit)
        (throw (ex-info "hugo build failed" {:out out :err err})))
      {:public-nodes (count pub) :internal-nodes (count intern)
       :site (str web-root "/public") :hugo (str/trim (or (last (str/split-lines out)) ""))})))
