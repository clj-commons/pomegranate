(ns cemerick.pomegranate.aether
  (:require [clojure.java.io :as io]
            clojure.set)
  (:import (org.apache.maven.repository.internal DefaultServiceLocator MavenRepositorySystemSession)
           (org.apache.maven.wagon.providers.http LightweightHttpWagon)
           (org.sonatype.aether RepositorySystem)
           (org.sonatype.aether.artifact Artifact)
           (org.sonatype.aether.connector.file FileRepositoryConnectorFactory)
           (org.sonatype.aether.connector.wagon WagonProvider WagonRepositoryConnectorFactory)
           (org.sonatype.aether.spi.connector RepositoryConnectorFactory)
           (org.sonatype.aether.repository ArtifactRepository Authentication RepositoryPolicy LocalRepository RemoteRepository)
           (org.sonatype.aether.graph Dependency Exclusion DependencyNode)
           (org.sonatype.aether.collection CollectRequest)
           (org.sonatype.aether.resolution DependencyRequest ArtifactRequest)
           (org.sonatype.aether.util.graph PreorderNodeListGenerator)
           (org.sonatype.aether.util.artifact DefaultArtifact SubArtifact)
           (org.sonatype.aether.deployment DeployRequest)
           (org.sonatype.aether.installation InstallRequest)))

