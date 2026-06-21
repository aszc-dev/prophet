(ns prophet.index.embed
  "Optional embedding client. OpenAI-compatible /v1/embeddings. When no endpoint
   is configured, embeddings are absent and the vector lane stays inert (weight 0)
   — see decisions.md ADR-009. Dimension is pinned at 1024."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def native-dim
  "The model's native embedding width (Qwen3-Embedding-0.6B = 1024)."
  1024)

(def dim
  "Embedding vector dimension; pinned at 1024 to match the vec0 table and model."
  1024)

(def default-model
  "Default embedding model — the local omlx deployment's model (ADR-013). Overridable
   via SLAYER_EMBED_MODEL; document and query vectors must come from the same model."
  "Qwen3-Embedding-0.6B-8bit")

(def ^:dynamic *disabled*
  "Force inert mode regardless of env (tests bind this true for determinism)."
  false)

(defn config
  "Embedding endpoint config from env, or nil when unset/disabled (inert mode)."
  []
  (when-let [url (and (not *disabled*) (System/getenv "SLAYER_EMBED_URL"))]
    {:url     url
     :model   (or (System/getenv "SLAYER_EMBED_MODEL") default-model)
     :api-key (System/getenv "SLAYER_EMBED_API_KEY")}))

(defn request-body
  "OpenAI /v1/embeddings request body for `model` over `texts`. Sends `:dimensions`
   only for genuine MRL truncation (dim < native width); omits it at native width,
   where some servers (incl. TEI) 400 on the param — ADR-009/010."
  [model texts]
  (cond-> {:model model :input (vec texts)}
    (not= dim native-dim) (assoc :dimensions dim)))

(defn- post-json [url api-key body]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) .build)
        req    (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofSeconds 300))
                   (.header "Content-Type" "application/json")
                   (cond-> api-key (.header "Authorization" (str "Bearer " api-key)))
                   (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                   .build)
        resp   (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info "embed endpoint error" {:status (.statusCode resp) :body (.body resp)})))
    (json/read-str (.body resp) :key-fn keyword)))

(def ^:dynamic *max-batch*
  "Max inputs per /v1/embeddings request. Under TEI's 32-item client cap, and kept
   small so a request stays well within the timeout on a slow CPU backend."
  8)

(defn embed-batch
  "Embed a seq of strings -> seq of float vectors (length `dim`), chunked into
   requests of at most `*max-batch*` inputs. Returns nil when no endpoint is
   configured (inert mode)."
  [texts]
  (when-let [{:keys [url model api-key]} (config)]
    (let [endpoint (str url (when-not (re-find #"/v1/embeddings$" url) "/v1/embeddings"))]
      (->> (partition-all *max-batch* texts)
           (mapcat (fn [chunk]
                     (->> (:data (post-json endpoint api-key (request-body model chunk)))
                          (sort-by :index)
                          (map :embedding))))
           vec))))

(defn vec->literal
  "sqlite-vec accepts a JSON array literal for a vector value."
  [v]
  (json/write-str (vec v)))
