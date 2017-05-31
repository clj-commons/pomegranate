(ns cemerick.pomegranate
  (:import (clojure.lang DynamicClassLoader)
           (java.net URL URLClassLoader))
  (:require [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether]
            [dynapath.util :as dp])
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
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip]
    (->> tip
      (iterate #(.getParent %))
      (take-while boolean))))

(defn modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
     (if-not (dp/add-classpath-url classloader (.toURL (.toURI (io/file jar-or-dir))))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn add-dependencies
  "Resolves a set of dependencies, optionally against a set of additional Maven
   repositories, and adds all of the resulting artifacts (jar files) to the
   current runtime via `add-classpath`:

   (add-dependencies :classloader your-classloader
                     :coordinates '[[incanter \"1.2.3\"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                     {\"clojars\" \"https://clojars.org/repo\"}))

   Note that the `:classloader` kwarg is optional; if not provided then resolved
   dependencies will be added to the closest modifiable classloader in the
   current thread's hierarchy, as per `add-classpath`.

   Otherwise, acceptable arguments are the same as those for
   `cemerick.pomegranate.aether/resolve-dependencies`; returns the dependency graph
   returned from that function.

   Note that Maven central is used as the sole repository if none are specified.
   If :repositories are provided, then you must merge in the `maven-central` map from
   the cemerick.pomegranate.aether namespace yourself."
  [& args]
  (let [classloader (-> (apply hash-map args)
                        :classloader
                        ; replace with some-> when we bump the clojure dep
                        (#(when % [%])))
        deps (apply aether/resolve-dependencies args)]
    (doseq [artifact-file (aether/dependency-files deps)]
      (apply add-classpath artifact-file classloader))
    deps))

(defn get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of classloaders if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   (get-classpath (drop 2 (classloader-hierarchy)))"
  ([classloaders]
    (->> (reverse classloaders)
      (mapcat #(dp/classpath-urls %))
      (map str)))
  ([] (get-classpath (classloader-hierarchy))))

(defn classloader-resources
  "Returns a sequence of [classloader url-seq] pairs representing all
   of the resources of the specified name on the classpath of each
   classloader. If no classloaders are given, uses the
   classloader-heirarchy, in which case the order of pairs will be
   such that the first url mentioned will in most circumstances match
   what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for
   finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (distinct (mapcat second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))
