(ns slayer-kb.mcp-test
  (:require [clojure.test :refer [deftest is]]
            [slayer-kb.mcp.server :as mcp]))

(deftest initialize-advertises-tools-capability
  (let [r (mcp/handle {:id 1 :method "initialize" :params {}})]
    (is (= "slayer-kb" (get-in r [:result :serverInfo :name])))
    (is (contains? (get-in r [:result :capabilities]) :tools))))

(deftest tools-list-shape
  (let [tools (get-in (mcp/handle {:id 2 :method "tools/list"}) [:result :tools])
        names (set (map :name tools))]
    (is (= #{"search" "get_node" "traverse" "neighbors" "whats_new"} names))
    (is (every? #(contains? % :inputSchema) tools))
    (is (not-any? #(contains? % :handler) tools) "internal handler not serialized")))

(deftest notifications-have-no-response
  (is (nil? (mcp/handle {:method "notifications/initialized"}))))

(deftest unknown-tool-is-rpc-error
  (let [r (mcp/handle {:id 3 :method "tools/call" :params {:name "nope" :arguments {}}})]
    (is (= -32602 (get-in r [:error :code])))))

(deftest unknown-method-is-rpc-error
  (is (= -32601 (get-in (mcp/handle {:id 4 :method "frobnicate"}) [:error :code]))))
