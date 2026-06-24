# tazuna 手綱 — remote-robotics fleet operation + teleoperation + learning-from-demonstration

**DID**: `did:web:etzhayyim.com:actor:tazuna` · **Tier**: B · **Status**: R0 · **ADR**: 2606042100

The charter-clean answer to the **Boston-Dynamics-Orbit** shape — the missing **head** that remotely
operates the robotics-body fleet, and the missing **loop** that learns from operating it. 手綱 = *the
reins*: you hold them to guide a robot that does not yet know the path (teleoperation); you release
them as it earns autonomy through demonstration (imitation learning). The 手綱-release **is** the
learning loop closing.

## Two faces

- **Operation** (the Orbit half): a transparent, member-signed, on-chain-logged **fleet-management +
  teleoperation control plane** over sanae / kiyome / hataori / giemon / wadachi.
  Cells: `fleet_registry` · `mission_dispatch` · **`teleop_session`** (coded) · `telemetry_ingest`.
- **Learning** (the train-from-it half): teleop trajectory → consent-bound **demonstration** →
  **baien federated** imitation/behavior-cloning **policy** → **supervised autonomy hand-off**.
  Cells: `demonstration_record` · `policy_train` · `autonomy_handoff`.

## Why it is constitutional

Remote actuation of a physical machine is the most charter-sensitive capability in the roster, so it
is admitted only inside three load-bearing invariants:

- **Transparent Force (G3, Charter §1.12.B)** — every `teleopCommand` is an on-chain-anchored Datom;
  control code open-source; force class + activation governed by 1 SBT = 1 vote. No
  force-authorization reference → no grant.
- **no-server-key (G4, ADR-2605231525)** — the platform **never** holds the key that commands a
  robot. The member signs every actuation command; a server signature is refused by construction.
- **soft-RT supervision, not a safety system (G10, kotoba-os N2)** — deadman / e-stop / latency
  budget force a safe-stop/autonomy-fallback; safety-rated + hard-RT live actuation near humans is
  R5/Lv7+.

Plus: force class `:weaponizable` is **structurally unrepresentable** (N1); demonstrations are the
member's own labour — encrypted, consent-bound, `cash≡0` (G8/N8); home teleop is on-device-only with
no cloud video / biometric capture (G9, inherited from kiyome); Murakumo-only (G5); policies fit the
baien edge envelope (G6); live operation that displaces labour requires the Displacement Dividend
(G2, ADR-2606032130).

## Clean-room interop (G1)

Published API shapes + open standards **only** — no vendor SDK / decompilation / trademarked code /
fork (the sumitsubo + nv-compat pattern): ROS 2 / DDS / MCAP / rosbag2 / LeRobot / Foxglove (open),
Open-RMF (Apache-2.0), and the Boston Dynamics Orbit REST/gRPC call *shapes* as a neutral vocabulary
(names in adapter docstrings only), NVIDIA Isaac via nv-compat (ADR-2605261800).

## Build / test

```
cd methods && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest   # teleop-safety reasoner (18 tests)
cd cells   && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python3 -m pytest   # teleop_session state machine (11 tests)
python3 methods/teleop_safety.py                                   # offline safety-reasoner demo
```

R0 = design + the `teleop_safety` reasoner + the `teleop_session` state-machine + a `:representative`
fleet seed. **No hardware, no live robot link, no live actuation** — every adapter call is gated
Council Lv6+ + operator (Lv7+ for `:powered-actuation` near humans), G7.

## Do not

- Do not let the platform sign an actuation command or hold a robot's key — G4 / ADR-2605231525.
  The member signs; the server signature is refused.
- Do not build a `teleopGrant` without a force-authorization reference (1 SBT = 1 vote) — G3.
- Do not introduce a `:weaponizable` / force-as-harm force class — N1; it is unrepresentable.
- Do not stream home/private teleop video to any third party or capture biometrics — G9 / N2.
- Do not pay cash for demonstrations or operation, and do not treat demonstration data as a
  third-party corpus — G8 / N8.
- Do not promote a policy to live actuation without a supervised hand-off + gate, or exceed the
  wadachi SAE-L4 ceiling — N6.
- Do not bundle a vendor SDK / firmware / weights or evade a vendor ToS — G1 / N7.
- Do not call any cell's `.solve()` — R0 scaffolds raise `RuntimeError` by design.
