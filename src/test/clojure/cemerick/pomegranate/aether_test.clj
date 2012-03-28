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

(deftest online-resolve-deps-with-proxy
  (let [deps (aether/resolve-dependencies :repositories test-remote-repo
                                          :coordinates '[[javax.servlet/servlet-api "2.5"]]
                                          :proxy {:host "repo1.maven.org"  :port 80  :non-proxy-hosts "clojars.org"} 
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "javax/servlet" "servlet-api" "2.5" "servlet-api-2.5.jar"))

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

(deftest resolve-deps-with-exclusions
  (let [deps (aether/resolve-dependencies :repositories test-repo
                                          :coordinates
                                          '[[demo/demo2 "1.0.0" :exclusions [demo/demo]]]
                                          :local-repo tmp-local-repo-dir)]
    (is (= 1 (count deps)))
    (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
           (.getAbsolutePath (first (aether/dependency-files deps)))))))

(deftest deploy-jar
  (aether/deploy :coordinates '[group/artifact "1.0.0"]
                 :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                 :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
                 :repository tmp-remote-repo
                 :local-repo tmp-local-repo-dir)
  (is (= 6 (count (.list (io/file tmp-remote-repo-dir "group" "artifact" "1.0.0"))))))

(deftest install-jar
  (aether/install :coordinates '[group/artifact "1.0.0"]
                  :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                  :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
                  :local-repo tmp-local-repo-dir)
  (is (= 3 (count (.list (io/file tmp-local-repo-dir "group" "artifact" "1.0.0"))))))


(comment
  "tests needed for:
  
  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
