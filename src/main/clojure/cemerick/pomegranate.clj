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

(def ^{:private true} dynamic-classloaders #{DynamicClassLoader URLClassLoader})

(defn- find-eldest-classloader
  ([]
    (find-eldest-classloader (clojure.lang.RT/baseLoader)))
  ([tip]
    (when (dynamic-classloaders (class tip))
      (or (find-eldest-classloader (.getParent tip)) tip))))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the current thread's context classloader (by default)."
  ([jar-or-dir classloader]
    (let [url (.toURL (io/file jar-or-dir))]
      (cond
        (instance? DynamicClassLoader classloader) (.addURL classloader url)
        (instance? URLClassLoader) (call-method URLClassLoader 'addURL [URL] classloader url)
        :else (throw (IllegalStateException.
                       (format "Thread context classloader is of type %s; needs to be a DynamicClassLoader or URLClassLoader"
                               (class classloader)))))))
  ([jar-or-dir]
    (if-let [classloader (find-eldest-classloader)]
      (add-classpath jar-or-dir classloader)
      (throw (IllegalStateException. "Could not find a DynamicClassLoader or URLClassLoader to modify")))))

(defn add-dependencies
  "Resolves a set of dependencies, optionally against a set of additional Maven repositories
   (Maven central is used when blank), and adds all of the resulting artifacts
   (jar files) to the current runtime via `cemerick.pomegranate/add-classpath`.  e.g.

   (add-dependencies '[[incanter \"1.2.3\"]]
                     :repositories {\"clojars\" \"http://clojars.org/repo\"})"
  [coordinates & {:keys [repositories]}]
  (doseq [artifact-file (aether/resolve-dependencies
                         :coordinates coordinates
                         :repositories repositories)]
    (add-classpath artifact-file)))
