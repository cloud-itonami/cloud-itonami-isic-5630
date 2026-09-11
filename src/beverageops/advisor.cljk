(ns beverageops.advisor
  "BeverageServiceAdvisor -- the *contained intelligence node* for the
  ISIC-5630 beverage-serving operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: service-record logging (orders/tabs/inventory draws),
  staffing-operation scheduling, supply-order coordination
  (beverage/ingredient procurement), and guest-safety-concern flagging.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record
  and NEVER a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by `beverageops.governor`
  before anything touches the SSoT.

  This advisor NEVER drafts a proposal that directly finalizes a
  responsible-service-of-alcohol (RSA) authority decision -- deciding to
  keep serving an intoxicated patron, or overriding an age-verification
  failure -- those are permanently out of scope for this actor, not
  merely un-implemented. It also never controls equipment (taps, POS
  terminals, access control) directly. `beverageops.governor`'s
  `rsa-decision-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into an authority decision it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :venue-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the RSA-decision gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-service-record
  "Draft an order/tab/inventory-draw service-record log entry. Pure
  logging of observed service activity -- never a decision about
  whether service should continue."
  [_db {:keys [venue-id patch]}]
  {:op         :log-service-record
   :venue-id   venue-id
   :summary    (str venue-id " のサービス記録（注文/タブ/在庫消費）を記録: " (pr-str (keys patch)))
   :rationale  "注文・伝票・在庫消費の観察記録のみ。サービス提供の可否に関する判断は含まない。"
   :cites      [venue-id]
   :effect     :propose
   :value      (merge {:venue-id venue-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a shift/prep staffing-operation scheduling PROPOSAL only
  (never a binding assignment). Actual staffing finalization is always
  done by a human shift manager."
  [_db {:keys [venue-id patch]}]
  {:op         :schedule-staffing-operation
   :venue-id   venue-id
   :summary    (str venue-id " のシフト/仕込み予定を提案: " (pr-str (keys patch)))
   :rationale  "バー/カフェスタッフのシフト・仕込み割り当て提案のみ。確定は人間のシフト管理者が判断する。"
   :cites      [venue-id]
   :effect     :propose
   :value      (merge {:venue-id venue-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft a beverage/ingredient supply-order procurement PROPOSAL
  (never a binding purchase order). Carries an :estimated-cost the
  governor uses for its cost-threshold escalation check."
  [_db {:keys [venue-id patch]}]
  {:op         :coordinate-supply-order
   :venue-id   venue-id
   :summary    (str venue-id " の飲料/原材料の発注提案: " (pr-str (keys patch)))
   :rationale  "飲料・食材などの消耗品調達調整のみ。発注の最終承認は人間が行う。"
   :cites      [venue-id]
   :effect     :propose
   :value      (merge {:venue-id venue-id} patch)
   :confidence 0.90})

(defn- propose-guest-safety-concern
  "Surface a guest/venue safety concern (suspected over-service,
  suspected underage attempt, altercation, observed distress) for
  HUMAN triage. This op ALWAYS escalates in `beverageops.governor` --
  never auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real. Surfacing the OBSERVATION is
  this op's entire job; it never itself decides whether to continue
  serving or how to resolve an age-verification failure -- that
  decision is exactly what the human being escalated to must make."
  [_db {:keys [venue-id patch]}]
  {:op         :flag-guest-safety-concern
   :venue-id   venue-id
   :summary    (str venue-id " のゲスト安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "ゲストの安全に関する観察事実の報告（過剰提供の疑い/年齢確認失敗の疑い/もめごと等）。常に人間の確認・判断が必要。"
   :cites      [venue-id]
   :effect     :propose
   :value      (merge {:venue-id venue-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-service-record (propose-service-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-guest-safety-concern (propose-guest-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting an RSA-authority-decision phrase to
    ;; exercise the governor's RSA-decision block end-to-end. Must be
    ;; cleared before production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually decided to continue serving the intoxicated patron and override the age verification failure")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :venue-id (:venue-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
