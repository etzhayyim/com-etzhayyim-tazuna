# tazuna (手綱) — remote-robotics teleop + learning-from-demonstration actor

**DID**: `did:web:etzhayyim.com:actor:tazuna` · **Tier**: B · **Status**: R0 · **ADR**: 2606042100

## What this is

The charter-clean **Boston-Dynamics-Orbit** equivalent: the missing **head** that remotely operates
the robotics-body fleet (sanae/kiyome/hataori/giemon/wadachi) and the missing **loop** that learns
from operating it. 手綱 = *the reins* — hold them to teleoperate a robot that does not yet know the
path, release them (the 手綱-release = supervised autonomy hand-off) as it earns autonomy through
imitation learning on its own demonstrations.

A **horizontal control-plane actor** (operates the bodies; not a single-occupation body), with two
faces: **operation** (fleet/teleop control plane) and **learning** (demonstration → baien federated
policy → supervised hand-off). The find/render split of danjo→kanae and the observe/operate split of
watatsuna→watatsumi; here it is operate→learn in one actor because the loop is the point.

## Cells (langgraph→WASM; Murakumo-only; `.solve()` raises at R0)

Operation: `fleet_registry` (reuben) · `mission_dispatch` (simeon) · **`teleop_session`** (levi —
coded reference cell, the safety-critical one) · `telemetry_ingest` (judah).
Learning: `demonstration_record` (zebulun) · `policy_train` (issachar) · `autonomy_handoff` (dan).

## Gates (immutable R0→R5)

**G1 cleanroom-interop** (published API shapes + open standards only — Orbit REST shape / Open-RMF /
ROS 2 / DDS / MCAP / rosbag2 / LeRobot / Foxglove; nv-compat Isaac; NO vendor SDK/decompile/trademark/
fork) · **G2 displacement-dividend-coupling** (no live displacing operation without a funded cohort,
ADR-2606032130) · **G3 transparent-force** (every command an on-chain Datom; open-source; 1 SBT=1 vote
force-auth; no force-auth ref → no grant) · **G4 no-server-key** (member signs every actuation;
server signature refused; serverHeldKey=false, ADR-2605231525) · **G5 murakumo-only** · **G6
edge-envelope-policy** (baien envelope, ADR-2605241900) · **G7 outward-gated** (any live actuation/
enrollment/capture Council Lv6+ + operator; Lv7+ for :powered-actuation) · **G8 consent-bound-
demonstration** (member's labour; encrypted; cash=0) · **G9 privacy-by-construction** (home teleop
on-device-only, no cloud video/biometric, kiyome G9) · **G10 soft-rt-not-a-safety-system** (deadman/
e-stop/latency; IEC 61508 hard-RT = R5/Lv7+, kotoba-os N2) · **G11 kotoba-EAVT audit** · **G12
sourcing-honesty** (`:representative`; manipulation is unsolved — honest staging).

## Non-goals

N1 no weapons/force-as-harm (`:weaponizable` unrepresentable) · N2 no surveillance/covert teleop · N3
no non-member fleet operation · N4 no gig-teleop marketplace · N5 not a certified safety controller ·
N6 no autonomy beyond SAE-L4 / no un-supervised policy promotion · N7 no vendor SDK/firmware/weights
bundling / no ToS evasion · N8 no cash for demonstrations/operation.

## Build / test

```
cd methods && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest   # teleop-safety reasoner (23 tests, incl. satellite-link hysteresis)
cd cells   && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest   # teleop_session state machine (14 tests, incl. satellite-link hysteresis)
python3 methods/teleop_safety.py                                   # offline demo
```

(The code is stdlib-only; the `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` prefix avoids a global pydantic
pytest plugin, same as karakuri.)

R0 = design + `teleop_safety` reasoner + `teleop_session` state-machine + `:representative` fleet
seed. **No hardware, no live link, no live actuation** (all gated G7).

## Do not

- Do not let the platform sign an actuation command or hold a robot's key — G4 / ADR-2605231525.
- Do not build a `teleopGrant` without a force-authorization reference — G3.
- Do not introduce a `:weaponizable` / force-as-harm force class — N1 (it is unrepresentable in the
  schema, lexicon, and `teleop_safety.ADMITTED_FORCE_CLASSES`).
- Do not stream home/private teleop to a third party or capture biometrics — G9 / N2.
- Do not pay cash for demonstrations, or treat demonstration data as a third-party corpus — G8 / N8.
- Do not promote a policy to live actuation without a supervised hand-off + gate, or exceed SAE-L4 —
  N6.
- Do not bundle a vendor SDK / firmware / weights, or evade a vendor ToS — G1 / N7.
- Do not call any cell's `.solve()` — R0 scaffolds raise `RuntimeError` by design.
