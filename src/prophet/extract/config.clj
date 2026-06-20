(ns prophet.extract.config
  "Training/recipe configs -> `recipe` nodes + params. v0 handles YAML; TOML is
   deferred (recognized by the adapter, skipped here with a warning). Deterministic."
  (:require [clojure.string :as str]
            [clj-yaml.core :as yaml]))

(defn- params->obs [m ref]
  (->> [:model :base_model :lr :learning_rate :epochs :batch_size :method
        :dataset :optimizer]
       (keep (fn [k]
               (when-let [v (get m k)]
                 {:date "" :ref ref :text (str (name k) ": " v)})))
       vec))

(defn extract
  "RawItem (kind :config) -> [node] for YAML; [] (with a warning) for TOML."
  [{:keys [body ref meta title]}]
  (let [path (:path meta)]
    (if (or (str/ends-with? (str title) ".toml")
            (str/ends-with? (str path) ".toml"))
      (do (binding [*out* *err*] (println "config: TOML deferred, skipped:" path))
          [])
      (let [m (yaml/parse-string body :keywords true)
            fname (str/replace (or (last (str/split (str path) #"/")) "") #"\.ya?ml$" "")]
        [{:type        :recipe
          :title       (or (:name m) (:title m) fname)
          :status      :current
          :moc         ["trening"]
          :tags        (vec (:tags m))
          :visibility  (keyword (or (:visibility m) "public"))
          :source_key  (str (:repo meta) ":" path)
          :aliases     (->> [(:name m) fname] (remove nil?) distinct vec)
          :provenance  [{:source :git :ref ref}]
          :links       {}
          :link_hints  (cond-> {}
                         (:dataset m) (assoc :uses-dataset [(:dataset m)]))
          :state       (or (:description m) (str "Recipe " fname))
          :observations (params->obs m ref)}]))))
