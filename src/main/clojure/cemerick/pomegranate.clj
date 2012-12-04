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

(defprotocol URLClasspath
  "Ability to dynamically add urls to classloaders.

This protocol is an implementation detail.  Use
`modifiable-classloader?` and `add-classpath` or `add-dependencies`
unless you are extending a type to this protocol."
  (^{:private true} can-modify? [this] "Returns true if the given classloader can be modified.")
  (^{:private true} add-url [this url] "add the url to the classpath"))

(extend-type DynamicClassLoader
  URLClasspath
  (can-modify? [this] true)
  (add-url [this url] (.addURL this url)))

(def ^:private url-classloader-base
  {:can-modify? (constantly true)
   :add-url (fn [this url]
              (call-method URLClassLoader 'addURL [URL] this url))})

(extend URLClassLoader URLClasspath url-classloader-base)

(defmacro when-resolves
  [sym & body]
  (when (resolve sym) `(do ~@body)))

(when-resolves sun.misc.Launcher             
  (extend sun.misc.Launcher$ExtClassLoader URLClasspath
    (assoc url-classloader-base
           :can-modify? (constantly false))))

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
   the URLClasspath protocol, and it can be modified."
  [cl]
  (and (satisfies? URLClasspath cl)
       (can-modify? cl)))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
    (add-url classloader (.toURL (io/file jar-or-dir))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. "Could not find a suitable classloader to modify from " classloaders))))))

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
    (->> (reverse classloaders)
      (mapcat #(when (instance? URLClassLoader %) (.getURLs %)))
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
       [classloader
        (map str
             (enumeration-seq
              (.getResources ^ClassLoader classloader resource-name)))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for
   finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (distinct (map second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))
