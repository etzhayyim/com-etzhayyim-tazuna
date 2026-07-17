(ns tazuna.methods.teleop-safety
  "tazuna 手綱 teleop-safety reasoner — 1:1 Clojure port of methods/teleop_safety.py
  (ADR-2606042100). The safety-critical core of the teleoperation control plane,
  pure + deterministic so it unit-tests offline. For every relayed command, in
  priority order:

    1. force-class admission (N1) — representable + force-authorized (G3)?
    2. no-server-key (G4)        — member-signed, NO server signature?
    3. soft-RT supervision (G10) — deadman lapsed / latency budget breached?
    4. safe verdict             — nominal / deadman / latency / estopped / autonomy-fallback.

  Best-effort SOFT-real-time supervision, NOT a certified (IEC 61508 / ISO 13849)
  system; never a live actuation (G7) — the verdict is advisory + replayable.

  `evaluate-session` extends this to a link-quality-hysteresis fold over a sequence of
  commands (satellite/high-jitter-link recovery gating) — see its docstring.

  Errors are ex-info maps tagged `:error` ∈ {:force-class :transparent-force
  :no-server-key :value-error} (mirroring the Python exception subclasses)."
  (:require [clojure.string :as str]))

(def ADMITTED-FORCE-CLASSES #{"observational" "soft-actuation" "powered-actuation"})
(def ALWAYS-PERMITTED #{"halt" "estop" "handback"})   ; safety commands; never gated/signed
(def ACTUATION-KINDS #{"move" "manipulate"})

(defn- fail! [kind msg] (throw (ex-info msg {:error kind})))

(defn command
  "Build a command map (defaults mirror the Python @dataclass)."
  ([kind] (command kind {}))
  ([kind opts]
   (merge {:kind kind :member-sig "" :server-sig ""
           :elapsed-since-presence-ms 0 :observed-latency-ms 0}
          opts)))

(defn grant
  "Build a grant map (defaults mirror the Python @dataclass). `:recovery-samples`
  (added for evaluate-session, G10 extension) is the count of CONSECUTIVE in-budget
  latency samples required to clear an autonomy-fallback and resume nominal actuation —
  see evaluate-session."
  ([] (grant {}))
  ([opts]
   (merge {:force-class "soft-actuation" :force-auth-ref ""
           :deadman-ms 300 :latency-budget-ms 150 :recovery-samples 3}
          opts)))

(def satellite-leo-grant-defaults
  "Illustrative starting point for a LEO-satellite-relayed session (e.g. a Starlink-class
  backhaul): a wider latency budget than a terrestrial/LAN link to tolerate normal
  beam-handoff jitter, plus a longer recovery-samples window so a single lucky sample
  mid-handoff cannot re-arm actuation while the link is still degraded. NOT calibrated
  against any real link (G12 sourcing-honesty) — an operator must measure the actual
  path (RTT + jitter distribution, handoff period) before using this for anything beyond
  a sim/tabletop dry run; there is no live link at R0 (G7)."
  {:latency-budget-ms 400 :recovery-samples 5 :deadman-ms 300})

(defn admit-session
  "N1 + G3: raise unless the force class is representable AND force-authorized."
  [g]
  (when-not (contains? ADMITTED-FORCE-CLASSES (:force-class g))
    (fail! :force-class
           (str "N1: force class '" (:force-class g) "' is unrepresentable; "
                "weaponizable / force-as-harm can never be admitted (Mission Charter §1.12)")))
  (when-not (seq (:force-auth-ref g))
    (fail! :transparent-force
           (str "G3: Transparent Force requires a force-authorization reference "
                "(1 SBT=1 vote admission) before a teleop session is admitted")))
  nil)

(defn- verdict [safe-state actuates effective-kind reason]
  {:safe-state safe-state :actuates actuates :effective-kind effective-kind :reason reason})

(defn evaluate
  "Return the safety verdict for one relayed command. Raises on N1/G3/G4 violations.
  Priority: e-stop > deadman lapse > latency breach > nominal."
  [c g]
  (admit-session g)
  ;; G4: a server signature is always refused, for any command.
  (when (seq (:server-sig c))
    (fail! :no-server-key
           (str "G4: server signature refused — the platform never signs a physical-robot "
                "command (no-server-key, ADR-2605231525)")))
  (let [kind (:kind c)]
    (cond
      (= kind "estop")
      (verdict "estopped" false "estop" "emergency stop")

      (or (= kind "halt") (= kind "handback"))
      (verdict "nominal" false kind "safety command")

      (not (contains? ACTUATION-KINDS kind))
      (fail! :value-error (str "unknown command kind '" kind "'"))

      ;; G4: actuation requires a member signature.
      (not (seq (:member-sig c)))
      (fail! :no-server-key "G4: a member signature is required to relay an actuation command")

      ;; G10: soft-RT supervision — deadman first, then latency.
      (> (:elapsed-since-presence-ms c) (:deadman-ms g))
      (verdict "autonomy-fallback" false "halt"
               (str "deadman lapse (" (:elapsed-since-presence-ms c) "ms > " (:deadman-ms g) "ms)"))

      (> (:observed-latency-ms c) (:latency-budget-ms g))
      (verdict "autonomy-fallback" false "halt"
               (str "latency breach (" (:observed-latency-ms c) "ms > " (:latency-budget-ms g) "ms)"))

      :else
      (verdict "nominal" true kind "member-signed, in-budget"))))

(defn- link-recover
  "G10 extension: fold one latency sample through link-quality hysteresis. `link` is
  {:fallback? bool :recovery-count int}. A breach trips fallback immediately
  (fail-fast, unchanged priority: deadman/latency instantly force a safe-stop);
  clearing fallback back to nominal requires `recovery-samples` CONSECUTIVE in-budget
  samples (fail-safe against flapping actuation on/off across a single lucky sample
  amid a jitter-prone link, e.g. LEO beam handoffs) — mirrors
  cells/teleop_session/state_machine.cljc's latency-hysteresis."
  [{:keys [fallback? recovery-count] :or {fallback? false recovery-count 0}} breach? recovery-samples]
  (cond
    breach? {:fallback? true :recovery-count 0}
    (not fallback?) {:fallback? false :recovery-count 0}
    :else (let [n (inc recovery-count)]
            (if (>= n recovery-samples)
              {:fallback? false :recovery-count 0}
              {:fallback? true :recovery-count n}))))

(defn evaluate-session
  "G10 extension: fold `evaluate`'s per-command priority (e-stop > deadman > latency >
  nominal) over a SEQUENCE of relayed commands against one grant, applying link-quality
  hysteresis to the latency-budget dimension only — deadman/e-stop stay instant, exactly
  as in `evaluate` (a lapsed presence heartbeat needs an explicit operator re-arm, not a
  lucky sample). Use for a satellite-relayed session where a single link recovery blip
  should not be trusted to re-arm actuation mid-degraded-link. Raises on N1/G3/G4
  violations, same as `evaluate`. Returns a vector of verdicts, one per command, in
  order."
  [commands g]
  (admit-session g)
  (loop [cs commands link {:fallback? false :recovery-count 0} out (transient [])]
    (if (empty? cs)
      (persistent! out)
      (let [c (first cs)]
        (when (seq (:server-sig c))
          (fail! :no-server-key
                 (str "G4: server signature refused — the platform never signs a "
                      "physical-robot command (no-server-key, ADR-2605231525)")))
        (let [kind (:kind c)]
          (cond
            (= kind "estop")
            (recur (rest cs) link (conj! out (verdict "estopped" false "estop" "emergency stop")))

            (or (= kind "halt") (= kind "handback"))
            (recur (rest cs) link (conj! out (verdict "nominal" false kind "safety command")))

            (not (contains? ACTUATION-KINDS kind))
            (fail! :value-error (str "unknown command kind '" kind "'"))

            (not (seq (:member-sig c)))
            (fail! :no-server-key "G4: a member signature is required to relay an actuation command")

            (> (:elapsed-since-presence-ms c) (:deadman-ms g))
            (recur (rest cs) link
                   (conj! out (verdict "autonomy-fallback" false "halt"
                                        (str "deadman lapse (" (:elapsed-since-presence-ms c)
                                             "ms > " (:deadman-ms g) "ms)"))))

            :else
            (let [breach? (> (:observed-latency-ms c) (:latency-budget-ms g))
                  link' (link-recover link breach? (:recovery-samples g))]
              (if (:fallback? link')
                (recur (rest cs) link'
                       (conj! out (verdict "autonomy-fallback" false "halt"
                                            (if breach?
                                              (str "latency breach (" (:observed-latency-ms c)
                                                   "ms > " (:latency-budget-ms g) "ms)")
                                              (str "latency recovering (" (:recovery-count link')
                                                   "/" (:recovery-samples g) " consecutive in-budget)")))))
                (recur (rest cs) link'
                       (conj! out (verdict "nominal" true kind "member-signed, in-budget")))))))))))
