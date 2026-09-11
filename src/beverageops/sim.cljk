(ns beverageops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean service-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a staffing-operation scheduling request, a
  low-cost supply-order coordination (auto-commits clean at phase 3),
  then a HIGH-cost supply-order (above the governor's cost threshold ->
  always escalates even at phase 3), then a guest-safety-concern flag
  (ALWAYS escalates, at any phase -- approve, then commit), then
  HARD-hold scenarios: an unregistered venue, a venue registered but
  not yet license-verified, a proposal whose own `:effect` is not
  `:propose`, and a proposal that has drifted into directly finalizing
  a responsible-service-of-alcohol authority decision."
  (:require [langgraph.graph :as g]
            [beverageops.advisor :as advisor]
            [beverageops.store :as store]
            [beverageops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "venue-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :venue-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :venue-manager :phase 3}
        actor (op/build db)]

    (println "== log-service-record venue-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-service-record :venue-id "venue-1"
                                  :patch {:order "2x IPA, 1x sparkling water" :tab "table-4"}} manager-phase-1)]
      (println r)
      (println "-- human venue manager approves --")
      (println (approve! actor "t1")))

    (println "\n== log-service-record venue-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-service-record :venue-id "venue-1"
                                  :patch {:order "1x cold brew" :tab "table-7"}} manager-phase-3))

    (println "\n== schedule-staffing-operation venue-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-staffing-operation :venue-id "venue-1"
                                  :patch {:role "bartender" :shift "evening" :date "2026-07-20"}} manager-phase-3))

    (println "\n== coordinate-supply-order venue-1, low cost (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :venue-id "venue-1"
                                  :patch {:item "citrus mixers" :quantity 20 :estimated-cost 300}} manager-phase-3))

    (println "\n== coordinate-supply-order venue-1, HIGH cost (phase 3, always escalates above cost threshold) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :venue-id "venue-1"
                                 :patch {:item "premium spirits restock" :quantity 50 :estimated-cost 12000}} manager-phase-3)]
      (println r)
      (println "-- human venue manager reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-guest-safety-concern venue-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-guest-safety-concern :venue-id "venue-1"
                                 :patch {:concern "guest appears heavily intoxicated, staff considering cutting off service" :confidence 0.92}} manager-phase-3)]
      (println r)
      (println "-- human venue manager reviews & decides --")
      (println (approve! actor "t6")))

    (println "\n== log-service-record venue-99 (unregistered venue -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-service-record :venue-id "venue-99"
                                  :patch {:order "1x espresso"}} manager-phase-3))

    (println "\n== log-service-record venue-3 (registered but license-unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-service-record :venue-id "venue-3"
                                  :patch {:order "1x lager"}} manager-phase-3))

    (println "\n== schedule-staffing-operation venue-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-staffing-operation :venue-id "venue-1"
                                           :patch {:role "server" :shift "morning"}} manager-phase-3)))

    (println "\n== log-service-record venue-1, advisor drifts into finalizing an RSA decision -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :log-service-record :venue-id "venue-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
