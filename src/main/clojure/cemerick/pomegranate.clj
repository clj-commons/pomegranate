(ns cemerick.pomegranate
  "Classpath and class loader tools."
  (:require [clojure.java.io :as io]
            [cemerick.pomegranate.aether :as aether]
            [dynapath.util :as dp])
  (:refer-clojure :exclude (add-classpath)))

(defn classloader-hierarchy
  "Returns a seq of class loaders, with the `tip` of the hierarchy first.
   The `tip` defaults to the current thread context class loader."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip]
    (->> tip
         (iterate #(.getParent ^ClassLoader %))
         (take-while boolean))))

(defn modifiable-classloader?
  "Returns `true` iff the given `ClassLoader` is of a type that satisfies
   the `dynapath.dynamic-classpath/DynamicClasspath` protocol, and can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn add-classpath
  "A corollary to the deprecated `add-classpath` in `clojure.core`.

   Attempt to add `jar-or-dir`, a string or `java.io.File`, to `classloader`.

   When `classloader` is not provided, searches for a modifiable classloader rooted at the current thread's context classloader."
  ([jar-or-dir classloader]
     (when-not (dp/add-classpath-url classloader (.toURL (.toURI (io/file jar-or-dir))))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn add-dependencies
  "Resolves dependency `:coordinates` against Maven `:repositories`, then adds all
   the resulting artifacts (jar files) to the current runtime via [[add-classpath]]:

   ```Clojure
   (add-dependencies :classloader your-classloader
                     :coordinates '[[incanter \"1.9.2\"]]
                     :repositories (merge cemerick.pomegranate.aether/maven-central
                                          {\"clojars\" \"https://clojars.org/repo\"}))
   ```
 
   kwarg options:
   - `:classloader` - (optional, defaults to closest modifiable classloader in current thread's
   hierarchy as per [[add-classpath]])
   - Otherwise, kwargs are the same as [[cemerick.pomegranate.aether/resolve-dependencies]]

   Returns the dependency graph from [[cemerick.pomegranate.aether/resolve-dependencies]]."
  [& kwargs]
  (let [classloader (-> (apply hash-map kwargs)
                        :classloader
                        ; replace with some-> when we bump the clojure dep
                        (#(when % [%])))
        deps (apply aether/resolve-dependencies kwargs)]
    (doseq [artifact-file (aether/dependency-files deps)]
      (apply add-classpath artifact-file classloader))
    deps))

(defn get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   `(System/getProperty \"java.class.path\")` as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of `classloaders` if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   ```Clojure
   (get-classpath (drop 2 (classloader-hierarchy)))
   ```"
  ([classloaders]
    (->> (reverse classloaders)
         (mapcat #(dp/classpath-urls %))
         (map str)))
  ([] (get-classpath (classloader-hierarchy))))

(defn classloader-resources
  "Returns a sequence of `[java.lang.ClassLoader [java.net.URL ...]]` pairs
   for all found `resource-name`s on the classpath of each classloader.

   If no `classloaders` are given, uses the [[classloader-hierarchy]].
   In this case, the first URL will in most circumstances match what
   what `clojure.java.io/resource` returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn resources
  "Returns a sequence of `java.net.URL`s on the effective classpath for specified specified `resource-name`.
   This can be useful for finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what `clojure.java.io/resource` returns."
  ([classloaders resource-name]
     (distinct (mapcat second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))
