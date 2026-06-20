(ns prophet.index.db
  "JVM-only SQLite access for the derived index. sqlite-vec is loaded as a
   runtime extension (see decisions.md ADR-006). The index is never authoritative;
   it is fully rebuildable from the md+YAML note store."
  (:require [clojure.java.io :as io])
  (:import [org.sqlite SQLiteConfig]
           [java.sql Connection DriverManager]))

(defn- platform
  "Running JVM OS/arch -> {:dir vendored-native-dir :ext loadable-suffix}."
  []
  (let [os   (.toLowerCase (System/getProperty "os.name"))
        arch (.toLowerCase (System/getProperty "os.arch"))]
    (cond
      (and (.contains os "mac")   (#{"aarch64" "arm64"} arch)) {:dir "macos-arm64"   :ext ".dylib"}
      (and (.contains os "mac")   (.contains arch "x86"))      {:dir "macos-x86_64"  :ext ".dylib"}
      (and (.contains os "linux") (or (.contains arch "amd") (.contains arch "x86"))) {:dir "linux-x86_64" :ext ".so"}
      (and (.contains os "linux") (#{"aarch64" "arm64"} arch)) {:dir "linux-aarch64" :ext ".so"}
      :else (throw (ex-info "No vendored sqlite-vec for this platform"
                            {:os os :arch arch})))))

(defn vec0-path
  "Absolute filesystem path (extension stripped) to the vendored vec0 loadable
   extension for the running platform. sqlite's load_extension appends the OS
   suffix (.dylib/.so)."
  []
  (let [{:keys [dir ext]} (platform)
        res (io/resource (str "native/" dir "/vec0" ext))]
    (when-not res
      (throw (ex-info "vec0 extension not found on classpath" {:dir dir})))
    (let [p (.getPath (io/file res))]
      (subs p 0 (- (count p) (count ext))))))

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
