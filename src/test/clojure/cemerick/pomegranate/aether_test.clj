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

(def test-remote-repo {"central" "https://repo1.maven.org/maven2/"})

(def test-repo {:test-repo "file://test-repo"})
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
    (is (instance? org.eclipse.aether.resolution.DependencyResult
          (apply aether/resolve-dependencies* args)))
    (is (instance? org.eclipse.aether.collection.CollectResult
          (apply aether/resolve-dependencies* :retrieve false args)))))

(deftest resolve-deps-with-mirror
  (let [deps (aether/resolve-dependencies :repositories {"clojars" "https://clojars.org/repo"}
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {"clojars" {:url "https://maven-central.storage.googleapis.com"}}
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-wildcard-mirror
  (let [deps (aether/resolve-dependencies :repositories {"clojars" "https://clojars.org/repo"}
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {#".+" {:url "https://maven-central.storage.googleapis.com"}}
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax" "servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest resolve-deps-with-wildcard-override-mirror
  (let [deps (aether/resolve-dependencies :repositories test-remote-repo
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :mirrors {#".+" {:url "https://clojars.org/repo"}
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

(java.lang.System/setProperty "aether.checksums.forSignature" "true")

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
         (set (.list (io/file tmp-remote-repo-dir "demo" "demo" "1.0.0")))) "Should deploy correctly demo \"1.0.0\"")
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
           "_remote.repositories"}
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

;; taken from the test suite for the pendantic lib by Nelson Morris

(defn get-versions [name repo]
  (let [name (symbol name)]
    (map second (filter #(= name (first %)) (keys repo)))))

(defn make-pom-string [name version deps]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
  <project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion>
  <groupId>" name "</groupId>
  <artifactId>" name "</artifactId>
  <packaging>jar</packaging>
  <version>" version "</version>
  <name>" name "</name>"
  (if-not (empty? deps)
    (apply str
           "<dependencies>"
           (clojure.string/join "\n"
                                (for [[n v] deps]
                                  (str "<dependency>
                   <groupId>" n "</groupId>
                   <artifactId>"n"</artifactId>
                   <version>"v"</version>
                   </dependency>")))
           "</dependencies>"))
  " </project>"))

(defn make-metadata [name versions]
  (str "<metadata>
  <groupId>" name "</groupId>
  <artifactId>" name "</artifactId>
  <versioning>
  <versions>"
  (clojure.string/join "\n"
                       (for [v versions]
                         (str "<version>"v"</version>")))
    "</versions>
    <lastUpdated>20120810193549</lastUpdated>
  </versioning>
  </metadata>"))

(def fake-repo
  '{[a "1"] []
    [a "2"] []
    [aa "2"] [[a "2"]]})

(deftest register-fake-wagon
  (aether/register-wagon-factory!
     "fake"
     #(reify org.apache.maven.wagon.Wagon
        (getRepository [_]
          (proxy [org.apache.maven.wagon.repository.Repository] []))
        (^void connect [_
                        ^org.apache.maven.wagon.repository.Repository _
                        ^org.apache.maven.wagon.authentication.AuthenticationInfo _
                        ^org.apache.maven.wagon.proxy.ProxyInfoProvider _])
        (disconnect [_])
        (removeTransferListener [_ _])
        (addTransferListener [_ _])
        (setTimeout [_ _])
        (setInteractive [_ _])
        (get [_ name file]
          (let [[n _ version] (clojure.string/split name #"/")]
            (if (= name (str n "/" n "/maven-metadata.xml"))
              (if-let [versions (get-versions n fake-repo)]
                (spit file (make-metadata n versions))
                (spit file ""))
              (if-let [deps (fake-repo [(symbol n) version])]
                (if (re-find #".pom$" name)
                  (spit file (make-pom-string n version deps))
                  (spit file ""))
                (throw (org.apache.maven.wagon.ResourceDoesNotExistException. ""))))))))

  (let [tmp-local-repo-dir (io/file tmp-dir "local-repo")]
    (aether/resolve-dependencies :coordinates '[[a "1"]]
                                 :repositories {"test-repo"
                                                {:url "fake://ss"
                                                 :checksum :ignore}}
                                 :local-repo tmp-local-repo-dir)
    (is (= #{"local-repo" "a" "1" "a-1.pom" "_remote.repositories" "a-1.jar"}
           (set (map (memfn getName) (file-seq tmp-local-repo-dir)))))))

(comment
  "tests needed for:
  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
