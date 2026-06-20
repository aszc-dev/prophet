(ns prophet.mcp.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [prophet.mcp.http :as http]))

(deftest allowed-origin
  (testing "no allowlist configured => allow everything"
    (is (true? (http/allowed-origin? nil "https://evil.example")))
    (is (true? (http/allowed-origin? [] "https://evil.example"))))
  (testing "allowlist set"
    (is (true? (http/allowed-origin? ["https://app.example"] "https://app.example")))
    (is (false? (http/allowed-origin? ["https://app.example"] "https://evil.example"))))
  (testing "missing Origin passes (non-browser clients omit it)"
    (is (true? (http/allowed-origin? ["https://app.example"] nil)))))

(deftest authorized
  (testing "no token configured => open"
    (is (true? (http/authorized? nil "Bearer whatever")))
    (is (true? (http/authorized? "" nil))))
  (testing "token configured"
    (is (true? (http/authorized? "s3cret" "Bearer s3cret")))
    (is (false? (http/authorized? "s3cret" "Bearer wrong")))
    (is (false? (http/authorized? "s3cret" nil)))
    (is (false? (http/authorized? "s3cret" "s3cret")))))
