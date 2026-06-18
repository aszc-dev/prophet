(ns slayer-kb.index.embed
  "Optional embedding client. OpenAI-compatible /v1/embeddings. When no endpoint
   is configured, embeddings are absent and the vector lane stays inert (weight 0)
   — see decisions.md ADR-009. Dimension is pinned at 1024."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def dim 1024)

(def ^:dynamic *disabled*
  "Force inert mode regardless of env (tests bind this true for determinism)."
  false)

(defn config
  "Embedding endpoint config from env, or nil when unset/disabled (inert mode)."
  []
  (when-let [url (and (not *disabled*) (System/getenv "SLAYER_EMBED_URL"))]
    {:url     url
     :model   (or (System/getenv "SLAYER_EMBED_MODEL")
                  "mlx-community/Qwen3-Embedding-0.6B-8bit")
     :api-key (System/getenv "SLAYER_EMBED_API_KEY")}))

(defn- post-json [url api-key body]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build)
        req    (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofSeconds 120))
                   (.header "Content-Type" "application/json")
                   (cond-> api-key (.header "Authorization" (str "Bearer " api-key)))
                   (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                   .build)
        resp   (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info "embed endpoint error" {:status (.statusCode resp) :body (.body resp)})))
    (json/read-str (.body resp) :key-fn keyword)))

(defn embed-batch
  "Embed a seq of strings -> seq of float vectors (length `dim`). Returns nil when
   no endpoint is configured (inert mode)."
  [texts]
  (when-let [{:keys [url model api-key]} (config)]
    (let [endpoint (str url (when-not (re-find #"/v1/embeddings$" url) "/v1/embeddings"))
          resp (post-json endpoint api-key {:model model :input (vec texts) :dimensions dim})]
      (->> (:data resp) (sort-by :index) (map :embedding)))))

(defn vec->literal
  "sqlite-vec accepts a JSON array literal for a vector value."
  [v]
  (json/write-str (vec v)))
