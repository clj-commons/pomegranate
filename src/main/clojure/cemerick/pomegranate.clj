(ns cemerick.pomegranate
  (:import (clojure.lang DynamicClassLoader)
           (java.net URL URLClassLoader))
  (:require [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether]
            [dynapath.core :as dp])
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

(defn classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses (clojure.lang.RT/baseLoader) -- which by default will be the
   current thread context ClassLoader -- as the tip ClassLoader if one is
   not provided."
  ([] (classloader-hierarchy (clojure.lang.RT/baseLoader)))
  ([tip]
    (->> tip
      (iterate #(.getParent %))
      (take-while boolean))))

(defn modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.core/DynamicClasspath protocol, and it can be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
     (if (modifiable-classloader? classloader)
       (dp/add-classpath-url classloader (.toURL (io/file jar-or-dir)))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn add-dependencies
  "Resolves a set of dependencies, optionally against a set of additional Maven repositories,
   and adds all of the resulting artifacts (jar files) to the current runtime via
   `add-classpath`:

   (add-dependencies :coordinates '[[incanter \"1.2.3\"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                     {\"clojars\" \"http://clojars.org/repo\"}))

   Note that Maven central is used as the sole repository if none are specified.
   If :repositories are provided, then you must merge in the `maven-central` map from
   the cemerick.pomegranate.aether namespace yourself.

   Acceptable arguments are the same as those for
   `cemerick.pomegranate.aether/resolve-dependencies`; returns the dependency graph
   returned from that function."
  [& args]
  (let [deps (apply aether/resolve-dependencies args)]
    (doseq [artifact-file (aether/dependency-files deps)]
      (add-classpath artifact-file))
    deps))

(defn get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of classloaders if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   (get-classpath (drop 2 (classloader-hierarchy)))"
  ([classloaders]
    []
    (->> (reverse classloaders)
      (mapcat #(when (dp/readable-classpath? %) (dp/classpath-urls %)))
      (map str)))
  ([] (get-classpath (classloader-hierarchy))))

