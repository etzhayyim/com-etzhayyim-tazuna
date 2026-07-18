(ns tazuna.methods.test-teleop-safety
  "Tests for the tazuna teleop-safety reasoner (ADR-2606042100).

  1:1 Clojure port of `20-actors/tazuna/methods/test_teleop_safety.py`.
  Stdlib + clojure.test only. Parametrized Python cases are expanded into
  separate `(is ...)` forms."
  (:require [clojure.test :refer [deftest is testing]]
            [tazuna.methods.teleop-safety :as sut]))

(def ^:private AUTHORIZED
  (sut/grant {:force-class "soft-actuation" :force-auth-ref "forceauth:sanae-soft-001"
              :deadman-ms 300 :latency-budget-ms 150}))

;; ── N1: force-class admission ────────────────────────────────────────────────
(deftest test-weaponizable-force-class-is-unrepresentable
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/admit-session (sut/grant {:force-class "weaponizable" :force-auth-ref "x"})))))

(deftest test-arbitrary-force-class-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/admit-session (sut/grant {:force-class "lethal" :force-auth-ref "x"})))))

(deftest test-admitted-force-classes-pass-observational
  (is (nil? (sut/admit-session (sut/grant {:force-class "observational" :force-auth-ref "forceauth:ok"})))))

(deftest test-admitted-force-classes-pass-soft-actuation
  (is (nil? (sut/admit-session (sut/grant {:force-class "soft-actuation" :force-auth-ref "forceauth:ok"})))))

(deftest test-admitted-force-classes-pass-powered-actuation
  (is (nil? (sut/admit-session (sut/grant {:force-class "powered-actuation" :force-auth-ref "forceauth:ok"})))))

;; ── G3: Transparent Force ────────────────────────────────────────────────────
(deftest test-grant-without-force-auth-ref-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/admit-session (sut/grant {:force-class "soft-actuation" :force-auth-ref ""})))))

(deftest test-actuation-refused-without-force-auth-even-if-signed
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/evaluate (sut/command "move" {:member-sig "m:sig"})
                             (sut/grant {:force-class "soft-actuation" :force-auth-ref ""})))))

;; ── G4: no-server-key ────────────────────────────────────────────────────────
(deftest test-server-signature-always-refused
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/evaluate (sut/command "move" {:member-sig "m:sig" :server-sig "s:sig"})
                             AUTHORIZED))))

(deftest test-actuation-requires-member-signature
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/evaluate (sut/command "move" {:member-sig ""})
                             AUTHORIZED))))

(deftest test-nominal-actuation-is-member-signed-and-passes
  (let [v (sut/evaluate (sut/command "move" {:member-sig "m:sig" :observed-latency-ms 40})
                        AUTHORIZED)]
    (is (= (:safe-state v) "nominal"))
    (is (= (:actuates v) true))
    (is (= (:effective-kind v) "move"))))

;; ── G10: soft-RT supervision ─────────────────────────────────────────────────
(deftest test-deadman-lapse-forces-autonomy-fallback-halt
  (let [v (sut/evaluate (sut/command "move" {:member-sig "m:sig" :elapsed-since-presence-ms 900})
                        AUTHORIZED)]
    (is (= (:safe-state v) "autonomy-fallback"))
    (is (= (:actuates v) false))
    (is (= (:effective-kind v) "halt"))
    (is (clojure.string/includes? (:reason v) "deadman"))))

(deftest test-latency-breach-forces-autonomy-fallback-halt
  (let [v (sut/evaluate (sut/command "move" {:member-sig "m:sig" :observed-latency-ms 400})
                        AUTHORIZED)]
    (is (= (:safe-state v) "autonomy-fallback"))
    (is (= (:actuates v) false))
    (is (= (:effective-kind v) "halt"))
    (is (clojure.string/includes? (:reason v) "latency"))))

(deftest test-deadman-takes-priority-over-latency
  (let [v (sut/evaluate (sut/command "move" {:member-sig "m:sig"
                                             :elapsed-since-presence-ms 900
                                             :observed-latency-ms 400})
                        AUTHORIZED)]
    (is (clojure.string/includes? (:reason v) "deadman"))))

(deftest test-estop-always-honoured-without-signature
  (let [v (sut/evaluate (sut/command "estop") AUTHORIZED)]
    (is (= (:safe-state v) "estopped"))
    (is (= (:actuates v) false))))

(deftest test-halt-and-handback-need-no-signature
  (is (= (:effective-kind (sut/evaluate (sut/command "halt") AUTHORIZED))
         "halt"))
  (is (= (:effective-kind (sut/evaluate (sut/command "handback") AUTHORIZED))
         "handback")))

(deftest test-estop-honoured-even-with-breached-supervision
  ;; an e-stop must work regardless of deadman/latency state
  (let [v (sut/evaluate (sut/command "estop" {:elapsed-since-presence-ms 99999})
                        AUTHORIZED)]
    (is (= (:safe-state v) "estopped"))))

;; ── G7: never live at R0 ─────────────────────────────────────────────────────
(deftest test-actuation-is-advisory-only-at-r0
  ;; evaluate() returns a verdict; it never performs a live actuation.
  ;; `actuates` is a permission flag the cell still routes through dry-run +
  ;; operator/Council gate (G7).
  (let [v (sut/evaluate (sut/command "manipulate" {:member-sig "m:sig" :observed-latency-ms 10})
                        AUTHORIZED)]
    (is (= (:actuates v) true))
    ;; ...but the lexicon pins dryRun const true and outwardGated const true;
    ;; no live call here.
    ))

(deftest test-unknown-command-kind-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/evaluate (sut/command "teleport" {:member-sig "m:sig"})
                             AUTHORIZED))))
