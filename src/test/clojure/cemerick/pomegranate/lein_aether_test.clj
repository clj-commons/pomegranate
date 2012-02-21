(ns cemerick.pomegranate.lein-aether-test
  (:require [cemerick.pomegranate.aether :as aether]
            [cemerick.pomegranate.lein-aether :as lein-aether]
            [clojure.java.io :as io])
  (:use clojure.test
        cemerick.pomegranate.test-utils))

(use-fixtures :each clear-tmp bind-local-repo)

(deftest dependency-roundtripping
  (are [x] (= x (#'lein-aether/lein-spec
                 (#'aether/dep-spec
                  (#'aether/dependency
                   (#'lein-aether/lein-dependency x)))))
       '[ring "1.0.0" :optional true]
       '[com.cemerick/pomegranate "0.0.1" :classifier "sources"]
       '[demo/demo2 "1.0.0" :exclusions [[demo :classifier "jdk5"]]]))

(deftest live-resolution
  (let [deps '[[commons-logging "1.1"]]
        graph '{[javax.servlet/servlet-api "2.3"] nil,
                [avalon-framework "4.1.3"] nil,
                [logkit "1.0.1"] nil,
                [log4j "1.2.12"] nil,
                [commons-logging "1.1"]
                #{[javax.servlet/servlet-api "2.3"] [avalon-framework "4.1.3"]
                  [logkit "1.0.1"] [log4j "1.2.12"]}}
        hierarchy '{[commons-logging "1.1"]
                    {[avalon-framework "4.1.3"] nil,
                     [javax.servlet/servlet-api "2.3"] nil,
                     [log4j "1.2.12"] nil,
                     [logkit "1.0.1"] nil}}]
    (is (= graph
           (lein-aether/resolve-dependencies
            :coordinates deps :retrieve false)))
    (is (not (some jar-file? (local-repo-files))))

    (doseq [[dep _] (lein-aether/resolve-dependencies :coordinates deps)]
      (is (-> dep meta :file))
      (is (-> dep meta :file .exists)))
    (is (some jar-file? (local-repo-files)))

    (is (= hierarchy (lein-aether/dependency-hierarchy deps graph)))))

(deftest resolve-deps
  (let [deps (lein-aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[[demo/demo "1.0.0"]])]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (local-repo-file "demo" "demo" "1.0.0"))
           (.getAbsolutePath (first (lein-aether/dependency-files deps)))))))

(deftest resolve-deps-with-deps
  (let [deps (lein-aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[[demo/demo2 "1.0.0"]])
        files (lein-aether/dependency-files deps)]
    (is (= 2 (count files)))
    (is (= 1
           (count
            (filter
             (file-path-matcher (local-repo-file "demo" "demo" "1.0.0"))
             files))))
    (is (= 1
           (count
            (filter
             (file-path-matcher (local-repo-file "demo" "demo2" "1.0.0"))
             files))))))

(deftest resolve-deps-with-exclusions
  (let [deps (lein-aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[[demo/demo2 "1.0.0" :exclusions [[demo/demo]]]])]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (local-repo-file "demo" "demo2" "1.0.0"))
           (.getAbsolutePath (first (lein-aether/dependency-files deps)))))))

(deftest deploy-jar
  (lein-aether/deploy
   :coordinates '[group/artifact "1.0.0"]
   :jar-file (test-repo-file "demo" "demo" "1.0.0")
   :pom-file (test-repo-file "demo" "demo" "1.0.0" "pom")
   :repository tmp-remote-repo)
  (is (= 6 (count (remote-repo-files "group" "artifact" "1.0.0")))))

(deftest install-jar
  (lein-aether/install
   :coordinates '[group/artifact "1.0.0"]
   :jar-file (test-repo-file "demo" "demo" "1.0.0")
   :pom-file (test-repo-file "demo" "demo" "1.0.0" "pom"))
  (is (= 3 (count (local-repo-files "group" "artifact" "1.0.0")))))


(comment
  "tests needed for:

  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
