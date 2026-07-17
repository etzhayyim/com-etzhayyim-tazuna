(ns tazuna.cells.teleop-session.test-state-machine
  "Tests for the tazuna 手綱 teleop_session state machine (ADR-2606042100). G3/G4/G10/N1.
  1:1 port of cells/test_state_machines.py. Exercises the transitions directly (.solve() raises at R0)."
  (:require [clojure.test :refer [deftest is]]
            [tazuna.cells.teleop-session.state-machine :as sm]))

(defn- wrap [cell-state] {"cell_state" cell-state})

;; ── R0 invariant: solve() raises ──
(deftest test-solve-raises-at-r0
  (is (thrown? clojure.lang.ExceptionInfo (sm/solve {}))))

;; ── N1 / G3: force-auth admission ──
(deftest test-verify-force-auth-refuses-unrepresentable-class
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/transition-verify-force-auth (wrap {"force_class" "weaponizable" "force_auth_ref" "x"})))))

(deftest test-verify-force-auth-requires-force-auth-ref
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/transition-verify-force-auth (wrap {"force_class" "soft-actuation" "force_auth_ref" ""})))))

(deftest test-verify-force-auth-passes-when-authorized
  (let [out (sm/transition-verify-force-auth (wrap {"force_class" "soft-actuation" "force_auth_ref" "forceauth:ok"}))]
    (is (= sm/phase-force-authorized (get-in out ["cell_state" "phase"])))
    (is (= "grant_built" (get out "next_node")))))

;; ── G4: no-server-key grant ──
(deftest test-build-grant-refuses-plaintext-secret
  (is (thrown? clojure.lang.ExceptionInfo (sm/transition-build-grant (wrap {"secret_ref" "hunter2"})))))

(deftest test-build-grant-is-server-keyless
  (let [out (sm/transition-build-grant (wrap {"secret_ref" "encref:com.etzhayyim.encrypted/tazuna-x"
                                              "force_auth_ref" "forceauth:ok"}))
        grant (get-in out ["cell_state" "payload" "grant"])]
    (is (= false (get grant "serverHeldKey")))
    (is (= true (get grant "onChainAnchor")))
    (is (= true (get grant "outwardGated")))))

;; ── G4 / G10: command relay ──
(deftest test-relay-refuses-server-signature
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/transition-relay-command (wrap {"command_kind" "move" "member_sig" "m" "server_sig" "s"})))))

(deftest test-relay-actuation-requires-member-sig
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/transition-relay-command (wrap {"command_kind" "move" "member_sig" ""})))))

(deftest test-relay-nominal-actuation-passes-dry-run
  (let [out (sm/transition-relay-command (wrap {"command_kind" "move" "member_sig" "m"
                                                "observed_latency_ms" 10 "latency_budget_ms" 150 "deadman_ms" 300}))
        cmd (get-in out ["cell_state" "payload" "command"])]
    (is (= "nominal" (get cmd "safeState")))
    (is (= true (get cmd "dryRun")))
    (is (= false (get cmd "serverSigned")))
    (is (= sm/phase-command-relayed (get-in out ["cell_state" "phase"])))))

(deftest test-relay-deadman-lapse-drops-to-safe-stop
  (let [out (sm/transition-relay-command (wrap {"command_kind" "move" "member_sig" "m"
                                                "elapsed_since_presence_ms" 999 "deadman_ms" 300}))
        cmd (get-in out ["cell_state" "payload" "command"])]
    (is (= "halt" (get cmd "kind")))
    (is (= "autonomy-fallback" (get cmd "safeState")))
    (is (= sm/phase-safe-stopped (get-in out ["cell_state" "phase"])))))

(deftest test-relay-estop-always-honoured
  (let [out (sm/transition-relay-command (wrap {"command_kind" "estop"}))]
    (is (= "estopped" (get-in out ["cell_state" "payload" "command" "safeState"])))))

;; ── G10 extension: link-quality hysteresis (satellite/high-jitter link) ──
;; Each tick threads the PREVIOUS returned cell_state forward (as the outer langgraph
;; loop would), so these tests chain transition-relay-command calls.

(deftest test-relay-single-latency-blip-does-not-resume-actuation
  (let [tick1 (sm/transition-relay-command
               (wrap {"command_kind" "move" "member_sig" "m"
                      "observed_latency_ms" 400 "latency_budget_ms" 150 "deadman_ms" 300}))
        cmd1 (get-in tick1 ["cell_state" "payload" "command"])
        tick2 (sm/transition-relay-command
               (wrap (assoc (get tick1 "cell_state") "observed_latency_ms" 10)))
        cmd2 (get-in tick2 ["cell_state" "payload" "command"])]
    (is (= "autonomy-fallback" (get cmd1 "safeState")))
    ;; a SINGLE in-budget sample right after the breach must not re-arm actuation.
    (is (= "autonomy-fallback" (get cmd2 "safeState")))
    (is (= sm/phase-safe-stopped (get-in tick2 ["cell_state" "phase"])))))

(deftest test-relay-resumes-nominal-after-consecutive-recovery-samples
  (let [tick1 (sm/transition-relay-command
               (wrap {"command_kind" "move" "member_sig" "m"
                      "observed_latency_ms" 400 "latency_budget_ms" 150
                      "deadman_ms" 300 "recovery_samples" 2}))
        tick2 (sm/transition-relay-command
               (wrap (assoc (get tick1 "cell_state") "observed_latency_ms" 10)))
        tick3 (sm/transition-relay-command
               (wrap (assoc (get tick2 "cell_state") "observed_latency_ms" 10)))
        cmd3 (get-in tick3 ["cell_state" "payload" "command"])]
    (is (= "nominal" (get cmd3 "safeState")))
    (is (= sm/phase-command-relayed (get-in tick3 ["cell_state" "phase"])))))

(deftest test-relay-deadman-lapse-unaffected-by-latency-hysteresis
  (let [tick1 (sm/transition-relay-command
               (wrap {"command_kind" "move" "member_sig" "m"
                      "elapsed_since_presence_ms" 999 "deadman_ms" 300}))
        tick2 (sm/transition-relay-command
               (wrap (assoc (get tick1 "cell_state")
                            "elapsed_since_presence_ms" 0 "observed_latency_ms" 10)))
        cmd2 (get-in tick2 ["cell_state" "payload" "command"])]
    ;; the deadman-lapsed tick never touched link-state, so the very next in-budget
    ;; sample resumes nominal immediately.
    (is (= "nominal" (get cmd2 "safeState")))))
