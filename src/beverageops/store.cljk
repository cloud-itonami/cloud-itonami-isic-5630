(ns beverageops.store
  "SSoT for the ISIC-5630 beverage-serving COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a beverage-serving
  venue (bar, pub, coffee shop, juice bar -- on-premise beverage service,
  ISIC 5630, distinct from beverage manufacturing): order/tab/inventory-draw
  service-record logging, staffing/prep shift scheduling, beverage/ingredient
  supply-order coordination, and guest-safety-concern flagging (over-service,
  underage-attempt, altercation). It NEVER directly finalizes a
  responsible-service-of-alcohol (RSA) authority decision -- deciding to keep
  serving an intoxicated patron, or overriding an age-verification failure --
  see `beverageops.governor`'s `rsa-decision-violations`, a HARD, permanent,
  un-overridable block. Nor does it control any equipment (taps, POS
  terminals, access control) directly.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `venues` directory keyed by `:venue-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified venue+license record must exist before ANY
  proposal for that venue may ever commit or escalate --
  `beverageops.governor`'s `venue-unverified-violations` re-derives this
  from the venue's own `:registered?`/`:verified?` fields, never from
  proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which venue a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (venue [s venue-id] "Registered venue record, or nil.
    Venue map: {:venue-id .. :name .. :registered? bool :verified? bool}.
    :registered? = the venue's business registration is on file;
    :verified? = its beverage-service license has been independently
    verified -- both are set only by a process OUTSIDE this actor.")
  (all-venues [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-venues [s venues] "replace/seed the venue directory (map venue-id->venue)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained venue directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:venues
   {"venue-1" {:venue-id "venue-1" :name "The Anchor Pub (full alcohol license)"
               :registered? true :verified? true}
    "venue-2" {:venue-id "venue-2" :name "Daybreak Coffee & Juice Bar (no alcohol license)"
               :registered? true :verified? true}
    "venue-3" {:venue-id "venue-3" :name "Neon Nights Lounge (license renewal in progress)"
               :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (venue [_ venue-id] (get-in @a [:venues venue-id]))
  (all-venues [_] (sort-by :venue-id (vals (:venues @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-venues [s venues] (when (seq venues) (swap! a assoc :venues venues)) s))

(defn seed-db
  "A MemStore seeded with the demo venue directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `venues` map (venue-id string ->
  venue map) -- the primary test/dev entry point. `venues` may be empty
  (an unregistered-everywhere store)."
  [venues]
  (->MemStore (atom {:venues (or venues {}) :ledger [] :coordination-log []})))
