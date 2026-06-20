(ns prophet.db-test
  "P0-1: the deploy crashed because resources/native/linux-x86_64/vec0.so was not
   vendored, only macOS arm64 — and CI ran on macOS, hiding it. This asserts the
   loadable for the RUNNING platform is actually on disk (RED on an arch whose
   binary is missing). db/smoke (exercised by index-test) is the load-level check."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [prophet.index.db :as db]))

(deftest vendored-vec0-exists-for-this-platform
  (let [os   (.toLowerCase (System/getProperty "os.name"))
        ext  (if (.contains os "mac") ".dylib" ".so")
        path (str (db/vec0-path) ext)]
    (is (.exists (io/file path))
        (str "vendored vec0 loadable missing for this platform: " path))))
