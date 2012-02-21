(ns cemerick.pomegranate.aether-test
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io])
  (:use clojure.test
        cemerick.pomegranate.test-utils))

(use-fixtures :each clear-tmp bind-local-repo)

(deftest dependency-roundtripping
  (let [default-dep {:extension "jar" :scope "compile"}]
    (are [x] (= (merge (assoc default-dep :base-version (:version x)) x)
                (#'aether/dep-spec (#'aether/dependency x)))
         {:group-id "ring" :artifact-id "ring" :version "1.0.0" :optional true}
         {:group-id "com.cemerick" :artifact-id "pomegranate" :version "0.0.1"
          :classifier "sources"}
         {:group-id "demo" :artifact-id "demo2" :version "1.0.0"
          :exclusions [{:group-id "demo" :artifact-id "demo"
                        :classifier "jdk5"}]})))

(deftest live-resolution
  (let [deps [{:group-id "commons-logging" :artifact-id "commons-logging"
               :version "1.1"}]
        graph {{:group-id "javax.servlet" :base-version "2.3"
                :properties {:constitutes-build-path true
                             :type "jar" :language "java"}
                :extension "jar" :version "2.3" :scope "compile"
                :artifact-id "servlet-api"}
               nil
               {:group-id "avalon-framework" :base-version "4.1.3"
                :properties {:constitutes-build-path true
                             :type "jar" :language "java"}
                :extension "jar" :version "4.1.3" :scope "compile"
                :artifact-id "avalon-framework"}
               nil
               {:group-id "logkit" :base-version "1.0.1"
                :properties {:constitutes-build-path true
                             :type "jar" :language "java"}
                :extension "jar" :version "1.0.1" :scope "compile"
                :artifact-id "logkit"}
               nil
               {:group-id "log4j" :base-version "1.2.12"
                :properties {:constitutes-build-path true
                             :type "jar" :language "java"}
                :extension "jar" :version "1.2.12" :scope "compile"
                :artifact-id "log4j"}
               nil
               {:group-id "commons-logging" :base-version "1.1"
                :extension "jar" :version "1.1"
                :scope "compile" :artifact-id "commons-logging"}
               #{{:group-id "log4j" :base-version "1.2.12"
                  :properties {:constitutes-build-path true
                               :type "jar" :language "java"}
                  :extension "jar" :version "1.2.12" :scope "compile"
                  :artifact-id "log4j"}
                 {:group-id "javax.servlet" :base-version "2.3"
                  :properties {:constitutes-build-path true :type "jar"
                               :language "java"}
                  :extension "jar" :version "2.3" :scope "compile"
                  :artifact-id "servlet-api"}
                 {:group-id "logkit" :base-version "1.0.1"
                  :properties {:constitutes-build-path true :type "jar"
                               :language "java"}
                  :extension "jar" :version "1.0.1" :scope "compile"
                  :artifact-id "logkit"}
                 {:group-id "avalon-framework" :base-version "4.1.3"
                  :properties {:constitutes-build-path true :type "jar"
                               :language "java"}
                  :extension "jar" :version "4.1.3" :scope "compile"
                  :artifact-id "avalon-framework"}}}
        hierarchy {{:group-id "commons-logging" :base-version "1.1"
                    :extension "jar" :version "1.1" :scope "compile"
                    :artifact-id "commons-logging"}
                   {{:group-id "avalon-framework" :base-version "4.1.3"
                     :extension "jar" :version "4.1.3" :scope "compile"
                     :artifact-id "avalon-framework"}
                    nil
                    {:group-id "javax.servlet" :base-version "2.3"
                     :extension "jar" :version "2.3" :scope "compile"
                     :artifact-id "servlet-api"}
                    nil
                    {:group-id "log4j" :base-version "1.2.12" :extension "jar"
                     :version "1.2.12" :scope "compile" :artifact-id "log4j"}
                    nil
                    {:group-id "logkit" :base-version "1.0.1" :extension "jar"
                     :version "1.0.1" :scope "compile" :artifact-id "logkit"}
                    nil}}]
    (is (= graph
           (aether/resolve-dependencies :coordinates deps :retrieve false)))
    (is (not (some jar-file? (local-repo-files))))

    (doseq [[dep _] (aether/resolve-dependencies :coordinates deps)]
      (is (-> dep meta :file))
      (is (-> dep meta :file .exists)))
    (is (some jar-file? (local-repo-files)))

    (is (= hierarchy (aether/dependency-hierarchy deps graph)))))

(deftest resolve-deps
  (let [deps (aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[{:group-id "demo" :artifact-id "demo"
                              :version"1.0.0"}])]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (local-repo-file "demo" "demo" "1.0.0"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-deps
  (let [deps (aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[{:group-id "demo" :artifact-id "demo2"
                              :version "1.0.0"}])
        files (aether/dependency-files deps)]
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
  (let [deps (aether/resolve-dependencies
              :repositories test-repo
              :coordinates '[{:group-id "demo" :artifact-id "demo2"
                              :version "1.0.0"
                              :exclusions [{:group-id "demo"
                                            :artifact-id "demo"}]}])]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (local-repo-file "demo" "demo2" "1.0.0"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest deploy-jar
  (aether/deploy
   :coordinates '{:group-id "group" :artifact-id "artifact" :version "1.0.0"}
   :jar-file (test-repo-file "demo" "demo" "1.0.0")
   :pom-file (test-repo-file "demo" "demo" "1.0.0" "pom")
   :repository tmp-remote-repo)
  (is (= 6 (count (remote-repo-files "group" "artifact" "1.0.0")))))

(deftest install-jar
  (aether/install
   :coordinates '{:group-id "group" :artifact-id "artifact" :version "1.0.0"}
   :jar-file (test-repo-file "demo" "demo" "1.0.0")
   :pom-file (test-repo-file "demo" "demo" "1.0.0" "pom"))
  (is (= 3 (count (local-repo-files "group" "artifact" "1.0.0")))))


(comment
  "tests needed for:

  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
