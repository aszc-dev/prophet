(ns prophet.provenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [prophet.provenance :as prov]))

(def ^:private sha "c382999f4b59942718ae3bff43dfb04d69da80cc")

(deftest ref->url-git
  (testing "git .md with anchor -> blob URL + #anchor (GitHub renders md anchors)"
    (is (= (str "https://github.com/slayerlabs/slayer/blob/" sha
                "/bench/HELDOUT.md#co-to-jest")
           (prov/ref->url (str "git:slayer@" sha ":bench/HELDOUT.md#co-to-jest")))))
  (testing "git non-md -> blob URL, anchor dropped (would not resolve)"
    (is (= (str "https://github.com/slayerlabs/slayer/blob/" sha
                "/public/data/datasety.json")
           (prov/ref->url (str "git:slayer@" sha ":public/data/datasety.json#arc")))))
  (testing "git .md without anchor -> plain blob URL"
    (is (= (str "https://github.com/slayerlabs/slayer/blob/" sha "/README.md")
           (prov/ref->url (str "git:slayer@" sha ":README.md"))))))

(deftest ref->url-web
  (is (= "https://example.com/x" (prov/ref->url "web:https://example.com/x"))))

(deftest ref->url-nil-cases
  (testing "discord -> nil (no stable URL scheme yet)"
    (is (nil? (prov/ref->url "discord:guild/chan/123"))))
  (testing "unknown scheme -> nil"
    (is (nil? (prov/ref->url "ftp://nope"))))
  (testing "unconfigured repo (no :github) -> nil"
    (is (nil? (prov/ref->url (str "git:unknownrepo@" sha ":x.md#a"))))))
