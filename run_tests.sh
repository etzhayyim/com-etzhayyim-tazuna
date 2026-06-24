#!/usr/bin/env bash
# tazuna — clj/bb test suite (ADR-2606160842 py->clj port wave); ALL test namespaces, fleet green-check.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec bb -e '(require (quote clojure.test) (quote tazuna.cells.teleop-session.test-state-machine) (quote tazuna.methods.test-charter-gates) (quote tazuna.methods.test-teleop-safety))(let [r (apply clojure.test/run-tests (quote [tazuna.cells.teleop-session.test-state-machine tazuna.methods.test-charter-gates tazuna.methods.test-teleop-safety]))](System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))'
