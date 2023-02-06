(ns cemerick.pomegranate.test-report
  (:require [clojure.test]))

(def platform
  (str "clj " (clojure-version) " jdk " (System/getProperty "java.version")))

(defmethod clojure.test/report :begin-test-var [m]
  (let [test-name (-> m :var meta :name)]
    (println (format "=== %s [%s]" test-name platform))))
