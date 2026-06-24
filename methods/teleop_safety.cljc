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
  "Build a grant map (defaults mirror the Python @dataclass)."
  ([] (grant {}))
  ([opts]
   (merge {:force-class "soft-actuation" :force-auth-ref ""
           :deadman-ms 300 :latency-budget-ms 150}
          opts)))

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
