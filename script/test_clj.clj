(ns test-clj
  (:require [babashka.cli :as cli]
            [babashka.tasks :as t]
            [clojure.string :as string]
            [lread.status-line :as status]))

(defn -main [& args]
  (let [old-clojure-versions ["1.4" "1.5" "1.6" "1.7"]
        all-clojure-versions (concat old-clojure-versions ["1.8" "1.9" "1.10" "1.11"])
        default-version (first all-clojure-versions)
        valid-clj-version-opt-values (conj all-clojure-versions ":all")
        all-suites [:unit :isolated]
        valid-suite-opt-values (conj all-suites :all)
        spec {:suite
              {:ref "<suite>"
               :desc (str "The test suite to run, valid values: " (string/join ", " valid-suite-opt-values))
               :coerce :keyword
               :default :all
               :validate
               {:pred (set valid-suite-opt-values)
                :ex-msg (fn [_m]
                          (str "--suite must be one of: " (string/join ", " valid-suite-opt-values)))}}
              :clj-version
              {:ref "<version>"
               :desc (str "The Clojure version to test against, valid values: " (string/join ", " valid-clj-version-opt-values))
               :coerce :string
               :booya :foo
               :default-desc default-version
               ;; don't specify :default, we want to know if the user passed this option in
               :validate
               {:pred (set valid-clj-version-opt-values)
                :ex-msg (fn [_m]
                          (str "--clj-version must be one of: " (string/join ", " valid-clj-version-opt-values)))}}}
        opts (cli/parse-opts args {:spec spec})
        suite (:suite opts)
        clj-version (:clj-version opts)
        runner-args (if-not (or clj-version suite)
                      args
                      (loop [args args
                             out-args []]
                        (if-let [a (first args)]
                          (if (re-matches #"(--|:)(clj-version|suite)" a)
                            (recur (drop 2 args) out-args)
                            (recur (rest args) (conj out-args a)))
                          out-args)))
        clj-version (or clj-version default-version)]

    (if (:help opts)
      (do
        (status/line :head "bb task option help")
        (println (cli/format-opts {:spec spec}))
        (status/line :head "test-runner option help")
        (t/clojure "-M:test:old-runner --test-help"))
      (let [suites (if (= :all suite)
                     all-suites
                     [suite])
            clj-versions (if (= ":all" clj-version)
                           all-clojure-versions
                           [clj-version])]
        (doseq [v clj-versions
                s suites
                :let [test-alias (if (some #{v} old-clojure-versions)
                                   "test:old-runner"
                                   "test")
                      test-alias (if (= :isolated s)
                                   (str test-alias ":isolated")
                                   test-alias)]]
          (status/line :head "Testing %s suite against Clojure version %s" s v)
          (apply t/clojure (format "-M:%s:%s" v test-alias) runner-args))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
