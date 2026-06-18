(ns slayer-kb.index.chat
  "Optional chat client. OpenAI-compatible /v1/chat/completions, used only to fill
   genuine gaps (ADR-005) — currently the grounded glossary definitions. When no
   endpoint is configured the caller stays inert (skips generation), exactly like
   the embedding vector lane (ADR-009). Same operational rules as embeddings: use
   127.0.0.1 (not localhost) so the JVM HttpClient does not resolve to IPv6 ::1,
   and supply a bearer token (omlx requires one)."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:dynamic *disabled*
  "Force inert mode regardless of env (tests bind this true for determinism)."
  false)

(defn config
  "Chat endpoint config from env, or nil when unset/disabled (inert mode)."
  []
  (when-let [url (and (not *disabled*) (System/getenv "SLAYER_CHAT_URL"))]
    {:url     url
     :model   (or (System/getenv "SLAYER_CHAT_MODEL")
                  "mlx-community/Qwen3-8B-4bit")
     :api-key (System/getenv "SLAYER_CHAT_API_KEY")}))

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
      (throw (ex-info "chat endpoint error" {:status (.statusCode resp) :body (.body resp)})))
    (json/read-str (.body resp) :key-fn keyword)))

(defn complete-json
  "Send `messages` to the chat endpoint and parse the assistant message as JSON.
   Returns the parsed map/value, or nil when no endpoint is configured (inert) or
   the response cannot be parsed as JSON. Temperature 0 for stability. Validation
   of the parsed shape is the caller's responsibility (validate-or-skip)."
  [messages]
  (when-let [{:keys [url model api-key]} (config)]
    (let [endpoint (str url (when-not (re-find #"/v1/chat/completions$" url) "/v1/chat/completions"))
          resp     (post-json endpoint api-key
                              {:model model
                               :messages (vec messages)
                               :temperature 0
                               :stream false
                               :response_format {:type "json_object"}})
          content  (-> resp :choices first :message :content)]
      (when (string? content)
        (try (json/read-str content :key-fn keyword)
             (catch Exception _ nil))))))
