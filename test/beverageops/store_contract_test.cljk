(ns beverageops.store-contract-test
  "Contract tests for `beverageops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [beverageops.store :as store]))

(deftest mem-store-venue-lookup
  (testing "MemStore can store and retrieve venues by ID (string keys)"
    (let [venues {"v1" {:venue-id "v1" :name "The Anchor Pub" :registered? true :verified? true}}
          s (store/mem-store venues)]
      (is (some? (store/venue s "v1")))
      (is (nil? (store/venue s "v99"))))))

(deftest mem-store-all-venues
  (testing "MemStore returns all venues in sorted order"
    (let [venues {"v2" {:venue-id "v2" :name "Bob's Bar"}
                  "v1" {:venue-id "v1" :name "Alice's Cafe"}
                  "v3" {:venue-id "v3" :name "Carol's Lounge"}}
          s (store/mem-store venues)
          all-v (store/all-venues s)]
      (is (= 3 (count all-v)))
      (is (= "v1" (:venue-id (first all-v))))
      (is (= "v3" (:venue-id (last all-v)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-service-record :venue-id "v1" :value {:order "1x lager"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-venues
  (testing "MemStore with-venues replaces the venue directory"
    (let [s (store/mem-store {})
          new-venues {"v1" {:venue-id "v1" :name "Alice's Cafe"}}]
      (is (= 0 (count (store/all-venues s))))
      (store/with-venues s new-venues)
      (is (= 1 (count (store/all-venues s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo venues"
    (let [s (store/seed-db)]
      (is (> (count (store/all-venues s)) 0))
      (is (some? (store/venue s "venue-1")))
      (is (some? (store/venue s "venue-2")))
      (is (some? (store/venue s "venue-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for venue-id"
    (let [demo (store/demo-data)
          venues (:venues demo)]
      (doseq [[k v] venues]
        (is (string? k) "keys must be strings")
        (is (string? (:venue-id v)) "venue-id must be string")
        (is (= k (:venue-id v)) "key must match venue-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