;Dynamic so that tests can use a different place
(def ^{:dynamic true} *local-repo*
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(def maven-central {"central" "http://repo1.maven.org/maven2/"})

(deftype HttpProvider []
  WagonProvider
  (release [_ wagon])
  (lookup [_ role-hint]
          (and (= "http" role-hint) (LightweightHttpWagon.))))

(defn- repository-system
  []
  (.getService (doto (DefaultServiceLocator.)
                 (.addService RepositoryConnectorFactory FileRepositoryConnectorFactory)
                 (.addService RepositoryConnectorFactory WagonRepositoryConnectorFactory)
                 (.setServices WagonProvider (into-array WagonProvider [(HttpProvider.)])))
               RepositorySystem))

(defn- repository-session
  [repository-system]
  (doto (MavenRepositorySystemSession.)
    (.setLocalRepositoryManager (.newLocalRepositoryManager repository-system
                                                            (-> *local-repo*
                                                              .getAbsolutePath
                                                              LocalRepository.)))))

(def update-policies {:daily RepositoryPolicy/UPDATE_POLICY_DAILY
                      :always RepositoryPolicy/UPDATE_POLICY_ALWAYS
                      :never RepositoryPolicy/UPDATE_POLICY_NEVER})

(def checksum-policies {:fail RepositoryPolicy/CHECKSUM_POLICY_FAIL
                        :ignore RepositoryPolicy/CHECKSUM_POLICY_IGNORE
                        :warn RepositoryPolicy/CHECKSUM_POLICY_WARN})

(defn- policy
  [policy-settings enabled?]
  (doto (RepositoryPolicy.)
    (.setUpdatePolicy (update-policies (:update policy-settings :daily)))
    (.setChecksumPolicy (checksum-policies (:checksum policy-settings :fail)))
    (.setEnabled (boolean enabled?))))

(defn- set-policies
  [repo {:keys [snapshots releases] :as settings}]
  (doto repo
      (.setPolicy true (policy snapshots (:snapshots settings true)))
      (.setPolicy false (policy releases (:releases settings true)))))

(defn- authentication
  [{:keys [username password passphrase private-key-file] :as settings}]
  (let [auth (Authentication. username password)]
    (when (seq settings)
      (when passphrase (.setPassphrase auth passphrase))
      (when private-key-file (.setPrivateKeyFile auth private-key-file))
      auth)))

(defn- make-repository
  [[id settings]]
  (let [settings-map (if (string? settings)
                       {:url settings}
                       settings)
        repo (RemoteRepository. id
                                (:type settings-map "default")
                                (str (:url settings-map)))]
    (set-policies repo settings-map)
    (when-let [auth (authentication settings-map)]
      (.setAuthentication repo auth))
    repo))

(defn- group
  [group-artifact]
  (or (namespace group-artifact) (name group-artifact)))


(defn- coordinate-string
  "Produces a coordinate string with a format of
   <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>>
   given a lein-style dependency spec.  :extension defaults to jar."
  [[group-artifact version & {:keys [classifier extension] :or {extension "jar"}}]]
  (->> [(group group-artifact) (name group-artifact) extension classifier version]
    (remove nil?)
    (interpose \:)
    (apply str)))

(defn- exclusion
  [[group-artifact & {:as opts}]]
  (Exclusion.
    (group group-artifact)
    (name group-artifact)
    (:classifier opts "*")
    (:extension opts "*")))

(defn- dependency
  [[group-artifact version & {:keys [scope optional exclusions]
                              :as opts
                              :or {scope "compile"
                                   optional false}}
    :as dep-spec]]
  (Dependency. (DefaultArtifact. (coordinate-string dep-spec))
               scope
               optional
               (map (comp exclusion #(if (symbol? %) [%] %)) exclusions)))

(declare dep-spec*)

(defn- exclusion-spec
  "Given an Aether Exclusion, returns a lein-style exclusion vector with the
   :exclusion in its metadata."
  [^Exclusion ex]
  (with-meta (-> ex bean dep-spec*) {:exclusion ex}))

(defn- dep-spec
  "Given an Aether Dependency, returns a lein-style dependency vector with the
   :dependency and its corresponding artifact's :file in its metadata."
  [^Dependency dep]
  (let [artifact (.getArtifact dep)]
    (-> (merge (bean dep) (bean artifact))
      dep-spec*
      (with-meta {:dependency dep :file (.getFile artifact)}))))

(defn- dep-spec*
  "Base function for producing lein-style dependency spec vectors for dependencies
   and exclusions."
  [{:keys [groupId artifactId version classifier extension scope optional exclusions]
    :or {version nil
         scope "compile"
         optional false
         exclusions nil}}]
  (let [group-artifact (apply symbol (if (= groupId artifactId)
                                       [artifactId]
                                       [groupId artifactId]))]
    (vec (concat [group-artifact]
                 (when version [version])
                 (when (and (seq classifier)
                            (not= "*" classifier))
                   [:classifier classifier])
                 (when (and (seq extension)
                            (not (#{"*" "jar"} extension)))
                   [:extension extension])
                 (when optional [:optional true])
                 (when (not= scope "compile")
                   [:scope scope])
                 (when (seq exclusions)
                   [:exclusions (vec (map exclusion-spec exclusions))])))))
(defn deploy
    "Deploy the jar-file kwarg using the pom-file kwarg and coordinates kwarg to the repository kwarg.

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
  [& {:keys [coordinates jar-file pom-file repository]}]
  (let [system (repository-system)
        session (repository-session system)
        jar-artifact (-> (DefaultArtifact. (coordinate-string coordinates))
                         (.setFile jar-file))
        pom-artifact (-> (SubArtifact. jar-artifact "" "pom")
                         (.setFile pom-file))]
    (.deploy system session (doto (DeployRequest.)
                      (.addArtifact jar-artifact)
                      (.addArtifact pom-artifact)
                      (.setRepository (first (map make-repository repository)))))))

(defn install
  "Install the jar-file kwarg using the pom-file kwarg and coordinates kwarg.

coordinates - [group/name \"version\"]

jar-file - a file pointing to the jar

pom-file - a file pointing to the pom"
  [& {:keys [coordinates jar-file pom-file]}]
  (let [system (repository-system)
        session (repository-session system)
        jar-artifact (-> (DefaultArtifact. (coordinate-string coordinates))
                         (.setFile jar-file))
        pom-artifact (-> (SubArtifact. jar-artifact "" "pom")
                         (.setFile pom-file))]
      (.install system session (doto (InstallRequest.)
                      (.addArtifact jar-artifact)
                      (.addArtifact pom-artifact)))))

(defn- dependency-graph
  ([node]
    (reduce (fn [g ^DependencyNode n]
              (if-let [dep (.getDependency n)]
                (update-in g [(dep-spec dep)]
                           clojure.set/union
                           (->> (.getChildren n) 
                             (map #(.getDependency %))
                             (map dep-spec)
                             set))
                g)) 
            {}
            (tree-seq (constantly true)
                      #(seq (.getChildren %))
                      node))))

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
  [& {:keys [repositories coordinates retrieve] :or {retrieve true}}]
  (let [repositories (or repositories maven-central)
        system (repository-system)
        session (repository-session system)
        deps (map dependency coordinates)
        collect-request (CollectRequest. deps
                                         nil
                                         (map make-repository repositories))
        _ (.setRequestContext collect-request "runtime")
        result (if retrieve
                 (.resolveDependencies system session (DependencyRequest. collect-request nil))
                 (.collectDependencies system session collect-request))]
    (-> result .getRoot dependency-graph)))

(defn dependency-files
  "Given a dependency graph obtained from `resolve-dependencies`, returns a seq of
   files from the dependencies' metadata."
  [graph]
  (->> graph keys (map (comp :file meta)) (remove nil?)))

(defn dependency-hierarchy
  "Returns a dependency hierarchy based on the provided dependency graph
   (as returned by `resolve-dependencies`) and the coordinates that should
   be the root(s) of the hierarchy.  Siblings are sorted alphabetically."
  [root-coordinates dep-graph]
  (let [hierarchy (for [[root children] (select-keys dep-graph (map (comp dep-spec dependency) root-coordinates))]
                    [root (dependency-hierarchy children dep-graph)])]
    (when (seq hierarchy)
      (into (sorted-map-by #(apply compare (map coordinate-string %&))) hierarchy))))

