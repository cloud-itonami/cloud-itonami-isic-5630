(ns beverageops.governor-test
  "Pure unit tests of `beverageops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [beverageops.governor :as gov]
            [beverageops.store :as store]))

(def venue-1 {:venue-id "venue-1" :name "The Anchor Pub" :registered? true :verified? true})
(def venue-3 {:venue-id "venue-3" :name "Neon Nights Lounge" :registered? true :verified? false})

(defn- clean-proposal [op venue-id]
  {:op op :venue-id venue-id :summary "s" :rationale "routine venue coordination"
   :cites [venue-id] :effect :propose :value {} :confidence 0.85})

(deftest venue-unregistered-is-hard
  (testing "no venue record at all -> HARD hold"
    (let [s (store/mem-store {"venue-1" venue-1})
          verdict (gov/check {} nil (clean-proposal :log-service-record "unknown-venue") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:venue-unverified} (map :rule (:violations verdict)))))))

(deftest venue-unverified-is-hard
  (testing "venue registered but license not yet verified -> HARD hold"
    (let [s (store/mem-store {"venue-3" venue-3})
          verdict (gov/check {} nil (clean-proposal :log-service-record "venue-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:venue-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"venue-1" venue-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "venue-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"venue-1" venue-1})
          verdict (gov/check {} nil (clean-proposal :override-age-verification "venue-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest continue-serving-intoxicated-content-is-hard-and-permanent
  (testing "a proposal whose rationale directly finalizes 'continue serving an intoxicated patron' is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"venue-1" venue-1})
          poisoned (assoc (clean-proposal :log-service-record "venue-1")
                          :rationale "decided to continue serving the intoxicated patron at table 4"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:rsa-decision-excluded} (map :rule (:violations verdict)))))))

(deftest override-age-verification-content-is-hard
  (testing "a proposal that overrides an age-verification failure is HARD-blocked, same as continue-serving"
    (let [s (store/mem-store {"venue-1" venue-1})
          poisoned (assoc (clean-proposal :log-service-record "venue-1")
                          :rationale "chose to override age verification failure and served the guest anyway"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:rsa-decision-excluded} (map :rule (:violations verdict)))))))

(deftest serve-minor-content-is-hard
  (testing "a proposal authorizing service to a minor is HARD-blocked"
    (let [s (store/mem-store {"venue-1" venue-1})
          poisoned (assoc (clean-proposal :schedule-staffing-operation "venue-1")
                          :summary "authorize underage service for the private event tonight")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:rsa-decision-excluded} (map :rule (:violations verdict)))))))

(deftest bypass-id-check-content-is-hard
  (testing "a proposal that bypasses an ID check is HARD-blocked"
    (let [s (store/mem-store {"venue-1" venue-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "venue-1")
                          :value {:decision "bypass id check for the group at the bar"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:rsa-decision-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging a suspected over-service/underage-attempt as a GUEST SAFETY CONCERN (not a decision to serve) never trips the RSA-decision block -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"venue-1" venue-1})
          concern (assoc (clean-proposal :flag-guest-safety-concern "venue-1")
                         :value {:concern "guest at table 4 appears heavily intoxicated and a second guest showed an ID that looked altered during an underage-attempt check"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :rsa-decision-excluded (:rule %)) (:violations verdict)))
          "raw observation content (intoxication/underage-attempt) is exactly what this op exists to surface"))))

(deftest supply-order-above-cost-threshold-always-escalates
  (testing "a coordinate-supply-order proposal above the cost threshold escalates regardless of confidence"
    (let [s (store/mem-store {"venue-1" venue-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "venue-1")
                           :value {:estimated-cost 12000}
                           :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest supply-order-below-cost-threshold-does-not-force-escalate
  (testing "a coordinate-supply-order proposal below the cost threshold, clean and high-confidence, is not forced to escalate on cost grounds"
    (let [s (store/mem-store {"venue-1" venue-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "venue-1")
                       :value {:estimated-cost 300}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

(deftest guest-safety-concern-always-high-stakes
  (testing ":flag-guest-safety-concern is always high-stakes/escalate regardless of confidence"
    (let [s (store/mem-store {"venue-1" venue-1})
          concern (assoc (clean-proposal :flag-guest-safety-concern "venue-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-confidence-escalates
  (testing "a clean proposal below the confidence floor escalates, not hard-holds"
    (let [s (store/mem-store {"venue-1" venue-1})
          low-conf (assoc (clean-proposal :log-service-record "venue-1") :confidence 0.2)
          verdict (gov/check {} nil low-conf s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))
