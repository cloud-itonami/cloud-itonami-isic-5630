(ns beverageops.governor-contract-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, and audit trail."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [beverageops.advisor :as advisor]
            [beverageops.store :as store]
            [beverageops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "manager"}} {:thread-id tid :resume? true}))

(deftest service-record-logging-full-flow
  (testing "clean service-record proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3}
          result (exec-request actor "t1"
                               {:op :log-service-record :venue-id "venue-1" :patch {:order "2x IPA"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest guest-safety-concern-always-escalates
  (testing ":flag-guest-safety-concern escalates for human approval, regardless of phase/confidence"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3}
          result (exec-request actor "t2"
                               {:op :flag-guest-safety-concern :venue-id "venue-1"
                                :patch {:concern "guest appears intoxicated" :confidence 0.99}}
                               ctx)]
      (is (some? result))
      ;; At this point the actor is paused for approval, not yet committed
      (is (= 0 (count (store/coordination-log db)))
          "guest safety concern must not auto-commit, must wait for approval")
      ;; Now approve it
      (resume-approval actor "t2" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest supply-order-above-cost-threshold-always-escalates
  (testing "a coordinate-supply-order above the cost threshold escalates for human approval, even at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2b" :phase 3}
          result (exec-request actor "t2b"
                               {:op :coordinate-supply-order :venue-id "venue-1"
                                :patch {:item "premium spirits restock" :estimated-cost 12000}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "above-threshold supply order must not auto-commit, must wait for approval")
      (resume-approval actor "t2b" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest supply-order-below-cost-threshold-auto-commits
  (testing "a coordinate-supply-order below the cost threshold auto-commits, clean, at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2c" :phase 3}]
      (exec-request actor "t2c"
                     {:op :coordinate-supply-order :venue-id "venue-1"
                      :patch {:item "citrus mixers" :estimated-cost 300}}
                     ctx)
      (is (> (count (store/coordination-log db)) 0)
          "below-threshold clean supply order must auto-commit at phase 3"))))

(deftest unregistered-venue-hard-hold
  (testing "unregistered venue -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3}]
      (exec-request actor "t3"
                     {:op :log-service-record :venue-id "unknown-venue"
                      :patch {:order "1x espresso"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "HARD hold must never commit"))))

(deftest unverified-venue-hard-hold
  (testing "registered but license-unverified venue -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3}
          result (exec-request actor "t4"
                               {:op :log-service-record :venue-id "venue-3"
                                :patch {:order "1x lager"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "license-unverified venue must HARD hold"))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-5" :phase 3}
          result (exec-request actor "t5"
                               {:op :log-service-record :venue-id "venue-1"
                                :patch {:order "2x IPA"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "non-:propose effect must HARD hold"))))

(deftest rsa-decision-content-hard-hold
  (testing "proposal drifting into finalizing an RSA authority decision -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-6" :phase 3}
          result (exec-request actor "t6"
                               {:op :log-service-record :venue-id "venue-1"
                                :out-of-scope? true  ; triggers RSA-decision content in advisor
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "RSA-decision content must HARD hold"))))

(deftest phase-1-approval-gate
  (testing "phase 1 approved request -> commits after human approval"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 1}]
      (exec-request actor "t7"
                     {:op :log-service-record :venue-id "venue-1"
                      :patch {:order "1x espresso"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "phase 1 must not auto-commit, requires approval")
      (resume-approval actor "t7" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, must commit")
      (is (some #(= :committed (:t %)) (store/ledger db))
          "committed fact must be logged after approval"))))

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-8" :phase 3}]
      (exec-request actor "t8a"
                     {:op :log-service-record :venue-id "venue-1" :patch {:order "2x IPA"}}
                     ctx)
      (exec-request actor "t8b"
                     {:op :log-service-record :venue-id "unknown" :patch {:order "1x lager"}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))
