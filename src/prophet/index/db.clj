(ns prophet.index.db
  "JVM-only SQLite access for the derived index. sqlite-vec is loaded as a
   runtime extension (see decisions.md ADR-006). The index is never authoritative;
   it is fully rebuildable from the md+YAML note store."
  (:require [clojure.java.io :as io])
  (:import [org.sqlite SQLiteConfig]
           [java.sql Connection DriverManager]))

(defn- arch-dir
  "Map the running JVM's OS/arch to the vendored native dir holding vec0."
  []
  (let [os   (.toLowerCase (System/getProperty "os.name"))
        arch (.toLowerCase (System/getProperty "os.arch"))]
    (cond
      (and (.contains os "mac") (#{"aarch64" "arm64"} arch)) "macos-arm64"
      (and (.contains os "mac") (.contains arch "x86"))       "macos-x86_64"
      :else (throw (ex-info "No vendored sqlite-vec for this platform"
                            {:os os :arch arch})))))

(defn vec0-path
  "Absolute filesystem path to the vendored vec0 loadable extension.
   sqlite's load_extension resolves the platform suffix (.dylib/.so), so we hand
   it the path without extension."
  []
  (let [res (io/resource (str "native/" (arch-dir) "/vec0.dylib"))]
    (when-not res
      (throw (ex-info "vec0 extension not found on classpath" {:arch (arch-dir)})))
    ;; strip the .dylib so load_extension picks the right suffix per-OS
    (let [p (.getPath (io/file res))]
      (subs p 0 (- (count p) (count ".dylib"))))))

(defn open
  "Open a JDBC Connection to db-path with extension loading enabled and vec0
   loaded. `:memory:` for an in-memory DB. Caller owns closing it."
  ^Connection [db-path]
  (let [cfg   (doto (SQLiteConfig.) (.enableLoadExtension true))
        conn  (DriverManager/getConnection (str "jdbc:sqlite:" db-path)
                                           (.toProperties cfg))]
    (with-open [st (.createStatement conn)]
      (.execute st (str "select load_extension('" (vec0-path) "')")))
    conn))

(defn smoke
  "Prove the index layer can load sqlite-vec + FTS5 in one file-backed DB.
   Returns a map of probe results; throws if the stack is broken."
  [db-path]
  (with-open [conn (open db-path)
              st   (.createStatement conn)]
    (let [ver (let [rs (.executeQuery st "select vec_version()")]
                (.next rs) (.getString rs 1))]
      (.execute st "create virtual table if not exists smoke_fts using fts5(t)")
      (.execute st "create virtual table if not exists smoke_vec using vec0(id integer primary key, e float[4])")
      {:vec-version ver
       :fts5 true
       :vec0 true
       :db db-path})))
