(ns cemerick.pomegranate.test-utils
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]))

(def ^:private tmp-dir
  (io/file (System/getProperty "java.io.tmpdir") "pomegranate-test-tmp"))
(def ^:private tmp-remote-repo-dir
  (.getAbsolutePath (io/file tmp-dir "remote-repo")))
(def ^:private tmp-local-repo-dir
  (io/file tmp-dir "local-repo"))

(def test-repo
  {"test-repo" "file://test-repo"})
(def tmp-remote-repo
  {"tmp-remote-repo" (str "file://" tmp-remote-repo-dir)})

(defn- delete-recursive
  [dir]
  (when (.isDirectory dir)
    (doseq [file (.listFiles dir)]
      (delete-recursive file)))
  (.delete dir))

(defn clear-tmp
  [f]
  (delete-recursive (io/file tmp-dir))
  (f))

(defn bind-local-repo
  [f]
  (binding [aether/*local-repo* tmp-local-repo-dir]
    (f)))

(defn local-repo-files
  ([]
     (file-seq tmp-local-repo-dir))
  ([group-id artifact-id version]
     (.list (io/file tmp-local-repo-dir group-id artifact-id version))))

(defn remote-repo-files
  ([]
     (file-seq tmp-local-repo-dir))
  ([group-id artifact-id version]
     (.list (io/file tmp-remote-repo-dir group-id artifact-id version))))

(defn- repo-file
  [repo group-id artifact-id version extension]
  (io/file
   repo group-id artifact-id version
   (format "%s-%s.%s" artifact-id version extension)))

(defn local-repo-file
  ([group-id artifact-id version extension]
     (repo-file tmp-local-repo-dir group-id artifact-id version extension))
  ([group-id artifact-id version]
     (local-repo-file group-id artifact-id version "jar")))

(defn test-repo-file
  ([group-id artifact-id version extension]
     (repo-file "test-repo" group-id artifact-id version extension))
  ([group-id artifact-id version]
     (test-repo-file group-id artifact-id version "jar")))

(defn jar-file?
  [f]
  (-> f .getName (.endsWith ".jar")))

(defn file-path-matcher [file2]
  (fn [file1]
    (= (.getAbsolutePath file1)
       (.getAbsolutePath file2))))
