(ns cemerick.pomegranate.aether-test
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io])
  (:use [clojure.test]))

(deftest dependency-roundtripping
  (are [x] (= x (#'aether/dep-spec (#'aether/dependency x)))
       '[ring "1.0.0" :optional true]
       '[com.cemerick/pomegranate "0.0.1" :classifier "sources"]
       '[demo/demo2 "1.0.0" :exclusions [[demo :classifier "jdk5"]]]))

(def tmp-dir (io/file (System/getProperty "java.io.tmpdir") "pomegranate-test-tmp"))
(def tmp-remote-repo-dir (.getAbsolutePath (io/file tmp-dir "remote-repo")))
(def tmp-local-repo-dir (io/file tmp-dir "local-repo"))
(def tmp-local-repo2-dir (io/file tmp-dir "local-repo2"))

(def test-remote-repo {"central" "http://repo1.maven.org/maven2/"})

(def test-repo {"test-repo" "file://test-repo"})
(def tmp-remote-repo {"tmp-remote-repo" (str "file://" tmp-remote-repo-dir)})

(defn delete-recursive
  [dir]
  (when (.isDirectory dir)
    (doseq [file (.listFiles dir)]
      (delete-recursive file)))
  (.delete dir))

(defn- clear-tmp
  [f]
  (delete-recursive (io/file tmp-dir)) (f))

(use-fixtures :each clear-tmp)

(defn file-path-eq [file1 file2]
  (= (.getAbsolutePath file1)
     (.getAbsolutePath file2)))

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
    (is (= graph (aether/resolve-dependencies :coordinates deps :retrieve false :local-repo tmp-local-repo-dir)))
    (is (not (some #(-> % .getName (.endsWith ".jar")) (file-seq tmp-local-repo-dir))))

    (doseq [[dep _] (aether/resolve-dependencies :coordinates deps :local-repo tmp-local-repo-dir)]
      (is (-> dep meta :file))
      (is (-> dep meta :file .exists)))
    (is (some #(-> % .getName (.endsWith ".jar")) (file-seq tmp-local-repo-dir)))

    (is (= hierarchy (aether/dependency-hierarchy deps graph)))))

(deftest live-artifact-resolution
  (let [deps '[[commons-logging "1.1"]]]
    (is (= deps (aether/resolve-artifacts
                 :coordinates deps :retrieve false
                 :local-repo tmp-local-repo-dir)))
    (is (= 1 (count (aether/resolve-artifacts
                     :coordinates '[[demo "1.0.0"]] :retrieve false
                     :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")}
                     :local-repo tmp-local-repo-dir))))
    (is (not (some #(-> % .getName (.endsWith ".jar"))
                   (file-seq tmp-local-repo-dir))))
    (doseq [dep (aether/resolve-artifacts
                     :coordinates deps :local-repo tmp-local-repo-dir)]
      (is (-> dep meta :file))
      (is (-> dep meta :file .exists)))
    (is (some #(-> % .getName (.endsWith ".jar"))
              (file-seq tmp-local-repo-dir)))))

(deftest impl-detail-types
  (let [args [:coordinates '[[commons-logging "1.1"]] :local-repo tmp-local-repo-dir]]
    (is (instance? org.sonatype.aether.resolution.DependencyResult
          (apply aether/resolve-dependencies* args)))
    (is (instance? org.sonatype.aether.collection.CollectResult
          (apply aether/resolve-dependencies* :retrieve false args)))))

(deftest resolve-deps-with-proxy
  (let [deps (aether/resolve-dependencies :repositories test-remote-repo
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :proxy {:host "repo1.maven.org"  :port 80  :non-proxy-hosts "clojars.org"} 
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-mirror
  (let [deps (aether/resolve-dependencies :repositories {"clojars" "http://clojars.org/repo"}
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {"clojars" {:url "http://uk.maven.org/maven2"}}
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-wildcard-mirror
  (let [deps (aether/resolve-dependencies :repositories {"clojars" "http://clojars.org/repo"}
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {#".+" {:url "http://uk.maven.org/maven2"}}
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-wildcard-override-mirror
  (let [deps (aether/resolve-dependencies :repositories test-remote-repo
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {#".+" {:url "http://clojars.org/repo"}
                                                    (ffirst test-remote-repo) nil}
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps
  (let [deps (aether/resolve-dependencies :repositories test-repo
                                          :coordinates '[[demo/demo "1.0.0"]]
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-deps
  (let [deps (aether/resolve-dependencies :repositories test-repo
                                           :coordinates '[[demo/demo2 "1.0.0"]]
                                           :local-repo tmp-local-repo-dir)
        files (aether/dependency-files deps)]
    (is (= 2 (count files)))
    (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                            files))))
    (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
                            files))))))

(deftest resolve-unmanaged-dependencies
  (let [deps (aether/resolve-dependencies
              :repositories {}
              :coordinates '[[demo "1.0.0"]]
              :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")}
              :local-repo tmp-local-repo-dir)
        files (aether/dependency-files deps)]
    (is (= 1 (count files)))
    (is (= nil (:file (meta (first files)))))))

(deftest resolve-deps-with-exclusions
  (let [deps (aether/resolve-dependencies :repositories test-repo
                                          :coordinates
                                          '[[demo/demo2 "1.0.0" :exclusions [demo/demo]]]
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-classifiers
  (let [deps (aether/resolve-dependencies :repositories test-repo
                                          :coordinates
                                          '[[demo/demo "1.0.1" :classifier "test"]]
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo" "1.0.1" "demo-1.0.1-test.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-managed-dependencies
  (testing "supports coordinates w/o version number, with managed coordinates"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo]]
                :managed-coordinates '[[demo/demo "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))))
  (testing "supports coordinates w/o version number, with managed coordinates, w/o group-id"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo]]
                :managed-coordinates '[[demo "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files)))))
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo]]
                :managed-coordinates '[[demo/demo "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))))
  (testing "supports coordinates w/nil version number, with managed coordinates"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo nil]]
                :managed-coordinates '[[demo/demo "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))))
  (testing "supports coordinates w/nil version number and kwargs, with managed coordinates"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo2 nil :exclusions [demo/demo]]]
                :managed-coordinates '[[demo/demo2 "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
                              files))))))
  (testing "error if missing version number w/o managed coordinates"
    (is (thrown-with-msg? IllegalArgumentException #"Provided artifact is missing a version: \[demo/demo\]"
                          (aether/resolve-dependencies
                           :repositories test-repo
                           :coordinates '[[demo/demo]]
                           :local-repo tmp-local-repo-dir))))
  (testing "error if nil version number w/o managed coordinates"
    (is (thrown-with-msg? IllegalArgumentException #"Provided artifact is missing a version: \[demo/demo nil\]"
                          (aether/resolve-dependencies
                           :repositories test-repo
                           :coordinates '[[demo/demo nil]]
                           :local-repo tmp-local-repo-dir))))
  (testing "coordinates version number overrides managed coordinates version"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo "1.0.0"]]
                :managed-coordinates '[[demo/demo "0.0.1"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))))
  (testing "managed coordinates version is honored for transitive deps"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo2 "1.0.0"]]
                :managed-coordinates '[[demo/demo "1.0.1"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 2 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
                              files))))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.1" "demo-1.0.1.jar"))
                              files))))))
  (testing "unused entries in managed coordinates are not resolved"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo]]
                :managed-coordinates '[[demo/demo "1.0.0"]
                                       [demo/demo2 "1.0.0"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))))
  (testing "exclusions in managed coordinates are honored"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo2]]
                :managed-coordinates '[[demo/demo2 "1.0.0" :exclusions [demo/demo]]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 1 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
                              files))))))
  (testing "classifiers in managed coordinates are honored"
    (let [deps (aether/resolve-dependencies
                :repositories test-repo
                :coordinates '[[demo/demo]
                               [demo/demo nil :classifier "test"]]
                :managed-coordinates '[[demo/demo "1.0.0"]
                                       [demo/demo "1.0.1" :classifier "test"]]
                :local-repo tmp-local-repo-dir)
          files (aether/dependency-files deps)]
      (is (= 2 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.1" "demo-1.0.1-test.jar"))
                              files)))))))

