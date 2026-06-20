(ns prophet.mcp.http
  "HTTP transport for the MCP server. A single POST endpoint feeds JSON-RPC
   requests to the pure `prophet.mcp.server/handle` (shared with the stdio
   transport — tool logic is not forked), plus a health endpoint. Binds to
   loopback behind a reverse proxy (TLS + domain + rate limiting at the edge).
   stdout is left clean; access logs go to stderr."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [prophet.mcp.server :as server]
            [prophet.mcp.telemetry :as tel]
            [prophet.index.query :as query])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange SimpleFileServer]
           [java.net InetSocketAddress]
           [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util.concurrent Executors]))

(def ^:private max-body
  "Request-body cap (bytes); larger requests are rejected with 413."
  (* 1 1024 1024))

(defn- log [& xs] (binding [*out* *err*] (apply println "[mcp-http]" xs)))

(defn traceparent-trace-id
  "Trace-id field of a W3C `traceparent` header (00-<trace>-<span>-<flags>), or nil."
  [tp]
  (when tp
    (let [parts (str/split tp #"-")]
      (when (>= (count parts) 2) (nth parts 1)))))

(defn parse-allowlist
  "Parse MCP_ALLOWED_ORIGINS (comma-separated) into a seq of origins, or nil."
  [s]
  (when-not (str/blank? s)
    (seq (remove str/blank? (map str/trim (str/split s #","))))))

(defn allowed-origin?
  "True if `origin` is permitted under parsed allowlist `allowed`. An empty/nil
   allowlist allows all; a nil `Origin` (non-browser clients omit it) is allowed;
   otherwise the origin must be a member of the allowlist."
  [allowed origin]
  (or (empty? allowed)
      (nil? origin)
      (boolean (some #{origin} allowed))))

(defn- ct-eq? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                         (.getBytes b StandardCharsets/UTF_8)))

(defn authorized?
  "True if the Authorization `header` carries the configured bearer `token`. A
   nil/blank token means auth is disabled (open). Compare is constant-time."
  [token header]
  (or (str/blank? token)
      (boolean (and header (ct-eq? (str "Bearer " token) header)))))

(defn- send-json! [^HttpExchange ex status obj]
  (let [body (.getBytes (json/write-str obj) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (alength body))
    (with-open [os (.getResponseBody ex)] (.write os body))))

(defn- read-body ^String [^HttpExchange ex]
  (let [is (.getRequestBody ex)
        baos (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read is buf)]
        (cond
          (neg? n) (.toString baos "UTF-8")
          (> (+ total n) max-body) (throw (ex-info "request too large" {:status 413}))
          :else (do (.write baos buf 0 n) (recur (+ total n))))))))

(defn- mcp-handler []
  (reify HttpHandler
    ;; ex is rebound with a hint inside; hinting the reify method param itself
    ;; trips a "Mismatched return type" compiler quirk for void methods.
    (handle [_ ex]
      (let [^HttpExchange ex ex]
        (try
          (let [headers (.getRequestHeaders ex)
                origin  (.getFirst headers "Origin")
                auth    (.getFirst headers "Authorization")
                allowed (parse-allowlist (System/getenv "MCP_ALLOWED_ORIGINS"))
                token   (System/getenv "MCP_AUTH_TOKEN")]
            (cond
              (not= "POST" (.getRequestMethod ex))
              (send-json! ex 405 {:error "method not allowed"})

              (not (allowed-origin? allowed origin))
              (send-json! ex 403 {:error "origin not allowed"})

              (not (authorized? token auth))
              (send-json! ex 401 {:error "unauthorized"})

              :else
              (let [tp (.getFirst headers "traceparent")]
                (log "traceparent" (if tp "present" "absent"))
                (binding [tel/*transport*  "http"
                          tel/*session-id* (or (traceparent-trace-id tp) (str (random-uuid)))]
                  (let [t0  (System/currentTimeMillis)
                        req (json/read-str (read-body ex) :key-fn keyword)
                        resp (server/handle req)]
                    (log (:method req) (str (- (System/currentTimeMillis) t0) "ms"))
                    (if resp
                      (send-json! ex 200 resp)
                      (send-json! ex 202 {})))))))
          (catch Exception e
            (let [status (or (:status (ex-data e)) 400)]
              (log "error" status (.getMessage e))
              (try (send-json! ex status {:jsonrpc "2.0" :id nil
                                          :error {:code -32700 :message (str (.getMessage e))}})
                   (catch Exception _ nil))))
          (finally (.close ex))))
      nil)))

(defn- health-handler []
  (reify HttpHandler
    (handle [_ ex]
      (let [^HttpExchange ex ex]
        (try (send-json! ex 200 {:status "ok" :db query/*db-path*})
             (finally (.close ex))))
      nil)))

(defn serve
  "Start the MCP HTTP server and block. Host/port from MCP_HTTP_HOST (default
   127.0.0.1) and MCP_HTTP_PORT / PORT (default 8765). POST /mcp = JSON-RPC over
   the shared handler; GET /health = liveness."
  []
  (let [port (Integer/parseInt (or (System/getenv "MCP_HTTP_PORT")
                                   (System/getenv "PORT") "8765"))
        host (or (System/getenv "MCP_HTTP_HOST") "127.0.0.1")
        srv  (HttpServer/create (InetSocketAddress. host (int port)) 0)
        web  (System/getenv "PROPHET_WEB_DIR")]
    (.createContext srv "/mcp" (mcp-handler))
    (.createContext srv "/health" (health-handler))
    ;; Optionally serve the static Hugo build at / from the same process; /mcp and
    ;; /health win by longest-prefix match.
    (when (and web (.isDirectory (io/file web)))
      (.createContext srv "/" (SimpleFileServer/createFileHandler
                               (.toAbsolutePath (.toPath (io/file web)))))
      (log "serving static site from" web))
    (.setExecutor srv (Executors/newFixedThreadPool 4))
    (.start srv)
    (log "serving on" (str "http://" host ":" port) "(/mcp, /health); db =" query/*db-path*)
    @(promise)))
