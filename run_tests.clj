(require '[clojure.test :as t])
(def suites '[tazuna.methods.test-charter-gates tazuna.methods.test-teleop-safety tazuna.cells.teleop-session.test-state-machine tazuna.murakumo-test tazuna.repository-contract-test])
(apply require suites)
(let [{:keys [fail error] :as r} (apply t/run-tests suites)] (println (select-keys r [:test :pass :fail :error])) (when (pos? (+ fail error)) (System/exit 1)))
