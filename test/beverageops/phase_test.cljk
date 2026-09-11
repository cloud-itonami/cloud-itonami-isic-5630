(ns beverageops.phase-test
  "Unit tests of `beverageops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [beverageops.phase :as phase]))

(def clean-verdict {:hard? false :escalate? false})
(def low-conf-verdict {:hard? false :escalate? true})
(def hard-verdict {:hard? true :escalate? false})

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-service-record :schedule-staffing-operation
                :coordinate-supply-order :flag-guest-safety-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-service-record-only
  (testing "phase 1 allows only service-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-service-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-staffing-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-service-record :schedule-staffing-operation :coordinate-supply-order]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-safety ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-service-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-staffing-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :commit)]
      (is (= :commit disposition)))))

(deftest guest-safety-concern-holds-when-not-enabled
  (testing ":flag-guest-safety-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-guest-safety-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-guest-safety-concern yet"))))))

(deftest guest-safety-concern-escalates-when-enabled
  (testing ":flag-guest-safety-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-guest-safety-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate guest-safety concerns regardless of governor disposition"))))

(deftest guest-safety-concern-never-in-any-auto-set
  (testing "the guardrail invariant: :flag-guest-safety-concern is never a member of any phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-guest-safety-concern))
          (str "phase " ph " must never auto-commit flag-guest-safety-concern")))))

(deftest supply-order-escalation-from-governor-is-preserved
  (testing "phase gate never downgrades an escalate the governor already decided (e.g. cost-threshold) back to commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :escalate)]
      (is (= :escalate disposition)))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-service-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
