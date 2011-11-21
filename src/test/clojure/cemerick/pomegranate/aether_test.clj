(ns cemerick.pomegranate.aether-test
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io])
  (:use [clojure.test]))

(def tmp-dir (System/getProperty "java.io.tmpdir"))

(def test-repo {"test-repo" "file://test-repo"})
(def tmp-remote-repo {"tmp-remote-repo" (str "file://" (.getAbsolutePath (io/file tmp-dir "remote-repo")))})

(defn delete-recursive
  [dir]
  (when (.isDirectory dir)
    (doseq [file (.listFiles dir)]
      (delete-recursive file)))
  (.delete dir))

(defn clear-tmp
  [f] (delete-recursive (io/file tmp-dir)) (f))

(use-fixtures :each clear-tmp)

(deftest resolve-deps
  (binding [aether/*local-repo* (io/file tmp-dir "local-repo")]
    (let [files (aether/resolve-dependencies :repositories test-repo
                                             :coordinates
                                             '[[demo/demo "1.0.0"]])]
      (is (= 1 (count files)))
      (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
             (.getAbsolutePath (first files)))))))

(defn file-path-eq [file1 file2]
  (= (.getAbsolutePath file1)
     (.getAbsolutePath file2)))

(deftest resolve-deps-with-deps
  (binding [aether/*local-repo* (io/file tmp-dir "local-repo")]
    (let [files (aether/resolve-dependencies :repositories test-repo
                                             :coordinates
                                             '[[demo/demo2 "1.0.0"]])]
      (is (= 2 (count files)))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar"))
                              files))))
      (is (= 1 (count (filter #(file-path-eq % (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
                              files)))))))

(deftest resolve-deps-with-exclusions
  (binding [aether/*local-repo* (io/file tmp-dir "local-repo")]
    (let [files (aether/resolve-dependencies :repositories test-repo
                                             :coordinates
                                             '[[demo/demo2 "1.0.0" :exclusions [demo/demo]]])]
      (is (= 1 (count files)))
      (is (= (.getAbsolutePath (io/file tmp-dir "local-repo" "demo" "demo2" "1.0.0" "demo2-1.0.0.jar"))
             (.getAbsolutePath (first files)))))))

(deftest deploy-jar
  (aether/deploy :coordinates '[group/artifact "1.0.0"]
                 :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                 :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom")
                 :repository tmp-remote-repo)
  (let [files (.list (io/file tmp-dir "remote-repo" "group" "artifact" "1.0.0"))]
    (is (= 6 (count files)))))

(deftest install-jar
  (binding [aether/*local-repo* (io/file tmp-dir "local-repo")]
    (aether/install :coordinates '[group/artifact "1.0.0"]
                    :jar-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.jar")
                    :pom-file (io/file "test-repo" "demo" "demo" "1.0.0" "demo-1.0.0.pom"))
    (let [files (.list (io/file tmp-dir "local-repo" "group" "artifact" "1.0.0"))]
      (is (= 3 (count files))))))


(comment
  "tests needed for:
  
  repository authentication
  repository policies
  dependency options (scope/optional)
  exclusion options (classifier/extension)")
