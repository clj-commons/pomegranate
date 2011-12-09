(ns cemerick.pomegranate
  (:import (clojure.lang DynamicClassLoader)
           (java.net URL URLClassLoader))
  (:require [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether])
  (:refer-clojure :exclude (add-classpath)))

;; call-method pulled from clojure.contrib.reflect, (c) 2010 Stuart Halloway & Contributors
(defn- call-method
  "Calls a private or protected method.

  params is a vector of classes which correspond to the arguments to
  the method e

  obj is nil for static methods, the instance object otherwise.

  The method-name is given a symbol or a keyword (something Named)."
  [klass method-name params obj & args]
  (-> klass (.getDeclaredMethod (name method-name)
                                (into-array Class params))
    (doto (.setAccessible true))
    (.invoke obj (into-array Object args))))

(defprotocol AddURL
  "Ability to dynamically add urls to classloaders"
  (add-url [this url] "add the url to the classpath"))

(extend-type DynamicClassLoader AddURL
  (add-url [this url] (.addURL this url)))

(extend-type URLClassLoader AddURL
  (add-url [this url] (call-method URLClassLoader 'addURL [URL] this url)))

(defn- find-eldest-classloader
  ([]
    (find-eldest-classloader (clojure.lang.RT/baseLoader)))
  ([tip]
     (when (satisfies? AddURL tip)
       (or (find-eldest-classloader (.getParent tip)) tip))))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the current thread's context classloader (by default)."
  ([jar-or-dir classloader]
    (let [url (.toURL (io/file jar-or-dir))]
      (add-url classloader url)))
  ([jar-or-dir]
    (if-let [classloader (find-eldest-classloader)]
      (add-classpath jar-or-dir classloader)
      (throw (IllegalStateException. "Could not find a DynamicClassLoader or URLClassLoader to modify")))))

(defn add-dependencies
  "Resolves a set of dependencies, optionally against a set of additional Maven repositories
   (Maven central is used when blank), and adds all of the resulting artifacts
   (jar files) to the current runtime via `cemerick.pomegranate/add-classpath`.  e.g.

   (add-dependencies '[[incanter \"1.2.3\"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                          {\"clojars\" \"http://clojars.org/repo\"}))

   (Note that Maven central is used as the sole repository if none are specified.
    If :repositories are provided, then you must merge in the `maven-central` map from
    the cemerick.pomegranate.aether namespace yourself.)"
  [coordinates & {:keys [repositories]}]
  (doseq [artifact-file (aether/resolve-dependencies
                          :coordinates coordinates
                          :repositories repositories)]
    (add-classpath artifact-file)))
