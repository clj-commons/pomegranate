(ns test-clj
  (:require [babashka.cli :as cli]
            [babashka.tasks :as t]
            [lread.status-line :as status]))

(defn -main [& args]
  (let [all-clojure-versions ["1.8" "1.9" "1.10" "1.11"]
        valid-clj-version-opt-values (conj all-clojure-versions ":all")
        spec {:clj-version
              {:ref "<version>"
               :desc "The Clojure version to test against."
               :coerce :string
               :default-desc "1.8"
               ;; don't specify :default, we want to know if the user passed this option in
               :validate
               {:pred (set valid-clj-version-opt-values)
                :ex-msg (fn [_m]
                          (str "--clj-version must be one of: " valid-clj-version-opt-values))}}}
        opts (cli/parse-opts args {:spec spec})
        clj-version (:clj-version opts)
        runner-args (if-not clj-version
                      args
                      (loop [args args
                             out-args []]
                        (if-let [a (first args)]
                          (if (re-matches #"(--|:)clj-version" a)
                            (recur (drop 2 args) out-args)
                            (recur (rest args) (conj out-args a)))
                          out-args)))
        clj-version (or clj-version "1.8")]

    (if (:help opts)
      (do
        (status/line :head "bb task option help")
        (println (cli/format-opts {:spec spec}))
        (status/line :head "test-runner option help")
        (t/clojure "-M:test --test-help"))
      (let [clj-versions (if (= ":all" clj-version)
                           all-clojure-versions
                           [clj-version])]
        (doseq [v clj-versions]
          (status/line :head "Testing against Clojure version %s" v)
          (apply t/clojure (format "-M:%s:test" v) runner-args))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