(deftest deploy-jar
  (aether/deploy :coordinates '[group/artifact "1.0.0"]
                 :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                 :repository tmp-remote-repo
                 :local-repo tmp-local-repo-dir)
  (is (= 3 (count (.list (io/file tmp-remote-repo-dir "group" "artifact" "1.0.0"))))))

(deftest deploy-jar-with-pom
  (aether/deploy :coordinates '[group/artifact "1.0.0"]
                 :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                 :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
                 :repository tmp-remote-repo
                 :local-repo tmp-local-repo-dir)
  (is (= 6 (count (.list (io/file tmp-remote-repo-dir "group" "artifact" "1.0.0"))))))

(deftest deploy-jar-with-artifact-map
  (let [repo-file (partial io/file "test-repo" "demo" "demo" "1.0.0")]
    (aether/deploy
     :coordinates '[group/artifact "1.0.0"]
     :artifact-map {[] (repo-file "demo-1.0.0.pom")
                    [:extension "pom"] (repo-file "demo-1.0.0.pom")}
     :repository tmp-remote-repo
     :local-repo tmp-local-repo-dir))
  (is (= 6 (count (.list (io/file tmp-remote-repo-dir "group" "artifact" "1.0.0"))))))

(deftest install-jar
  (aether/install :coordinates '[group/artifact "1.0.0"]
                  :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
                  :local-repo tmp-local-repo-dir)
  (is (= 3 (count (.list (io/file tmp-local-repo-dir "group" "artifact" "1.0.0"))))))

