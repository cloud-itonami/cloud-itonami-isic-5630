(ns beverageops.governor
  "BeverageServiceGovernor -- the independent compliance layer that
  earns the BeverageServiceAdvisor the right to commit. The advisor has
  no notion of whether a venue is actually registered and license
  verified, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently
  drifted into finalizing a responsible-service-of-alcohol (RSA)
  authority decision, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (service-record logging, staffing-operation scheduling,
  supply-order coordination, guest-safety-concern flagging). It NEVER
  performs or authorizes:
    - a responsible-service-of-alcohol (RSA) authority decision, e.g.
      deciding to keep serving an intoxicated patron, or overriding an
      age-verification failure
    - direct equipment control (taps, POS terminals, access control)
    - any other action outside the closed four-op allowlist below

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Venue unverified         -- the target venue record must exist
                                   AND be independently confirmed
                                   `:registered?`/`:verified?` in the
                                   store before ANY proposal for it may
                                   commit or even escalate. Never trusts
                                   a proposal's own claim about the
                                   venue -- re-derived from the venue's
                                   own store record, the same 'ground
                                   truth, not self-report' discipline
                                   every sibling actor's own governor
                                   uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST
                                   be `:propose`. Any other effect
                                   value is, by construction, a
                                   claim to directly actuate/commit
                                   outside governance -- HARD block,
                                   not merely low-confidence.
    3. RSA-decision / scope     -- ANY proposal (regardless of op)
       exclusion                  whose op, rationale, summary,
                                   citations or draft value directly
                                   finalizes a responsible-service-of-
                                   alcohol authority decision (continue/
                                   resume/authorize serving an
                                   intoxicated patron; override, bypass
                                   or waive an age-verification/ID-check
                                   failure; serve a minor) is a HARD,
                                   PERMANENT block -- this actor's
                                   charter excludes that authority
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every proposal.
                                   An op outside the closed four-op
                                   allowlist is the SAME failure mode
                                   (an advisor proposing something it
                                   was never authorized to propose) and
                                   is folded into this same check.
                                   NOTE: merely *observing/flagging*
                                   suspected over-service or a suspected
                                   underage attempt (this actor's own
                                   `:flag-guest-safety-concern` op) is
                                   NOT a scope violation -- it is exactly
                                   the valid use case this op exists
                                   for. Only proposals that themselves
                                   FINALIZE the RSA decision trip this
                                   check; see `rsa-decision-terms` and
                                   `legitimate-safety-concern-is-not-
                                   scope-excluded` in the test suite.

  Two ESCALATE (SOFT) gates, either always requires a human:
    - `:flag-guest-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `beverageops.phase` independently agrees: this op
      is never a member of any phase's `:auto` set either -- two
      layers, not one.
    - `:coordinate-supply-order` above `supply-cost-threshold` --
      ALWAYS escalates to a human, regardless of confidence, so a
      large procurement commitment is never auto-committed silently."
  (:require [clojure.string :as str]
            [beverageops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Beverage/ingredient supply orders whose :estimated-cost exceeds this
  (in the venue's local currency units) always escalate to a human,
  regardless of confidence or how clean the proposal otherwise is."
  5000)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `rsa-decision-violations`)."
  #{:log-service-record :schedule-staffing-operation
    :coordinate-supply-order :flag-guest-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-guest-safety-concern})

(def rsa-decision-terms
  "Case-insensitive substrings that mark a proposal as directly
  FINALIZING a responsible-service-of-alcohol (RSA) authority decision
  -- continuing/resuming/authorizing service to an intoxicated patron,
  or overriding/bypassing/waiving an age-verification/ID-check failure.
  Scanned across the proposal's op/summary/rationale/cites/value, never
  trusting the advisor's own framing of its intent. Deliberately
  decision-phrase-based (multi-word), NOT bare nouns like 'intoxicated'
  or 'underage' alone -- this actor's own legitimate
  `:flag-guest-safety-concern` proposals must be able to use those
  words to describe an OBSERVATION without self-triggering this block;
  see `legitimate-safety-concern-is-not-scope-excluded` in the test
  suite."
  ["continue serving" "continue to serve" "keep serving" "keep pouring"
   "resume serving" "authorize service" "authorize continued service"
   "approve service to" "serve the intoxicated" "serve despite intoxication"
   "override age verification" "overriding the age verification"
   "bypass age verification" "waive age verification"
   "override the age check" "bypass id check" "waive id check"
   "override id verification" "ignore age verification"
   "responsible service of alcohol override" "rsa override"
   "finalize alcohol service decision" "alcohol service authority decision"
   "grant service exception" "authorize underage service"
   "serve a minor" "serve minors" "sell alcohol to a minor"
   "sell alcohol to minor" "提供継続" "提供を継続" "飲酒提供を継続"
   "年齢確認を無視" "年齢確認を無効" "未成年に提供" "未成年へ提供"])

;; ----------------------------- checks -----------------------------

(defn- venue-unverified-violations
  "The target venue must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:venue-id` claim without a store lookup."
  [{:keys [venue-id]} st]
  (let [v (store/venue st venue-id)]
    (when-not (and v (:registered? v) (:verified? v))
      [{:rule :venue-unverified
        :detail (str venue-id " は未登録または未検証（ライセンス未確認）の施設 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the RSA-decision scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- rsa-decision-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content directly finalizes a responsible-service-of-
  alcohol authority decision, regardless of confidence or how clean
  every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) rsa-decision-terms)
      [{:rule :rsa-decision-excluded
        :detail "提供継続の最終判断/年齢確認失敗の無効化など responsible-service-of-alcohol の当局判断に触れる提案は永久に禁止"}])))

(defn- supply-cost-exceeded?
  "True when a `:coordinate-supply-order` proposal's :estimated-cost
  (looked up from the proposal's :value, falling back to 0 when
  absent) exceeds `supply-cost-threshold`."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (> (get-in proposal [:value :estimated-cost] 0) supply-cost-threshold)))

(defn check
  "Censors a BeverageServiceAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [venue-id (or (:venue-id proposal) (:venue-id request))
        hard (into []
                   (concat (venue-unverified-violations {:venue-id venue-id} store)
                           (effect-not-propose-violations proposal)
                           (rsa-decision-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (supply-cost-exceeded? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :venue-id   (:venue-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
