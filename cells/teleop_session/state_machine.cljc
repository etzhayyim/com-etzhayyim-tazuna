(ns tazuna.cells.teleop-session.state-machine
  "Phase state machine for the tazuna 手綱 teleop_session (levi) cell.
  1:1 port of cells/teleop_session/state_machine.py (ADR-2606042100).

  The defining, safety-critical tazuna skill: admit a remote teleoperation session over a physical
  robot ONLY as Transparent Force and ONLY under no-server-key, then relay member-signed actuation
  commands while a deadman / e-stop / latency budget continuously guards a safe-stop. Pure,
  unit-tested transitions; the cell's .solve() raises until Council activation.

  Invariants enforced (the load-bearing four):
    G3 — Transparent Force: no grant without a force-authorization reference (1 SBT=1 vote).
    G4 — no-server-key: serverHeldKey=false + an encrypted-envelope REFERENCE only; every actuation
         command is member-signed and a server signature is refused (ADR-2605231525).
    G10 — soft-RT supervision: a deadman lapse / latency-budget breach forces a safe-stop /
          autonomy-fallback; an e-stop is always honoured. NOT a certified safety system.
          Latency recovery is link-quality-hysteresis-gated (see `safe-state`): a breach
          trips fallback instantly (fail-fast) but resuming nominal actuation requires
          `recovery_samples` CONSECUTIVE in-budget samples (fail-safe against flapping
          actuation on/off across a single lucky sample on a jitter-prone link, e.g. a
          satellite/Starlink-class relay with periodic beam-handoff spikes). Deadman/
          e-stop stay instant, unaffected by this hysteresis.
    N1  — force class: :weaponizable is unrepresentable; only the three admitted classes pass.

  Conventions: dataclass SessionState → a plain map with the SAME string field keys the Python
  `cs.__dict__` round-trips; phase enum value identities stay strings; ValueError → ex-info."
  (:require [clojure.string :as str]))

