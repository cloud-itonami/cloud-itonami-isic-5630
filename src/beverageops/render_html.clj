(ns beverageops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-5630`: this
  repo previously had NO demo page and no generator at all.

  This namespace drives the REAL actor stack --
  `beverageops.operation` (the langgraph StateGraph) ->
  `beverageops.advisor` -> `beverageops.governor` ->
  `beverageops.phase` -> `beverageops.store` -- through a scenario that
  is a superset of this repo's own `beverageops.sim` demo driver
  (`clojure -M:dev:run`, which was run and read BEFORE this file was
  written: its venue ids `venue-1`/`venue-3`/`venue-99` do line up with
  `beverageops.store/demo-data`, so it was safe to build on).

  EVERY id, number, rule, violation detail, verdict and approver on the
  generated page is read back out of that run's own actor/store output.
  Nothing on the page is hand-typed sample data. Where a value cannot
  be obtained from the store, the page SAYS SO rather than inventing it
  -- see `approver-disclosure`, which walks both registers at render
  time instead of asserting a fixed claim about what the store keeps.

  Determinism: no timestamps, no wall-clock, no random ids, no set
  iteration order leaking into output (every set is sorted before it is
  printed). Two consecutive runs against the same seed are
  byte-identical -- verify by rendering twice into two scratch
  directories and diffing.

  Build-time invariant: `-main` REFUSES to write the page unless the
  run produced at least one hold AND at least one hold carrying a
  non-empty governor violation. A phase-gating hold
  (`:phase-reason :phase-disabled`) carries an EMPTY `:violations`
  vector, so counting holds alone would be satisfied by a run in which
  the governor never actually refused anything -- hence the two stages.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [beverageops.advisor :as advisor]
            [beverageops.governor :as governor]
            [beverageops.operation :as op]
            [beverageops.phase :as phase]
            [beverageops.store :as store]))

;; ============================== the run ==============================

(defn- ctx
  "Operator context injected into the actor for one request."
  [ph]
  {:actor-id "mgr-1" :actor-role :venue-manager :phase ph})

(defn- fixed-advisor
  "An advisor that returns `p` verbatim -- used to reproduce the two
  failure modes a *compromised or confused* advisor would produce
  (a proposal claiming direct actuation, and a proposal for an op
  outside the closed allowlist). The governor must catch both without
  any cooperation from the advisor."
  [p]
  (reify advisor/Advisor
    (-advise [_ _store _request] p)))

(defn- patched-advisor
  "Wraps the repo's own mock advisor and applies `f` to its proposal."
  [f]
  (reify advisor/Advisor
    (-advise [_ _store request] (f (advisor/infer nil request)))))

(defn- exec!
  "One coordination request through the actor. Returns langgraph's
  `{:state .. :events ..}`."
  [actor tid request ph]
  (g/run* actor {:request request :context (ctx ph)} {:thread-id tid}))

(defn- resume!
  "The human operator resumes a paused (interrupt-before
  :request-approval) actor with a real decision."
  [actor tid status by]
  (g/run* actor {:approval {:status status :by by}}
          {:thread-id tid :resume? true}))

(defn- scenario
  "Runs one scenario end to end and captures ONLY real actor output.
  `:decision`/`:approver` are what this driver actually handed the
  paused actor -- the page never claims an approver the run did not
  supply, and never reads one back out of thin air."
  [{:keys [actor id label phase request decision approver]}]
  (let [first-run (exec! actor id request phase)
        resumed   (when decision (resume! actor id decision approver))]
    {:id id :label label :phase phase :request request
     :decision decision :offered-approver approver
     :paused-state (:state first-run)
     :final-state  (:state (or resumed first-run))}))

(defn run-demo!
  "Drives a freshly seeded store through 16 coordination requests that
  between them reach every disposition this actor can produce:

    committed straight through (phase-3 auto),
    committed after a human approved a phase-gated write,
    committed after a human approved an ALWAYS-escalating op,
    committed after a human approved a low-confidence proposal,
    HELD because a human REJECTED it,
    HELD by the rollout phase gate before the governor's verdict mattered,
    HARD-HELD by the governor with no human ever seeing it (4 distinct rules).

  Returns `{:db <store> :runs [<scenario> ..]}`; every table on the
  console is a projection of these two values."
  []
  (let [db     (store/seed-db)
        actor  (op/build db)
        ;; a compromised advisor claiming direct actuation instead of a proposal
        actor-actuating (op/build db {:advisor (patched-advisor #(assoc % :effect :commit))})
        ;; a drifting advisor proposing an op outside the closed allowlist
        actor-off-charter
        (op/build db {:advisor (fixed-advisor
                                {:op :adjust-drink-pricing
                                 :venue-id "venue-1"
                                 :summary "venue-1 のドリンク価格改定を提案"
                                 :rationale "原価上昇に伴う価格改定の提案。"
                                 :cites ["venue-1"]
                                 :effect :propose
                                 :value {:venue-id "venue-1" :item "house pour" :new-price 900}
                                 :confidence 0.91})})
        ;; the same mock advisor, but unsure of itself -- trips the confidence floor
        actor-unsure (op/build db {:advisor (patched-advisor #(assoc % :confidence 0.42))})
        specs
        [{:actor actor :id "s01" :phase 3
          :label "phase-3 サービス記録 -- governor clean, auto-commit"
          :request {:op :log-service-record :venue-id "venue-1"
                    :patch {:order "2x IPA, 1x sparkling water" :tab "table-4"}}}

         {:actor actor :id "s02" :phase 1
          :label "phase-1 サービス記録 -- rollout gate が承認を要求"
          :request {:op :log-service-record :venue-id "venue-2"
                    :patch {:order "1x cold brew" :tab "counter-2"}}
          :decision :approved :approver "venue-manager-1"}

         {:actor actor :id "s03" :phase 3
          :label "phase-3 シフト提案 -- governor clean, auto-commit"
          :request {:op :schedule-staffing-operation :venue-id "venue-2"
                    :patch {:role "barista" :shift "morning" :date "2026-07-20"}}}

         {:actor actor :id "s04" :phase 3
          :label "phase-3 発注 (少額) -- cost threshold 未満, auto-commit"
          :request {:op :coordinate-supply-order :venue-id "venue-1"
                    :patch {:item "citrus mixers" :quantity 20 :estimated-cost 300}}}

         {:actor actor :id "s05" :phase 3
          :label "phase-3 発注 (高額) -- cost threshold 超過で必ず人間へ"
          :request {:op :coordinate-supply-order :venue-id "venue-1"
                    :patch {:item "premium spirits restock" :quantity 50 :estimated-cost 12000}}
          :decision :approved :approver "venue-manager-1"}

         {:actor actor :id "s06" :phase 3
          :label "phase-3 ゲスト安全懸念 -- 常に人間へ (承認)"
          :request {:op :flag-guest-safety-concern :venue-id "venue-2"
                    :patch {:concern "guest reports feeling unwell after a hot drink"
                            :confidence 0.9}}
          :decision :approved :approver "venue-manager-1"}

         {:actor actor :id "s07" :phase 3
          :label "phase-3 ゲスト安全懸念 -- 人間が却下 (HOLD)"
          :request {:op :flag-guest-safety-concern :venue-id "venue-1"
                    :patch {:concern "suspected over-service at table-9, staff want a second opinion"
                            :confidence 0.92}}
          :decision :rejected :approver "venue-manager-2"}

         {:actor actor-unsure :id "s08" :phase 3
          :label "phase-3 サービス記録 -- confidence floor 未満で人間へ (承認)"
          :request {:op :log-service-record :venue-id "venue-1"
                    :patch {:order "unclear handwriting on the ticket" :tab "table-2"}}
          :decision :approved :approver "venue-manager-1"}

         {:actor actor :id "s09" :phase 2
          :label "phase-2 発注 -- 書き込みは可だが auto 対象外, 人間へ (承認)"
          :request {:op :coordinate-supply-order :venue-id "venue-2"
                    :patch {:item "oat milk" :quantity 36 :estimated-cost 480}}
          :decision :approved :approver "venue-manager-1"}

         {:actor actor :id "s10" :phase 0
          :label "phase-0 read-only -- rollout gate が書き込み自体を止める"
          :request {:op :log-service-record :venue-id "venue-1"
                    :patch {:order "1x espresso" :tab "counter-1"}}}

         {:actor actor :id "s11" :phase 1
          :label "phase-1 発注 -- この phase では未解禁 (rollout gate HOLD)"
          :request {:op :coordinate-supply-order :venue-id "venue-1"
                    :patch {:item "tonic water" :quantity 24 :estimated-cost 120}}}

         {:actor actor :id "s12" :phase 3
          :label "未登録の施設 -- HARD hold, 人間に到達しない"
          :request {:op :log-service-record :venue-id "venue-99"
                    :patch {:order "1x espresso"}}}

         {:actor actor :id "s13" :phase 3
          :label "ライセンス未検証の施設 -- HARD hold, 人間に到達しない"
          :request {:op :coordinate-supply-order :venue-id "venue-3"
                    :patch {:item "draft lager keg" :quantity 2 :estimated-cost 400}}}

         {:actor actor-actuating :id "s14" :phase 3
          :label "advisor が直接実行を主張 (:effect :commit) -- HARD hold"
          :request {:op :schedule-staffing-operation :venue-id "venue-1"
                    :patch {:role "server" :shift "evening"}}}

         {:actor actor :id "s15" :phase 3
          :label "advisor が RSA 当局判断へ逸脱 -- HARD hold, 永久"
          :request {:op :log-service-record :venue-id "venue-1"
                    :out-of-scope? true :patch {:tab "table-9"}}}

         {:actor actor-off-charter :id "s16" :phase 3
          :label "allowlist 外の操作を提案 -- HARD hold"
          :request {:op :adjust-drink-pricing :venue-id "venue-1"
                    :patch {:item "house pour" :new-price 900}}}]]
    {:db db :runs (mapv scenario specs)}))

;; ========================= fact classification =========================
;;
;; Three different things all surface as "held" and MUST NOT be blurred
;; together: the governor refusing on a rule, the rollout phase gate
;; withholding a write that is otherwise clean, and a human saying no.

(defn- hard-refusal?
  "A governor HARD refusal: a `:governor-hold` fact that carries at
  least one real violation and was NOT produced by the phase gate."
  [f]
  (and (= :governor-hold (:t f))
       (nil? (:phase-reason f))
       (boolean (seq (:violations f)))))

(defn- rollout-gate-hold?
  "The rollout phase gate withheld a write. `:violations` is EMPTY here
  -- the governor had nothing against it."
  [f]
  (and (= :governor-hold (:t f)) (some? (:phase-reason f))))

(defn- human-rejection? [f] (= :approval-rejected (:t f)))

(defn- committed? [f] (= :committed (:t f)))

;; ========================= approver attribution =========================
;;
;; MEASURED, not assumed. Different repos in this fleet keep the
;; approver in different places (or drop it); a hard-coded note about
;; "this store loses approvers" becomes a lie the day someone fixes the
;; store. So probe both registers for an approver under any of the keys
;; a scaffold in this fleet has been seen to use, and describe whatever
;; is actually there.

(def ^:private approver-paths
  [[:approved-by] [:approver] [:by]
   [:payload :approved-by] [:payload :approver] [:payload :by]
   [:value :approved-by] [:value :approver] [:value :by]])

(defn- approver-in
  "First `[path value]` at which an approver is actually present in
  `m`, or nil. Never guesses -- only reports keys that exist."
  [m]
  (some (fn [p] (when-let [v (get-in m p)] [p v])) approver-paths))

(defn- path->str [p] (str/join "/" (map (comp str symbol) p)))

;; ============================== escaping ==============================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- ops->str
  "Sorted so set iteration order can never make the page
  non-deterministic."
  [s]
  (if (seq s) (str/join ", " (sort (map name s))) "—"))

(defn- yes-no [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"err\">no</span>"))

;; ============================== markup ==============================

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n      <tbody>\n"
       (if (seq rows)
         (str/join "\n" rows)
         (str "        <tr><td colspan=\"" (count headers)
              "\" class=\"muted\">この実行では 0 件</td></tr>"))
       "\n      </tbody>\n    </table>\n"))

(defn- section [title note body]
  (str "  <section class=\"card\">\n    <h2>" title "</h2>\n"
       (when note (str "    <p class=\"muted\">" note "</p>\n"))
       body
       "  </section>\n"))

;; ============================== cells ==============================

(defn- terminal-fact
  "The last decision fact this run's own audit trail produced."
  [state]
  (last (filter #(#{:committed :governor-hold :approval-rejected} (:t %))
                (:audit state))))

(defn- outcome-cell [{:keys [final-state]}]
  (let [f (terminal-fact final-state)]
    (cond
      (nil? f) "<span class=\"muted\">終端事実なし</span>"
      (committed? f) "<span class=\"ok\">committed</span>"
      (human-rejection? f) "<span class=\"warn\">人間が却下 → HOLD</span>"
      (rollout-gate-hold? f)
      (str "<span class=\"warn\">rollout gate HOLD · " (esc (kw (:phase-reason f))) "</span>")
      (hard-refusal? f)
      (str "<span class=\"critical\">HARD hold · "
           (esc (str/join ", " (map (comp kw :rule) (:violations f)))) "</span>")
      :else (str "<span class=\"muted\">" (esc (kw (:t f))) "</span>"))))

(defn- verdict-cell [{:keys [paused-state]}]
  (let [{:keys [ok? hard? escalate? high-stakes? confidence violations]} (:verdict paused-state)]
    (str (cond hard? "<span class=\"critical\">hard</span>"
               ok? "<span class=\"ok\">clean</span>"
               escalate? "<span class=\"warn\">escalate</span>"
               :else "<span class=\"muted\">—</span>")
         (when high-stakes? " · <span class=\"warn\">high-stakes</span>")
         " · conf " (esc confidence)
         (when (seq violations)
           (str " · " (esc (str/join ", " (map (comp kw :rule) violations))))))))

(defn- human-cell
  "What a human was actually asked and actually answered -- or that no
  human was ever involved."
  [{:keys [paused-state decision offered-approver]}]
  (let [asked? (= :escalate (:disposition paused-state))]
    (cond
      (and asked? (= :approved decision))
      (str "<span class=\"ok\">承認</span> · " (code offered-approver))
      (and asked? (= :rejected decision))
      (str "<span class=\"err\">却下</span> · " (code offered-approver))
      asked? "<span class=\"warn\">承認待ち</span>"
      :else "<span class=\"muted\">人間に到達せず</span>")))

;; ============================== sections ==============================

(defn- venues-section [db]
  (section
   "登録施設ディレクトリ (SSoT)"
   (str "governor の <code>venue-unverified</code> HARD check は、提案が自称する施設 id ではなく "
        "この表の <code>:registered?</code> / <code>:verified?</code> を毎回引き直す。"
        "両方 true でない施設への提案は commit も escalate もできない。")
   (table ["Venue id" "Name" "registered?" "verified?" "提案を進められるか"]
          (for [v (store/all-venues db)]
            (row (code (:venue-id v))
                 (esc (:name v))
                 (yes-no (:registered? v))
                 (yes-no (:verified? v))
                 (if (and (:registered? v) (:verified? v))
                   "<span class=\"ok\">可</span>"
                   "<span class=\"critical\">不可 (HARD)</span>"))))))

(defn- phase-section []
  (section
   "ロールアウト phase ladder"
   (str "この実行の既定 phase は <code>" (esc phase/default-phase) "</code>。"
        "phase gate は governor の判断を強める方向にしか働かない —— clean な提案を止めることはあっても、"
        "governor が止めたものを通すことはない。"
        "<code>:flag-guest-safety-concern</code> はどの phase の <code>:auto</code> にも入っていない "
        "(phase 3 を含む) —— これはロールアウトの未達項目ではなく恒久的な構造。")
   (table ["Phase" "Label" "書き込み可能な op" "自動 commit 可能な op"]
          (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
            (row (esc p) (esc label) (code (ops->str writes)) (code (ops->str auto)))))))

(defn- governor-section []
  (section
   "BeverageServiceGovernor -- 実行時に効いている規則"
   (str "下の値は本ページ生成時に <code>beverageops.governor</code> / <code>beverageops.phase</code> の "
        "var から読み出したもの (転記ではない)。HARD check は人間の承認でも上書きできない。")
   (table ["規則" "種別" "実効値"]
          [(row "許可された op (closed allowlist)" "<span class=\"critical\">HARD</span>"
                (code (ops->str governor/allowed-ops)))
           (row "<code>:effect</code> は :propose のみ" "<span class=\"critical\">HARD</span>"
                "提案以外の effect は直接実行の主張として即 HOLD")
           (row "RSA 当局判断への抵触" "<span class=\"critical\">HARD</span>"
                (str (esc (count governor/rsa-decision-terms))
                     " 個の決定フレーズを op/summary/rationale/cites/value 全文に対して走査 (op を問わず無条件)"))
           (row "施設の登録・ライセンス検証" "<span class=\"critical\">HARD</span>"
                "store の施設レコードから毎回再導出 (提案の自称は信用しない)")
           (row "常に人間へ上げる op" "<span class=\"warn\">SOFT (escalate)</span>"
                (code (ops->str governor/always-escalate-ops)))
           (row "発注額のしきい値" "<span class=\"warn\">SOFT (escalate)</span>"
                (str (code (str "> " governor/supply-cost-threshold))
                     " の <code>:coordinate-supply-order</code> は confidence によらず必ず人間へ"))
           (row "confidence floor" "<span class=\"warn\">SOFT (escalate)</span>"
                (code (str "< " governor/confidence-floor)))])))

(defn- run-log-section [runs]
  (section
   "この実行の全リクエスト"
   (str (esc (count runs))
        " 件の coordination request を実 actor (intake → advise → govern → decide → commit|hold|approval) "
        "に通した結果。verdict 列は governor が返した判定そのもの。")
   (table ["#" "シナリオ" "Phase" "Op" "Venue" "Governor verdict" "人間" "結果"]
          (for [r runs]
            (row (code (:id r))
                 (esc (:label r))
                 (esc (:phase r))
                 (code (kw (:op (:request r))))
                 (code (:venue-id (:request r)))
                 (verdict-cell r)
                 (human-cell r)
                 (outcome-cell r))))))

(defn- hard-hold-section [ledger]
  (let [hs (filter hard-refusal? ledger)]
    (section
     "GOVERNOR HARD REFUSALS -- 人間に到達しなかった提案"
     (str (esc (count hs))
          " 件。これらは承認キューに載らない —— 誰も承認できないので、承認の有無に関わらず SSoT には書かれない。"
          "下の detail は governor が生成した文言そのもの。")
     (table ["Op" "Venue" "違反した規則" "governor の説明" "conf"]
            (for [f hs, v (:violations f)]
              (row (code (kw (:op f)))
                   (code (:venue-id f))
                   (str "<span class=\"critical\">" (esc (kw (:rule v))) "</span>")
                   (esc (:detail v))
                   (esc (:confidence f))))))))

(defn- rollout-gate-section [ledger]
  (let [hs (filter rollout-gate-hold? ledger)]
    (section
     "ROLLOUT GATE HOLDS -- governor の拒否とは別物"
     (str (esc (count hs))
          " 件。governor は何も咎めていない (<code>:violations</code> は空) が、"
          "その phase ではまだその op を書けない。"
          "この 2 つを 1 つの表に混ぜると「governor が拒否した件数」が水増しされるので分けてある —— "
          "本ページ生成時の invariant も、空 violations のこの種の HOLD だけでは満たされない。")
     (table ["Op" "Venue" "Phase" "gate の理由" "violations"]
            (for [f hs]
              (row (code (kw (:op f)))
                   (code (:venue-id f))
                   (esc (:phase f))
                   (str "<span class=\"warn\">" (esc (kw (:phase-reason f))) "</span>")
                   (if (seq (:violations f))
                     (esc (str/join ", " (map (comp kw :rule) (:violations f))))
                     "<span class=\"muted\">空 (governor は clean)</span>")))))))

(defn- human-decision-section [runs ledger]
  (let [escalated (filter #(= :escalate (:disposition (:paused-state %))) runs)
        rejections (filter human-rejection? ledger)]
    (section
     "人間の承認キューと決定"
     (str (esc (count escalated)) " 件が人間に上がり、うち "
          (esc (count rejections)) " 件が却下された。"
          "却下は governor の HARD 拒否とは別の事実として台帳に載る "
          "(<code>:approval-rejected</code>)。")
     (table ["#" "Op" "Venue" "上がった理由" "Phase" "conf" "人間の決定" "台帳に載った事実"]
            (for [r escalated
                  :let [req (last (filter #(= :approval-requested (:t %))
                                          (:audit (:paused-state r))))
                        f (terminal-fact (:final-state r))]]
              (row (code (:id r))
                   (code (kw (:op (:request r))))
                   (code (:venue-id (:request r)))
                   (code (kw (:reason req)))
                   (esc (:phase req))
                   (esc (:confidence req))
                   (human-cell r)
                   (code (kw (:t f)))))))))

(defn- records-section [db]
  (section
   "SSoT に書かれた coordination レコード"
   (str "commit ノードだけが SSoT に書く。承認者列は各レコードを実際に走査して見つかったキーを表示している "
        "(固定の前提を置いていない)。")
   (table ["Op" "Venue" "レコードの中身" "承認者" "承認者を保持しているキー"]
          (for [rec (store/coordination-log db)
                :let [[p v] (approver-in rec)]]
            (row (code (kw (:op rec)))
                 (code (:venue-id rec))
                 (esc (pr-str (dissoc (or (:value rec) {}) :venue-id)))
                 (if v (str "<span class=\"ok\">" (esc v) "</span>")
                     "<span class=\"muted\">なし (自動 commit)</span>")
                 (if p (code (path->str p)) "<span class=\"muted\">—</span>"))))))

(defn- approver-disclosure
  "DERIVED at render time by walking both registers. If the store is
  later changed to keep (or to drop) the approver, this paragraph
  changes with it -- it is not a fixed claim about the scaffold."
  [db runs]
  (let [records   (vec (store/coordination-log db))
        ledger    (vec (store/ledger db))
        approved  (filter #(and (= :approved (:decision %))
                                (= :escalate (:disposition (:paused-state %))))
                          runs)
        n-appr    (count approved)
        rec-app   (filter approver-in records)
        led-app   (filter approver-in ledger)
        n-rec     (count rec-app)
        n-led     (count led-app)
        rec-key   (some-> (first rec-app) approver-in first path->str)
        led-key   (some-> (first led-app) approver-in first path->str)]
    (section
     "承認者の帰属 -- 実測 (仮定していない)"
     "本ページ生成時に SSoT レコード列と追記専用台帳の両方を走査し、承認者キーが実際に在るかを数えた結果。"
     (str
      (table ["レジスタ" "件数" "承認者を保持しているレコード数" "キー"]
             [(row "人間が承認したリクエスト (この実行)" (esc n-appr) "—" "<span class=\"muted\">—</span>")
              (row (code "store/coordination-log") (esc (count records)) (esc n-rec)
                   (if rec-key (code rec-key) "<span class=\"muted\">見つからず</span>"))
              (row (code "store/ledger") (esc (count ledger)) (esc n-led)
                   (if led-key (code led-key) "<span class=\"muted\">見つからず</span>"))])
      "    <p>"
      (cond
        (zero? n-appr)
        "この実行では人間の承認経路を 1 件も通っていないため、承認者の保持は検査できていない。"

        (and (= n-rec n-appr) (zero? n-led))
        (str "承認された " n-appr " 件すべてについて、SSoT レコード側は承認者を "
             "<code>" (esc rec-key) "</code> に保持している。"
             "<strong>一方、追記専用台帳側は 1 件も保持していない</strong> —— "
             "台帳の <code>:committed</code> 事実は誰が承認したかを記録しないので、"
             "<em>台帳だけを見て「誰が承認したか」には答えられない</em>。"
             "この欠落を黙って省略すると「誰も承認していない」のか「記録が落ちている」のか読者が区別できないため、"
             "ここに明示する。本ページはギャップを埋めるためにレコード側と台帳側を突き合わせているが、"
             "op と venue-id での突き合わせは一意にならない "
             "(同じ op を同じ施設に対して複数回実行しているため) ので、承認者はレコード自身から読んでいる。")

        (and (= n-rec n-appr) (= n-led n-appr))
        (str "承認された " n-appr " 件すべてについて、SSoT レコード (<code>" (esc rec-key)
             "</code>) と追記専用台帳 (<code>" (esc led-key)
             "</code>) の両方が承認者を保持している。両レジスタとも「誰が承認したか」に単独で答えられる。")

        (and (zero? n-rec) (pos? n-led))
        (str "承認された " n-appr " 件について、<strong>SSoT レコードは承認者を落としている</strong>が、"
             "追記専用台帳 (<code>" (esc led-key) "</code>) には残っている。"
             "レコード単独では監査に答えられない。")

        (and (zero? n-rec) (zero? n-led))
        (str "承認された " n-appr
             " 件について、<strong>どちらのレジスタも承認者を保持していない</strong> —— "
             "この実行の出力からは「誰が承認したか」を復元できない。"
             "承認自体は起きているので、これは「承認者なし」ではなく<em>記録の欠落</em>である。")

        :else
        (str "承認された " n-appr " 件のうち、SSoT レコード側で承認者が確認できたのは " n-rec
             " 件、追記専用台帳側では " n-led " 件 —— 部分的な帰属。"
             "件数が一致しないので、承認経路によって保持のされ方が違う。"))
      "</p>\n"))))

(defn- ledger-section [ledger]
  (section
   "追記専用の決定事実台帳 (この実行の全件)"
   (str (esc (count ledger))
        " 件。commit ノードと hold ノードだけが書き込む。SSoT を変更しなかった提案も、"
        "変更した提案と同じ台帳に残る。")
   (table ["#" "事実" "Op" "Venue" "Disposition" "Basis" "Phase reason"]
          (map-indexed
           (fn [i f]
             (row (esc (inc i))
                  (let [c (cond (committed? f) "ok"
                                (hard-refusal? f) "critical"
                                :else "warn")]
                    (str "<span class=\"" c "\">" (esc (kw (:t f))) "</span>"))
                  (code (kw (:op f)))
                  (code (:venue-id f))
                  (esc (kw (:disposition f)))
                  (if (seq (:basis f))
                    (esc (str/join ", " (map kw (:basis f))))
                    "<span class=\"muted\">—</span>")
                  (if (:phase-reason f)
                    (esc (kw (:phase-reason f)))
                    "<span class=\"muted\">—</span>")))
           ledger))))

(defn- invariant-section [ledger]
  (let [hard (filter hard-refusal? ledger)
        gate (filter rollout-gate-hold? ledger)
        rej  (filter human-rejection? ledger)
        com  (filter committed? ledger)
        rules (sort (distinct (map (comp kw :rule) (mapcat :violations hard))))]
    (section
     "このページのビルド時 invariant"
     (str "<code>beverageops.render-html/-main</code> は下の 2 段階を満たさない実行結果を"
          "<strong>書き出さずに例外で落ちる</strong>。1 段階目 (HOLD が 1 件以上) だけでは不十分 —— "
          "phase gate による HOLD は <code>:violations</code> が空なので、"
          "governor が一度も拒否していない実行でも 1 段階目は通ってしまう。")
     (table ["測定した値" "この実行" "要求"]
            [(row "HOLD 全件 (stage 1)" (esc (+ (count hard) (count gate) (count rej))) "≥ 1")
             (row "非空 violation を伴う governor HARD 拒否 (stage 2)"
                  (str "<span class=\"ok\">" (esc (count hard)) "</span>") "≥ 1")
             (row "HARD 拒否の相異なる規則" (code (str/join ", " rules))
                  (esc (str (count rules) " 種")))
             (row "rollout gate による HOLD (stage 2 には数えない)" (esc (count gate)) "—")
             (row "人間による却下 (stage 2 には数えない)" (esc (count rej)) "—")
             (row "SSoT に commit された件数" (esc (count com)) "≥ 1 (承認経路の実証)")]))))

;; ============================== document ==============================

(defn render
  "Renders the whole console from a store `db` and the scenario runs
  that produced it. Pure projection -- reads nothing but those two."
  [db runs]
  (let [ledger (vec (store/ledger db))]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-5630 · beverage service operations — Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>飲食店（飲料提供）業務コーディネーション (ISIC 5630) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · "
     "responsible-service-of-alcohol の当局判断は永久に対象外</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>このページについて</h2>\n"
     "    <p>ビルド時に <code>clojure -M:dev:render-html</code> が実 actor スタック "
     "(<code>beverageops.operation</code> → <code>beverageops.advisor</code> → "
     "<code>beverageops.governor</code> → <code>beverageops.phase</code> → "
     "<code>beverageops.store</code>) を実際に走らせ、その出力だけを描画した静的成果物。"
     "id・数値・違反理由・承認者はすべてその実行の actor / store 出力から読み出しており、"
     "手入力のサンプル値は 1 つも含まない。store から取れない値は書かずに"
     "「取れない」と明示する (下の「承認者の帰属」節)。</p>\n"
     "    <p class=\"muted\">決定的: 本文に時刻を持たず、同じシードからの再生成は byte 一致する。</p>\n"
     "  </section>\n"
     (venues-section db)
     (governor-section)
     (phase-section)
     (run-log-section runs)
     (hard-hold-section ledger)
     (rollout-gate-section ledger)
     (human-decision-section runs ledger)
     (records-section db)
     (approver-disclosure db runs)
     (ledger-section ledger)
     (invariant-section ledger)
     "</main>\n"
     "<footer>\n"
     "  <p>生成元: <code>beverageops.render-html</code> · シード: "
     "<code>beverageops.store/demo-data</code> · "
     (esc (count runs)) " requests · "
     (esc (count ledger)) " ledger facts · "
     (esc (count (store/coordination-log db))) " committed records.</p>\n"
     "  <p>この actor は業務コーディネーション "
     "(サービス記録・シフト提案・発注調整・ゲスト安全懸念の起票) のみを扱う。"
     "提供継続の可否や年齢確認失敗の無効化といった responsible-service-of-alcohol の当局判断、"
     "および設備 (タップ・POS・入退室) の直接制御は、ロールアウトの未達項目ではなく"
     "構造的に対象外である。</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ============================== entry point ==============================

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs]} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(#{:governor-hold :approval-rejected} (:t %)) ledger)
        hard  (filterv hard-refusal? ledger)
        commits (filterv committed? ledger)]
    ;; Build-time invariant, stage 1: the scenario must actually hold something.
    (when (empty? holds)
      (throw (ex-info "render-html: the scenario produced ZERO holds -- refusing to write a console that shows only happy paths"
                      {:ledger-facts (count ledger) :holds 0})))
    ;; Stage 2: at least one of those holds must be a real governor refusal
    ;; carrying a non-empty violation. A phase-gating hold has an EMPTY
    ;; :violations vector and would satisfy stage 1 on its own.
    (when (empty? hard)
      (throw (ex-info "render-html: holds exist but NONE carries a non-empty governor violation (phase-gating holds do not count) -- refusing to write"
                      {:holds (count holds)
                       :hold-kinds (frequencies (map :t holds))
                       :hard-refusals 0})))
    (when (empty? commits)
      (throw (ex-info "render-html: the scenario produced ZERO commits -- the page must show an approved path as well as a refused one"
                      {:ledger-facts (count ledger) :commits 0})))
    (io/make-parents out)
    (spit out (render db runs))
    (println "wrote" out
             (str "(" (count runs) " requests, "
                  (count ledger) " ledger facts, "
                  (count hard) " HARD governor refusals over "
                  (count (distinct (map (comp :rule) (mapcat :violations hard)))) " distinct rules, "
                  (count commits) " commits)"))))
