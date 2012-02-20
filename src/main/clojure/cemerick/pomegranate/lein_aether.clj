(ns cemerick.pomegranate.lein-aether
  "Lein dependency spec based interface"
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clojure.java.io :as io]
   clojure.set))

(defn- group
  "return the group id of the `group-artifact` symbol"
  [group-artifact]
  (or (namespace group-artifact) (name group-artifact)))

;;; conversion from a lein style dependency spec
(defn- lein-exclusion
  [[group-artifact & {:keys [extension classifier] :as opts}
    :as dep-spec]]
  (merge
   opts
   {:group-id (group group-artifact)
    :artifact-id (name group-artifact)}))

(defn- lein-dependency
  [[group-artifact version & {:keys [scope optional exclusions]
                              :as opts
                              :or {scope "compile"
                                   optional false}}
    :as dep-spec]]
  (merge
   opts
   {:group-id (group group-artifact)
    :artifact-id (name group-artifact)
    :version version
    :scope scope
    :optional optional
    :exclusions (map lein-exclusion exclusions)}))

;;; conversion to a lein style dependency spec
(defn- lein-spec
  "Return a lein-style dependency spec vector for a dependency or exclusion."
  [{:keys [group-id artifact-id version classifier extension scope optional
           exclusions]
    :or {version nil
         scope "compile"
         optional false
         exclusions nil}
    :as spec}]
  (let [group-artifact (apply symbol (if (= group-id artifact-id)
                                       [artifact-id]
                                       [group-id artifact-id]))]
    (->
     (concat [group-artifact]
             (when version [version])
             (when (and (seq classifier) (not= "*" classifier))
               [:classifier classifier])
             (when (and (seq extension) (not (#{"*" "jar"} extension)))
               [:extension extension])
             (when optional [:optional true])
             (when (not= scope "compile")
               [:scope scope])
             (when (seq exclusions)
               [:exclusions (vec (map lein-spec exclusions))]))
     vec
     (with-meta (meta spec)))))

;;; algorithms that consume and produce lein style dependency vectors
(defn deploy
    "Deploy the jar-file kwarg using the pom-file kwarg and coordinates kwarg to
the repository kwarg.

coordinates - [group/name \"version\"]

jar-file - a file pointing to the jar

pom-file - a file pointing to the pom

repository - {name url} | {name settings}
settings:
  :url - URL of the repository
  :snapshots - use snapshots versions? (default true)
  :releases - use release versions? (default true)
  :username - username to log in with
  :password - password to log in with
  :passphrase - passphrase to log in wth
  :private-key-file - private key file to log in with
  :update - :daily (default) | :always | :never
  :checksum - :fail (default) | :ignore | :warn"
  [& {:keys [coordinates jar-file pom-file repository] :as options}]
  (apply
   aether/deploy
   :coordinates (lein-dependency coordinates)
   (apply concat (dissoc options :coordinates))))

(defn install
  "Install the jar-file kwarg using the pom-file kwarg and coordinates kwarg.

coordinates - [group/name \"version\"]

jar-file - a file pointing to the jar

pom-file - a file pointing to the pom"
  [& {:keys [coordinates jar-file pom-file] :as options}]
  (apply
   aether/install
   :coordinates (lein-dependency coordinates)
   (apply concat (dissoc options :coordinates))))

(defn resolve-dependencies
  "Collects dependencies for the coordinates kwarg, using repositories from the repositories kwarg.
   Returns a graph of dependencies; each dependency's metadata contains the source Aether
   Dependency object, and the dependency's :file on disk.  Retrieval of dependencies
   can be disabled by providing `:retrieve false` as a kwarg.

coordinates - [[group/name \"version\" & settings] ..]
settings:
  :scope - the maven scope for the dependency (default \"compile\")
  :optional? - is the dependency optional? (default \"false\")
  :exclusions - which sub-dependencies to skip : [group/name & settings]
    settings:
      :classifier (default \"*\")
      :extension  (default \"*\")

repositories - {name url ..} | {name settings ..} (default {\"central\" \"http://repo1.maven.org/maven2/\"}
settings:
  :url - URL of the repository
  :snapshots - use snapshots versions? (default true)
  :releases - use release versions? (default true)
  :username - username to log in with
  :password - password to log in with
  :passphrase - passphrase to log in wth
  :private-key-file - private key file to log in with
  :update - :daily (default) | :always | :never
  :checksum - :fail (default) | :ignore | :warn"
  [& {:keys [repositories coordinates retrieve] :as options}]
  (letfn [(translate-result [[k v]]
            [(lein-spec k) (when (seq v)
                             (set (map lein-spec v)))])]
    (into {}
          (map translate-result
               (apply
                aether/resolve-dependencies
                :coordinates (map lein-dependency coordinates)
                (apply concat (dissoc options :coordinates)))))))

(defn dependency-files
  "Given a dependency graph obtained from `resolve-dependencies`, returns a seq
   of files from the dependencies' metadata."
  [graph]
  (aether/dependency-files graph))

(defn dependency-hierarchy
  "Returns a dependency hierarchy based on the provided dependency graph
   (as returned by `resolve-dependencies`) and the coordinates that should
   be the root(s) of the hierarchy.  Siblings are sorted alphabetically."
  [root-coordinates dep-graph]
  (let [hierarchy (for [[root children] (select-keys
                                         dep-graph root-coordinates)]
                    [root (dependency-hierarchy children dep-graph)])]
    (when (seq hierarchy)
      (into
       (sorted-map-by
        #(apply compare
                (map (comp #'aether/coordinate-string lein-dependency) %&)))
       hierarchy))))