(deftest install-jar-with-artifact-map
  (let [repo-file (partial io/file "test-repo" "demo" "demo" "1.0.0")]
    (aether/install
     :coordinates '[group/artifact "1.0.0"]
     :artifact-map {[] (repo-file "demo-1.0.0.jar")
                    [:extension "pom"] (repo-file "demo-1.0.0.pom")}
     :local-repo tmp-local-repo-dir))
  (is (= 3 (count (.list (io/file tmp-local-repo-dir "group" "artifact" "1.0.0"))))))

(deftest deploy-artifacts
  (aether/deploy-artifacts
   :artifacts '[[demo "1.0.0"]
                [demo "1.0.0" :extension "jar.asc"]
                [demo "1.0.0" :extension "pom"]
                [demo "1.0.0" :extension "pom.asc"]]
   ;; note: the .asc files in the test-repo are dummies, but it doesn't matter for this test
   :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
           '[demo "1.0.0" :extension "jar.asc"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar.asc")
           '[demo "1.0.0" :extension "pom"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
           '[demo "1.0.0" :extension "pom.asc"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom.asc")}
   :repository tmp-remote-repo
   :local-repo tmp-local-repo-dir)
  (is (= #{"demo-1.0.0.pom.md5"
           "demo-1.0.0.pom.sha1"
           "demo-1.0.0.pom"
           "demo-1.0.0.pom.asc.md5"
           "demo-1.0.0.pom.asc.sha1"
           "demo-1.0.0.pom.asc"
           "demo-1.0.0.jar.md5"
           "demo-1.0.0.jar.sha1"
           "demo-1.0.0.jar"
           "demo-1.0.0.jar.asc.md5"
           "demo-1.0.0.jar.asc.sha1"
           "demo-1.0.0.jar.asc"}
         (set (.list (io/file tmp-remote-repo-dir "demo" "demo" "1.0.0")))))
  (is (= '{[demo "1.0.0"] nil}
         (aether/resolve-dependencies :repositories tmp-remote-repo
                                      :coordinates
                                      '[[demo "1.0.0"]]
                                      :local-repo tmp-local-repo2-dir)))
  (is (= '{[demo "1.0.0" :extension "pom"] nil}
         (aether/resolve-dependencies :repositories tmp-remote-repo
                                      :coordinates
                                      '[[demo "1.0.0" :extension "pom"]]
                                      :local-repo tmp-local-repo2-dir)))
  (is (= '{[demo "1.0.0" :extension "jar.asc"] nil}
         (aether/resolve-dependencies :repositories tmp-remote-repo
                                      :coordinates
                                      '[[demo "1.0.0" :extension "jar.asc"]]
                                      :local-repo tmp-local-repo2-dir)))
  (is (= '{[demo "1.0.0" :extension "pom.asc"] nil}
         (aether/resolve-dependencies :repositories tmp-remote-repo
                                      :coordinates
                                      '[[demo "1.0.0" :extension "pom.asc"]]
                                      :local-repo tmp-local-repo2-dir))))

