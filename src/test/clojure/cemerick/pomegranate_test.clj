(ns cemerick.pomegranate-test
  (:require [cemerick.pomegranate :as p]
            [cemerick.pomegranate.test-report]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]))

(def java-version-major (-> (System/getProperty "java.version") (string/split #"\.") first Integer/parseInt))

(deftest resources
  (let [r (first (p/resources "META-INF/MANIFEST.MF"))]
    (is (not (nil? r))
        "first pomegranate resource match is not nil")

    (is (= r
           (io/resource "META-INF/MANIFEST.MF"))
        "first pomegranate resources match is the same as java resource match"))
  
  (let [^ClassLoader platform-classloader (last (p/classloader-hierarchy))
        class-name (-> platform-classloader .getClass .getSimpleName) ]
    (if (< java-version-major 9)
      (is (= "ExtClassLoader" class-name)
          "last class loader is extension classloader (for jdk versions < 9)")
      (is (= "PlatformClassLoader" class-name)
          "last class loader is platform classloader (for jdk versions >= 9)"))

    (is (->> (p/resources [platform-classloader] "META-INF/MANIFEST.MF")
             (map str)
             (filter #(.contains ^String % "clojure"))
             empty?)
        "no clojure manifest resources should be found on platform classloader"))
  
  (is (->> (p/resources (butlast (p/classloader-hierarchy)) "META-INF/MANIFEST.MF")
           (map str)
           (filter #(.contains ^String % "clojure"))
           seq)
      "clojure manifests should be found when searching classloaders"))

(deftest get-classpath
  (if (< java-version-major 9)
    (is (seq (p/get-classpath))
        "works for jdk versions < 9")
    (is (empty? (p/get-classpath))
        "always empty seq for jdk versions >= 9")))
