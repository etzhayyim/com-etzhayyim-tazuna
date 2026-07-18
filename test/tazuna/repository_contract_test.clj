(ns tazuna.repository-contract-test (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.test :refer [deftest is]]))
(deftest boundary (let [c (edn/read-string (slurp "repository-contracts.edn"))] (is (= :edn (get-in c [:canonical :format]))) (is (.isFile (io/file "wire/manifest.jsonld"))) (doseq [p (:forbidden-root-paths c)] (is (not (.exists (io/file p))) p))))
