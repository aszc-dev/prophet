(ns prophet.mcp-test
  (:require [clojure.test :refer [deftest is testing]]
            [prophet.mcp.server :as mcp]))

(deftest initialize-advertises-tools-capability
  (let [r (mcp/handle {:id 1 :method "initialize" :params {}})]
    (is (= "prophet" (get-in r [:result :serverInfo :name])))
    (is (contains? (get-in r [:result :capabilities]) :tools))))

(deftest initialize-negotiates-protocol-version
  (testing "echoes a supported version the client requested"
    (is (= "2025-06-18"
           (get-in (mcp/handle {:id 1 :method "initialize"
                                :params {:protocolVersion "2025-06-18"}})
                   [:result :protocolVersion])))
    (is (= "2024-11-05"
           (get-in (mcp/handle {:id 1 :method "initialize"
                                :params {:protocolVersion "2024-11-05"}})
                   [:result :protocolVersion]))))
  (testing "falls back to latest for an unknown version"
    (is (= "2025-06-18"
           (get-in (mcp/handle {:id 1 :method "initialize"
                                :params {:protocolVersion "1999-01-01"}})
                   [:result :protocolVersion]))))
  (testing "never returns 2024-11-05 unless the client asked for it"
    (is (not= "2024-11-05"
              (get-in (mcp/handle {:id 1 :method "initialize" :params {}})
                      [:result :protocolVersion])))))

(deftest initialize-includes-onboarding-instructions
  (let [instr (get-in (mcp/handle {:id 1 :method "initialize" :params {}})
                      [:result :instructions])]
    (is (string? instr))
    (is (seq instr))
    (is (re-find #"search" instr))
    (is (re-find #"source_url" instr))))

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
