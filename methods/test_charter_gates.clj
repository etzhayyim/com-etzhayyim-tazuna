(ns tazuna.methods.test-charter-gates
  "tazuna 手綱 — constitutional-gate conformance tests (local lexicons).

  Substrate-native Clojure (clj + datomic first tier). tazuna is clean-room remote-robotics
  fleet operation + teleoperation + learning-from-demonstration — Transparent-Force bound,
  no-server-key, dividend-coupled, and 'weaponizable unrepresentable'. Its charter discipline is
  const/enum-encoded across the 6 first-tier `lex/*.edn` lexicons (read via clojure.edn). This
  suite pins them so a future R-phase cell wave cannot silently drift them:

    no-server-key — teleopGrant.serverHeldKey const false, principal const member; teleopCommand
      requires a memberSig and pins serverSig to \"\" (the server never signs)
    weaponizable-unrepresentable — robotDescriptor.forceClass + teleopCommand.kind are bounded
      benign sets (no weapon / kinetic / fire verb representable) — Charter §2(a) force-separation
    SAE-L4 ceiling — autonomyHandoff.saeCeiling const L4; outward-gated
    cash≡0 / member-principal — demonstrationEpisode.cash const 0, demonstrator const member,
      consent + encrypted const true
    Murakumo-only / edge — policyArtifact.murakumoOnly + edgeEnvelope const true; baien-federated
    dry-run-default — teleopCommand.dryRun const true (R0 safety; live = outward-gated)

  It weakens no gate; it asserts them. The no-server-key + Murakumo-only invariants are pinned
  directly from the lexicons here."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.set :as set]
            [clojure.edn :as edn]))

(def ^:private here (.getParentFile (java.io.File. ^String *file*)))      ;; methods/
(def ^:private actor-dir (.getParentFile here))                          ;; tazuna/
(def ^:private lexdir (java.io.File. actor-dir "lex"))
(defn- lex [name]
  (edn/read-string (slurp (java.io.File. lexdir (str name ".edn")))))

(defn- record-node [doc] (get-in doc [:defs :main :record]))
(defn- required-of [doc] (set (:required (record-node doc))))
(defn- const-of [doc field] (get-in (record-node doc) [:properties field :const]))
(defn- enum-of [doc field]
  (let [p (get-in (record-node doc) [:properties field])]
    (set (or (:enum p) (get-in p [:items :enum])))))    ;; scalar enum OR array-of-enum

;; verbs that must NOT be representable in a teleop command vocabulary
(def FORBIDDEN-VERBS #{"fire" "launch" "weapon" "strike" "shoot" "detonate" "arm"})

;; ── no-server-key: the actor never holds/uses a server key; the member signs ──
(deftest no-server-key
  (let [g (lex "teleopGrant")]
    (is (= false (const-of g :serverHeldKey)) "teleopGrant.serverHeldKey const false")
    (is (= "member" (const-of g :principal)) "teleopGrant.principal const member"))
  (let [c (lex "teleopCommand")]
    (is (= "" (const-of c :serverSig)) "teleopCommand.serverSig const \"\" (server never signs)")
    (is (contains? (required-of c) "memberSig") "teleopCommand must require a memberSig")))

;; ── weaponizable-unrepresentable: bounded benign force/command vocab ──
(deftest weaponizable-unrepresentable
  (let [fc (enum-of (lex "robotDescriptor") :forceClass)]
    (is (= #{"observational" "soft-actuation" "powered-actuation"} fc)
        (str "forceClass must be the benign set (no weapon/kinetic class), got " fc)))
  (let [k (enum-of (lex "teleopCommand") :kind)]
    (is (= #{"move" "manipulate" "halt" "estop" "handback"} k)
        (str "teleop kind must be the benign verb set, got " k))
    (is (empty? (set/intersection k FORBIDDEN-VERBS))
        "no weapon/fire verb representable in a teleop command")))

;; ── SAE-L4 autonomy ceiling, outward-gated ──
(deftest sae-l4-ceiling
  (let [h (lex "autonomyHandoff")]
    (is (= "L4" (const-of h :saeCeiling)) "autonomyHandoff.saeCeiling const L4")
    (is (= true (const-of h :outwardGated)) "autonomy handoff is outward-gated")))

;; ── cash≡0 / member-principal demonstration ──
(deftest cash-zero-member-principal
  (let [d (lex "demonstrationEpisode")]
    (is (= 0 (const-of d :cash)) "demonstrationEpisode.cash const 0")
    (is (= "member" (const-of d :demonstrator)) "demonstrator const member")
    (is (= true (const-of d :consent)) "demonstration consent const true")
    (is (= true (const-of d :encrypted)) "demonstration payload encrypted const true")))

;; ── Murakumo-only inference + edge envelope ──
(deftest murakumo-only-edge
  (let [p (lex "policyArtifact")]
    (is (= true (const-of p :murakumoOnly)) "policyArtifact.murakumoOnly const true")
    (is (= true (const-of p :edgeEnvelope)) "policyArtifact.edgeEnvelope const true")
    (is (= "baien-federated" (const-of p :trainedVia)) "policy trained via baien-federated")))

;; ── dry-run default (R0 safety; live operation = outward-gated) ──
(deftest dry-run-default
  (is (= true (const-of (lex "teleopCommand") :dryRun))
      "teleopCommand.dryRun const true (live operation is Council/operator-gated)")
  (is (= #{"sim-only" "supervised-handoff" "blocked"} (enum-of (lex "policyArtifact") :promotion))
      "policy promotion is bounded {sim-only, supervised-handoff, blocked}"))

(defn -main [& _]
  (let [r (run-tests 'tazuna.methods.test-charter-gates)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
