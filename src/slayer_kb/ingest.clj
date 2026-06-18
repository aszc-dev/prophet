(ns slayer-kb.ingest
  "Drives a SourceAdapter through extract -> store. Deterministic; re-running is
   a no-op (stable ids via source_key reuse + content-hash skip)."
  (:require [slayer-kb.adapters.repo :as repo]
            [slayer-kb.extract.log :as log]
            [slayer-kb.extract.page :as page]
            [slayer-kb.extract.card :as card]
            [slayer-kb.extract.config :as config]
            [slayer-kb.resolve.link :as resolve]
            [slayer-kb.store.node :as store]))

(defn- extract-for
  "Dispatch a RawItem to its per-kind extractor. :code/:data are reference-only
   in v0 (no node body)."
  [{:keys [kind] :as item}]
  (case kind
    :log    (log/extract item)
    :page   (page/extract item)
    :card   (card/extract item)
    :config (config/extract item)
    nil))

(defn ingest-repo!
  "Cold or incremental ingest of a source repo into the note store.
   Returns a summary map of write outcomes."
  [dir]
  (let [a        (repo/adapter dir)
        refs     (repo/discover a)
        existing (mapv :node (store/all-notes))
        raw      (->> refs (map #(repo/fetch a %)) (mapcat extract-for) (remove nil?))
        prepared (resolve/prepare raw existing)
        results  (map store/upsert! prepared)]
    (reduce (fn [acc {:keys [status]}] (update acc status (fnil inc 0)))
            {:head (repo/head-sha dir) :refs (count refs)
             :links (reduce + (map #(reduce + (map count (vals (:links %)))) prepared))}
            results)))
