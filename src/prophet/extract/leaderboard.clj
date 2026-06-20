(ns prophet.extract.leaderboard
  "Leaderboard results -> observation bundles that ATTACH to existing benchmark
   anchor nodes (the `observation` write shape: results are timestamped,
   provenance-bearing lines under an anchor, not new nodes). Each benchmark entry
   carries a nested `models` list of per-model scores."
  (:require [clojure.data.json :as json]))

(defn extract
  "RawItem (kind :leaderboard) -> seq of {:attach-to :benchmark :match <id>
   :observations [...]}. Attach is type-aware so a benchmark id that also names a
   dataset (e.g. `llmzszl`) lands on the benchmark, not the dataset."
  [{:keys [body ref]}]
  (let [d (json/read-str (or body "null") :key-fn keyword)]
    (for [b (:benchmarks d)
          :let [bid    (:benchmark b)
                bref   (str ref "#" bid)
                metric (:metric b)
                date   (or (:date b) "")
                model-obs (for [m (:models b)]
                            {:date date :ref bref
                             :text (str (:display_name m) ": " (:accuracy m)
                                        (when metric (str " (" metric ")")))})
                mean-obs (when-let [mean (:mean_across_runs b)]
                           {:date date :ref bref
                            :text (str "mean across " (:runs b) " runs: "
                                       (json/write-str mean))})]]
      {:attach-to :benchmark
       :match bid
       :observations (vec (cond-> model-obs mean-obs (conj mean-obs)))})))