(deftest install-artifacts
  (aether/install-artifacts
    :artifacts '[[demo "1.0.0"]
                 [demo "1.0.0" :extension "jar.asc"]
                 [demo "1.0.0" :extension "pom"]
                 [demo "1.0.0" :extension "pom.asc"]]
   ;; note: the .asc files in the test-repo are dummies, but it doesn't matter for this test
   :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
           '[demo "1.0.0" :extension "jar.asc"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar.asc")
           '[demo "1.0.0" :extension "pom"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
           '[demo "1.0.0" :extension "pom.asc"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom.asc")}
   :local-repo tmp-local-repo-dir)
  (is (= #{"demo-1.0.0.jar"
           "demo-1.0.0.pom"
           "demo-1.0.0.jar.asc"
           "demo-1.0.0.pom.asc"
           "_maven.repositories"}
         (set (.list (io/file tmp-local-repo-dir "demo" "demo" "1.0.0"))))))

(deftest deploy-exceptions
  (is (thrown-with-msg? IllegalArgumentException #"Provided artifacts have varying"
        (aether/deploy-artifacts
          :artifacts '[[demo "1.0.0"]
                       [group/demo "1.0.0" :extension "jar.asc"]]
          :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  '[group/demo "1.0.0" :extension "jar.asc"]
                  (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar.asc")}
          :repository tmp-remote-repo
          :local-repo tmp-local-repo-dir)))
  (is (thrown-with-msg? IllegalArgumentException #"Provided artifacts have varying version, group, or artifact IDs"
        (aether/deploy-artifacts
          :artifacts '[[demo "1.0.0"]
                       [demo/artifact "1.0.0" :extension "jar.asc"]]
          :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  '[demo/artifact "1.0.0" :extension "jar.asc"]
                  (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar.asc")}
         :repository tmp-remote-repo
         :local-repo tmp-local-repo-dir)))
  (is (thrown-with-msg? IllegalArgumentException #"Provided artifacts have varying version, group, or artifact IDs"
        (aether/deploy-artifacts
          :artifacts '[[demo "1.0.0"]
                       [demo "1.1.0" :extension "jar.asc"]]
          :files {'[demo "1.0.0"] (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  '[demo "1.1.0" :extension "jar.asc"]
                  (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar.asc")}
         :repository tmp-remote-repo
         :local-repo tmp-local-repo-dir)))
  (is (thrown-with-msg? IllegalArgumentException #"Provided artifacts have varying version, group, or artifact IDs"
        (aether/deploy-artifacts
          :files {'[demo "1.0.0"]
                  (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  '[demo "1.1.0" :extension "jar.asc"]
                  (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")}
         :repository tmp-remote-repo
         :local-repo tmp-local-repo-dir))))

(deftest within?-comparisons
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "0.0.1"]))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo/demo "0.0.1"]))
  (is (aether/within? '[demo/demo "0.0.1"]
                       '[demo "0.0.1"]))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "[0.0.1,2.0.0)"]))
  (is (not (aether/within? '[demo "2.0.0"]
                            '[demo "[0.0.1,2.0.0)"])))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "(0.0.1,2.0.0)"])))
  (is (aether/within? '[demo "0.0.1-SNAPSHOT"]
                       '[demo/demo "0.0.1-SNAPSHOT"]))
  (is (aether/within? '[demo "0.0.1-SNAPSHOT"]
                       '[demo "0.0.1-SNAPSHOT"]))
  (is (aether/within? '[demo "0.0.1-20120403.012847-1"]
                       '[demo "0.0.1-SNAPSHOT"]))
  (is (not (aether/within? '[demo "0.0.1-SNAPSHOT"]
                            '[demo "0.0.1-20120403.012847-10"])))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "0.0.1" :extension "jar"]))
  (is (aether/within? '[demo "0.0.1" :extension "jar"]
                       '[demo "0.0.1"]))
  (is (not (aether/within? '[demo "0.0.1" :extension "pom"]
                            '[demo "0.0.1"])))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "0.0.1":extension "pom"])))
  (is (aether/within? '[demo "0.0.1" :classifier "sources"]
                       '[demo "0.0.1" :classifier "sources"]))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "0.0.1" :classifier "sources"])))
  (is (not (aether/within? '[demo "0.0.1" :classifier "sources"]
                            '[demo "0.0.1"])))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "0.0.1" :scope "compile"]))
  (is (aether/within? '[demo "0.0.1" :scope "compile"]
                       '[demo "0.0.1"]))
  (is (aether/within? '[demo "0.0.1" :scope "compile"]
                       '[demo "0.0.1" :scope "compile"]))
  (is (not (aether/within? '[demo "0.0.1" :scope "compile"]
                            '[demo "0.0.1" :scope "test"])))
  (is (not (aether/within? '[demo "0.0.1" :scope "test"]
                            '[demo "0.0.1" :scope "compile"])))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "0.0.1" :optional false]))
  (is (aether/within? '[demo "0.0.1" :optional false]
                       '[demo "0.0.1"]))
  (is (aether/within? '[demo "0.0.1" :optional true]
                       '[demo "0.0.1" :optional true]))
  (is (not (aether/within? '[demo "0.0.1" :optional true]
                            '[demo "0.0.1"])))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "0.0.1":optional true])))
  (is (aether/within? '[demo "0.0.1" :exclusions []]
                       '[demo "0.0.1"]))
  (is (aether/within? '[demo "0.0.1"]
                       '[demo "0.0.1" :exclusions []]))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2]]]
                       '[demo "0.0.1" :exclusions [[demo2]]]))
  (is (not (aether/within? '[demo "0.0.1" :exclusions [[demo2]]]
                            '[demo "0.0.1"])))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "0.0.1" :exclusions [[demo2]]])))
  (is (not (aether/within? '[demo "0.0.1" :exclusions [demo2]]
                            '[demo "0.0.1"])))
  (is (not (aether/within? '[demo "0.0.1"]
                            '[demo "0.0.1" :exclusions [demo2]])))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2]
                                                   [demo3]]]
                       '[demo "0.0.1" :exclusions [[demo2]
                                                   [demo3]]]))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo3]
                                                   [demo2]]]
                       '[demo "0.0.1" :exclusions [[demo2]
                                                   [demo3]]]))
  (is (not (aether/within? '[demo "0.0.1" :exclusions [[demo2]]]
                            '[demo "0.0.1" :exclusions [[demo2]
                                                        [demo3]]])))
  (is (not (aether/within? '[demo "0.0.1" :exclusions [[demo2]
                                                        [demo3]]]
                            '[demo "0.0.1" :exclusions [[demo2]]])))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2]]]
                       '[demo "0.0.1" :exclusions [[demo2 :classifier "*"]]]))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2 :classifier "*"]]]
                       '[demo "0.0.1" :exclusions [[demo2]]]))
  (is (not (aether/within?
            '[demo "0.0.1" :exclusions [[demo2 :classifier "*"]]]
            '[demo "0.0.1" :exclusions [[demo2 :classifier "sources"]]])))
  (is (not (aether/within?
            '[demo "0.0.1" :exclusions [[demo2 :classifier "sources"]]]
            '[demo "0.0.1" :exclusions [[demo2 :classifier "*"]]])))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2]]]
                       '[demo "0.0.1" :exclusions [[demo2 :extension "*"]]]))
  (is (aether/within? '[demo "0.0.1" :exclusions [[demo2 :extension "*"]]]
                       '[demo "0.0.1" :exclusions [[demo2]]]))
  (is (not (aether/within?
            '[demo "0.0.1" :exclusions [[demo2 :extension "*"]]]
            '[demo "0.0.1" :exclusions [[demo2 :extension "jar"]]])))
  (is (not (aether/within?
            '[demo "0.0.1" :exclusions [[demo2 :extension "jar"]]]
            '[demo "0.0.1" :exclusions [[demo2 :extension "*"]]]))))

(deftest dependency-hierarchy-matching
  (let [coords '[[demo/demo2 "[0.0.1,2.0.0)"]
                 [tester "0.1.0-SNAPSHOT"]]
        deps (aether/resolve-dependencies
              :repositories test-repo
              :coordinates coords
              :local-repo tmp-local-repo-dir)]
    (is (= {['demo/demo2 "1.0.0"] {['demo "1.0.0"] nil}
            ['tester "0.1.0-20120403.012847-1"] nil}
           (aether/dependency-hierarchy coords deps)))))

(deftest check-canonical-id
  (let [f @#'aether/canonical-id]
    (are [expect input] (= expect (f input)))
    'foo 'foo
    'foo 'foo/foo
    'foo/bar 'foo/bar))

(deftest check-conform-coord
  (let [f aether/conform-coord]
    (are [expect input] (= expect (f input))
                        nil nil
                        {:project 'foo} '[foo]
                        {:project 'foo/bar} '[foo/bar]
                        {:project 'foo} '[foo nil]
                        {:project 'foo, :version "1.2.3"} '[foo "1.2.3"]
                        {:project 'foo, :version "1.2.3", :scope "test"} '[foo "1.2.3" :scope "test"]
                        {:project 'foo, :scope "test"} '[foo :scope "test"]
                        {:project 'foo, :scope "test"} '[foo nil :scope "test"]
                        {:project 'foo, :scope nil} '[foo nil :scope nil]
                        {:project 'foo, :scope "test", :optional true} '[foo :scope "test" :optional true]
                        {:project 'foo, :version "1.2.3" :exclusions '[[bar]]} '[foo "1.2.3" :exclusions [[bar]]])))

(deftest check-unform-coord
  (let [f aether/unform-coord]
    (are [expect input] (= expect (f input))
                        '[foo] {:project 'foo}
                        '[foo/bar] {:project 'foo/bar}
                        '[foo "1.2.3"] {:project 'foo, :version "1.2.3"}
                        '[foo "1.2.3" :scope "test"] {:project 'foo, :version "1.2.3", :scope "test"}
                        '[foo :scope "test"] {:project 'foo, :scope "test"}
                        '[foo :scope nil] {:project 'foo, :scope nil}

                        '[foo :scope "test"] {:project 'foo, :scope "test"}
                        '[foo] {:project 'foo, :scope "compile"}
                        '[foo :optional true] {:project 'foo, :optional true}
                        '[foo] {:project 'foo, :optional false}
                        '[foo :extension "zip"] {:project 'foo, :extension "zip"}
                        '[foo] {:project 'foo, :extension "jar"}

                        '[foo "1.2.3" :exclusions [[bar]]] {:project 'foo, :version "1.2.3" :exclusions '[[bar]]})))

(deftest check-merge-managed-coord
  (let [f @#'aether/merge-managed-coord
        managed-coords-map @#'aether/managed-coords-map
        managed-coords-m (managed-coords-map
                           '[[demo "1.0.0"]
                             [demo-test "2.0.0" :scope "test"]
                             [demo-compile "3.0.0" :scope "compile"]
                             [demo-excl "4.0.0" :exclusions [[demo] [demo-test]]]])]
    (is (thrown-with-msg? IllegalArgumentException #"Provided artifact is missing a version: "
                          (f nil nil)))
    (is (thrown-with-msg? IllegalArgumentException #"Provided artifact is missing a version: \[demo-unk\]"
                                                                      (f nil '[demo-unk])))
    (is (= '[demo "1.0.0"] (f nil '[demo/demo "1.0.0"])))
    (is (= '[demo "1.0.0"] (f managed-coords-m '[demo])))
    (is (= '[demo "1.0.0"] (f managed-coords-m '[demo nil])))
    (is (= '[demo-test "2.0.0" :scope "test"] (f managed-coords-m '[demo-test])))
    (is (= '[demo-test "2.0.0" :scope "provided"] (f managed-coords-m '[demo-test :scope "provided"])))
    (is (= '[demo-test "2.0.0"] (f managed-coords-m '[demo-test :scope "compile"])))
    (is (= '[demo-test "2.0.0"] (f managed-coords-m '[demo-test :scope nil])))
    (is (= '[demo-compile "3.0.0"] (f managed-coords-m '[demo-compile])))
    (is (= '[demo-excl "4.0.0" :exclusions [[demo] [demo-test]]] (f managed-coords-m '[demo-excl])))
    (is (= '[demo-excl "4.0.0"] (f managed-coords-m '[demo-excl :exclusions nil])))))

;; simple functionality test; edge cases checked in underlying functions
(deftest check-merge-versions-from-managed-coords
  (let [canonical-form #(-> % aether/conform-coord aether/unform-coord)

        managed-coords '[[demo "1.0.0"]
                         [demo-test "2.0.0" :scope "test"]
                         [demo-provided "3.0.0" :scope "provided"]
                         [demo-compile "3.0.1" :scope "compile"]
                         [demo-excl "4.0.0" :exclusions [[demo] [demo-test]]]]
        local-coords '[[foo "1.0.0" :scope "test"]
                       [foo/bar "2.0.0" :exclusions [[demo]] :scope "compile"]]

        coords (concat local-coords '[[demo]
                                      [demo-test "2.0.0"]
                                      [demo-provided]
                                      [demo-compile]
                                      [demo-excl]])
        merged-coords (aether/merge-versions-from-managed-coords coords managed-coords)
        expected-coords (map canonical-form (concat managed-coords local-coords))]
    (is (= (set merged-coords) (set expected-coords)))))

(comment
  "tests needed for:
  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