(def member "member")
(def encref-prefix "encref:")
(def admitted-force-classes #{"observational" "soft-actuation" "powered-actuation"})
(def always-permitted #{"halt" "estop" "handback"})   ; safety commands never gated

;; ── SessionPhase (enum — Python value identities preserved) ──
(def session-phases
  {:init             "init"
   :force-authorized "force_authorized"
   :grant-built      "grant_built"
   :command-relayed  "command_relayed"
   :safe-stopped     "safe_stopped"})

(def phase-init             (:init session-phases))
(def phase-force-authorized (:force-authorized session-phases))
(def phase-grant-built      (:grant-built session-phases))
(def phase-command-relayed  (:command-relayed session-phases))
(def phase-safe-stopped     (:safe-stopped session-phases))

;; ── SessionState (dataclass → plain map, string keys + field defaults) ──
(def state-defaults
  {"phase"                     phase-init
   "robot_id"                  "sanae-saotome-01"
   "principal"                 member               ; G4: always the member
   "force_class"               "soft-actuation"
   "force_auth_ref"            ""                   ; G3: required to build a grant
   "server_held_key"          false                ; G4: always false
   "secret_ref"                "encref:com.etzhayyim.encrypted/tazuna-session"
   "deadman_ms"                300
   "latency_budget_ms"         150
   "recovery_samples"          3                    ; G10: consecutive in-budget samples to clear fallback
   "command_kind"              "move"
   "member_sig"                ""
   "server_sig"                ""                   ; G4: must remain empty
   "elapsed_since_presence_ms" 0                    ; G10: deadman input
   "observed_latency_ms"       0                    ; G10: latency input
   "link_fallback_active"      false                ; G10: persisted link-hysteresis bookkeeping
   "latency_recovery_count"    0                    ; G10: persisted link-hysteresis bookkeeping
   "payload"                   {}})

(defn- cell-state [state]
  (merge state-defaults (get state "cell_state" {})))

(defn transition-verify-force-auth
  "G3/N1: admit the session only if the force class is representable and force-authorized."
  [state]
  (let [cs (cell-state state)
        cs (assoc cs
                  "force_class" (get state "force_class" (get cs "force_class"))
                  "force_auth_ref" (get state "force_auth_ref" (get cs "force_auth_ref")))]
    (when-not (contains? admitted-force-classes (get cs "force_class"))
      (throw (ex-info (str "N1 violation: force class " (pr-str (get cs "force_class"))
                           " is unrepresentable (:weaponizable / force-as-harm can never be admitted; "
                           "Mission Charter §1.12)") {:gate "N1"})))
    (when-not (seq (get cs "force_auth_ref"))
      (throw (ex-info (str "G3 violation: Transparent Force requires a force-authorization reference "
                           "(1 SBT=1 vote admission of this force class) before a teleop session is admitted")
                      {:gate "G3"})))
    {"cell_state" (assoc cs "phase" phase-force-authorized) "next_node" "grant_built"}))

(defn transition-build-grant
  "G4: build a server-keyless grant holding only an encrypted-envelope ref — never plaintext."
  [state]
  (let [cs (cell-state state)
        cs (assoc cs "secret_ref" (get state "secret_ref" (get cs "secret_ref")) "server_held_key" false)]
    (when-not (str/starts-with? (get cs "secret_ref") encref-prefix)
      (throw (ex-info (str "G4 violation: the grant may carry only an encrypted-envelope ref "
                           "(com.etzhayyim.encrypted.*); plaintext credentials are never stored")
                      {:gate "G4"})))
    (let [grant {"robotId" (get cs "robot_id")
                 "principal" member
                 "serverHeldKey" false
                 "secretRef" (get cs "secret_ref")
                 "forceAuthRef" (get cs "force_auth_ref")
                 "deadmanMs" (get cs "deadman_ms")
                 "latencyBudgetMs" (get cs "latency_budget_ms")
                 "recoverySamples" (get cs "recovery_samples")
                 "onChainAnchor" true
                 "outwardGated" true}]
      {"cell_state" (assoc cs "phase" phase-grant-built
                           "payload" (assoc (get cs "payload") "grant" grant))
       "next_node" "relay_command"})))

(defn- latency-hysteresis
  "G10 extension: link-quality hysteresis for the latency-budget dimension only (mirrors
  methods/teleop_safety.clj's link-recover). A breach trips fallback immediately
  (fail-fast); clearing it back to nominal requires `recovery_samples` CONSECUTIVE
  in-budget samples (fail-safe against flapping actuation on/off across a single lucky
  sample amid a jitter-prone link, e.g. LEO satellite beam handoffs). Returns
  [verdict cs'] with the hysteresis bookkeeping folded into cs'."
  [cs]
  (let [breach? (> (get cs "observed_latency_ms") (get cs "latency_budget_ms"))
        active? (get cs "link_fallback_active")]
    (cond
      breach?
      ["latency-breach" (assoc cs "link_fallback_active" true "latency_recovery_count" 0)]

      (not active?)
      ["nominal" cs]

      :else
      (let [count' (inc (get cs "latency_recovery_count"))]
        (if (>= count' (get cs "recovery_samples"))
          ["nominal" (assoc cs "link_fallback_active" false "latency_recovery_count" 0)]
          ["latency-breach" (assoc cs "latency_recovery_count" count')])))))

(defn safe-state
  "G10: the soft-RT supervision verdict (also exported for methods/teleop_safety parity).
  Returns [verdict cs'] — cs' folds in updated link-hysteresis bookkeeping (see
  `latency-hysteresis`). Deadman/e-stop are instant, unaffected by the window: a lapsed
  presence heartbeat needs an explicit operator re-arm, not a lucky sample."
  [cs]
  (cond
    (= (get cs "command_kind") "estop") ["estopped" cs]
    (> (get cs "elapsed_since_presence_ms") (get cs "deadman_ms")) ["deadman-lapse" cs]
    :else (latency-hysteresis cs)))

(defn transition-relay-command
  "G4/G10: relay a member-signed command, but force a safe-stop on any supervision breach.
  A safety command (halt/estop/handback) is always permitted. An actuation command requires a
  member signature, refuses any server signature, and is dropped to a safe-stop on deadman/latency breach."
  [state]
  (let [cs (cell-state state)
        cs (assoc cs
                  "command_kind" (get state "command_kind" (get cs "command_kind"))
                  "member_sig" (get state "member_sig" (get cs "member_sig"))
                  "server_sig" (get state "server_sig" (get cs "server_sig"))
                  "elapsed_since_presence_ms" (get state "elapsed_since_presence_ms" (get cs "elapsed_since_presence_ms"))
                  "observed_latency_ms" (get state "observed_latency_ms" (get cs "observed_latency_ms"))
                  "recovery_samples" (get state "recovery_samples" (get cs "recovery_samples")))]
    (when (seq (get cs "server_sig"))
      (throw (ex-info "G4 violation: server signature refused (no-server-key, ADR-2605231525)" {:gate "G4"})))
    (let [[verdict cs] (safe-state cs)
          kind (get cs "command_kind")]
      (cond
        ;; Safety commands are always honoured, signature-free.
        (contains? always-permitted kind)
        (let [phase (if (not= kind "handback") phase-safe-stopped phase-command-relayed)
              cs (assoc cs "phase" phase
                        "payload" (assoc (get cs "payload") "command"
                                         {"kind" kind "safeState" verdict "memberSigned" false}))]
          {"cell_state" cs "next_node" "end"})

        ;; Actuation commands require a member signature (G4).
        (not (seq (get cs "member_sig")))
        (throw (ex-info "G4 violation: member signature required to relay an actuation command" {:gate "G4"}))

        ;; G10: a supervision breach forces a safe-stop/autonomy-fallback instead of actuating.
        (not= verdict "nominal")
        (let [cs (assoc cs "phase" phase-safe-stopped
                        "payload" (assoc (get cs "payload") "command"
                                         {"kind" "halt"
                                          "safeState" (if (contains? #{"deadman-lapse" "latency-breach"} verdict)
                                                        "autonomy-fallback" verdict)
                                          "reason" verdict
                                          "memberSigned" true}))]
          {"cell_state" cs "next_node" "end"})

        :else
        (let [cs (assoc cs "phase" phase-command-relayed
                        "payload" (assoc (get cs "payload") "command"
                                         {"kind" kind
                                          "safeState" "nominal"
                                          "memberSigned" true
                                          "serverSigned" false
                                          "dryRun" true            ; G7: R0 is plan/replay only
                                          "onChainAnchored" true})) ; G3/G11
          ]
          {"cell_state" cs "next_node" "end"})))))

(defn solve
  "R0 scaffold: .solve() raises until Council activation (ADR-2606042100 §Decision)."
  [_input-state]
  (throw (ex-info "tazuna R0 scaffold: activate teleop_session via Council ADR (post-2606042100 ratification)"
                  {:scaffold true})))
