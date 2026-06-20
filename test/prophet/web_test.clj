(ns prophet.web-test
  (:require [clojure.test :refer [deftest is]]
            [prophet.web.build :as web]))

(deftest provenance-to-url
  (is (= "https://github.com/slayerlabs/slayer/blob/abc123/public/data/datasety.json"
         (web/prov->url "git:slayer@abc123:public/data/datasety.json#llmzszl"))
      "git ref -> github blob at sha; non-line anchor dropped")
  (is (= "https://github.com/slayerlabs/slayer/blob/abc123/log.jsonl#L5"
         (web/prov->url "git:slayer@abc123:log.jsonl#L5"))
      "line anchor preserved")
  (is (= "https://slayer.fabryka.ai/x" (web/prov->url "https://slayer.fabryka.ai/x"))
      "http ref passes through")
  (is (nil? (web/prov->url "discord:slayer/trening/123")) "no public url for discord"))

(deftest permalink-is-id-based
  (is (= "/node/01ABC/" (web/permalink "01ABC"))))
