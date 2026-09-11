(ns beverageops.advisor-test
  "Unit tests of `beverageops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [beverageops.advisor :as adv]
            [beverageops.store :as store]))

(def db (store/seed-db))

(deftest propose-service-record-shape
  (testing "service-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-service-record
                           :venue-id "venue-1"
                           :patch {:order "2x IPA" :tab "table-4"}})]
      (is (= :log-service-record (:op p)))
      (is (= "venue-1" (:venue-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :venue-id)))))

(deftest propose-staffing-operation-shape
  (testing "staffing-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staffing-operation
                           :venue-id "venue-2"
                           :patch {:role "barista" :shift "morning"}})]
      (is (= :schedule-staffing-operation (:op p)))
      (is (= "venue-2" (:venue-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :venue-id "venue-1"
                           :patch {:item "citrus mixers" :quantity 20 :estimated-cost 300}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= 300 (get-in p [:value :estimated-cost]))))))

(deftest propose-guest-safety-concern-shape
  (testing "guest-safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-guest-safety-concern
                           :venue-id "venue-1"
                           :patch {:concern "guest appears intoxicated"}})]
      (is (= :flag-guest-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-service-record :schedule-staffing-operation
                :coordinate-supply-order :flag-guest-safety-concern]]
      (let [p (adv/infer db {:op op :venue-id "venue-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-service-record :schedule-staffing-operation
                :coordinate-supply-order :flag-guest-safety-concern]]
      (let [p (adv/infer db {:op op :venue-id "venue-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest out-of-scope-hook-injects-rsa-decision-phrase
  (testing "the :out-of-scope? test hook makes the rationale contain an RSA-decision phrase"
    (let [p (adv/infer db {:op :log-service-record :venue-id "venue-1"
                           :out-of-scope? true :patch {}})]
      (is (re-find #"continue serving" (:rationale p))))))
