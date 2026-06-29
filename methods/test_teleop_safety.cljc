;; Clojure mirror of methods/test_teleop_safety.py — tazuna 手綱 teleop-safety reasoner.
(ns tazuna.methods.test-teleop-safety
  "1:1 port of the teleop_safety.py suite: N1 force-class admission, G3 Transparent
  Force, G4 no-server-key, G10 soft-RT supervision (deadman > latency priority),
  e-stop/halt/handback always honoured, G7 advisory-only, unknown-kind rejection.
  Errors are checked by the ex-info :error tag (≡ the Python exception subclass).

  Run:  bb --classpath 20-actors 20-actors/tazuna/methods/test_teleop_safety.clj"
  (:require [tazuna.methods.teleop-safety :as t]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def AUTHORIZED (t/grant {:force-class "soft-actuation" :force-auth-ref "forceauth:sanae-soft-001"
                          :deadman-ms 300 :latency-budget-ms 150}))

(defn- error-tag
  "Run f; return the :error tag of the ex-info it throws, or nil if it doesn't throw."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

;; ── N1: force-class admission ──
(deftest weaponizable-force-class-unrepresentable
  (is (= :force-class (error-tag #(t/admit-session (t/grant {:force-class "weaponizable" :force-auth-ref "x"}))))))

(deftest arbitrary-force-class-refused
  (is (= :force-class (error-tag #(t/admit-session (t/grant {:force-class "lethal" :force-auth-ref "x"}))))))

(deftest admitted-force-classes-pass
  (doseq [fc ["observational" "soft-actuation" "powered-actuation"]]
    (is (nil? (t/admit-session (t/grant {:force-class fc :force-auth-ref "forceauth:ok"}))))))

;; ── G3: Transparent Force ──
(deftest grant-without-force-auth-ref-refused
  (is (= :transparent-force (error-tag #(t/admit-session (t/grant {:force-class "soft-actuation" :force-auth-ref ""}))))))

(deftest actuation-refused-without-force-auth-even-if-signed
  (is (= :transparent-force
         (error-tag #(t/evaluate (t/command "move" {:member-sig "m:sig"})
                                 (t/grant {:force-class "soft-actuation" :force-auth-ref ""}))))))

;; ── G4: no-server-key ──
(deftest server-signature-always-refused
  (is (= :no-server-key
         (error-tag #(t/evaluate (t/command "move" {:member-sig "m:sig" :server-sig "s:sig"}) AUTHORIZED)))))

(deftest actuation-requires-member-signature
  (is (= :no-server-key (error-tag #(t/evaluate (t/command "move" {:member-sig ""}) AUTHORIZED)))))

(deftest nominal-actuation-passes
  (let [v (t/evaluate (t/command "move" {:member-sig "m:sig" :observed-latency-ms 40}) AUTHORIZED)]
    (is (= "nominal" (:safe-state v)))
    (is (true? (:actuates v)))
    (is (= "move" (:effective-kind v)))))

;; ── G10: soft-RT supervision ──
(deftest deadman-lapse-forces-autonomy-fallback-halt
  (let [v (t/evaluate (t/command "move" {:member-sig "m:sig" :elapsed-since-presence-ms 900}) AUTHORIZED)]
    (is (= "autonomy-fallback" (:safe-state v)))
    (is (false? (:actuates v)))
    (is (= "halt" (:effective-kind v)))
    (is (str/includes? (:reason v) "deadman"))))

(deftest latency-breach-forces-autonomy-fallback-halt
  (let [v (t/evaluate (t/command "move" {:member-sig "m:sig" :observed-latency-ms 400}) AUTHORIZED)]
    (is (= "autonomy-fallback" (:safe-state v)))
    (is (= "halt" (:effective-kind v)))
    (is (str/includes? (:reason v) "latency"))))

(deftest deadman-takes-priority-over-latency
  (let [v (t/evaluate (t/command "move" {:member-sig "m:sig" :elapsed-since-presence-ms 900
                                         :observed-latency-ms 400}) AUTHORIZED)]
    (is (str/includes? (:reason v) "deadman"))))

(deftest estop-always-honoured-without-signature
  (let [v (t/evaluate (t/command "estop") AUTHORIZED)]
    (is (= "estopped" (:safe-state v)))
    (is (false? (:actuates v)))))

(deftest halt-and-handback-need-no-signature
  (is (= "halt" (:effective-kind (t/evaluate (t/command "halt") AUTHORIZED))))
  (is (= "handback" (:effective-kind (t/evaluate (t/command "handback") AUTHORIZED)))))

(deftest estop-honoured-even-with-breached-supervision
  (is (= "estopped" (:safe-state (t/evaluate (t/command "estop" {:elapsed-since-presence-ms 99999}) AUTHORIZED)))))

;; ── G7 advisory-only + unknown kind ──
(deftest actuation-is-advisory-only-at-r0
  (is (true? (:actuates (t/evaluate (t/command "manipulate" {:member-sig "m:sig" :observed-latency-ms 10}) AUTHORIZED)))))

(deftest unknown-command-kind-rejected
  (is (= :value-error (error-tag #(t/evaluate (t/command "teleport" {:member-sig "m:sig"}) AUTHORIZED)))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'tazuna.methods.test-teleop-safety)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
