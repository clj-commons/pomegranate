(ns cemerick.pomegranate.aether
  (:require [clojure.java.io :as io])
  (:import (org.apache.maven.repository.internal DefaultServiceLocator MavenRepositorySystemSession)
           (org.apache.maven.wagon.providers.http LightweightHttpWagon)
           (org.sonatype.aether RepositorySystem)
           (org.sonatype.aether.connector.file FileRepositoryConnectorFactory)
           (org.sonatype.aether.connector.wagon WagonProvider WagonRepositoryConnectorFactory)
           (org.sonatype.aether.spi.connector RepositoryConnectorFactory)
           (org.sonatype.aether.repository ArtifactRepository Authentication RepositoryPolicy LocalRepository RemoteRepository)
           (org.sonatype.aether.graph Dependency Exclusion)
           (org.sonatype.aether.collection CollectRequest)
           (org.sonatype.aether.resolution DependencyRequest)
           (org.sonatype.aether.util.graph PreorderNodeListGenerator)
           (org.sonatype.aether.util.artifact DefaultArtifact SubArtifact)
           (org.sonatype.aether.deployment DeployRequest)
           (org.sonatype.aether.installation InstallRequest)))

;Dynamic so that tests can use a different place
(def ^{:dynamic true} *local-repo*
  (io/file (System/getProperty "user.home") ".m2" "repository"))

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
  ([group-artifact version]
    (let [group (group group-artifact)
          artifact (name group-artifact)]
      (str group \: artifact \: version)))
  ([[group-artifact version]]
    (coordinate-string group-artifact version)))

(defn- exclusion
  [group-artifact & opts]
  (let [group (group group-artifact)
        artifact (name group-artifact)
        opts-map (apply hash-map opts)]
    (Exclusion.
     group
     artifact
     (:classifier opts-map "*")
     (:extension opts-map "*"))))

(defn- dependency
  [[group-artifact version & opts]]
  (let [opts-map (apply hash-map opts)]
    (Dependency. (DefaultArtifact. (coordinate-string [group-artifact version]))
                 (:scope opts-map "compile")
                 (boolean (:optional? opts-map false))
                 (map exclusion (:exclusions opts-map)))))

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

(defn resolve-dependencies
  "Resolve dependencies for the coordinates kwarg, using repositories from the repositories kwarg.

coordinates - [[group/name \"version\" & settings] ..]
settings:
  :scope - the maven scope for the dependency (default \"compile\")
  :optional? - is the dependency optional? (default \"false\")
  :exclusions - which sub-dependencies to skip : [group/name & settings]
    settings:
      :classifier (default \"*\")
      :extension  (default \"*\")

repositories - {name url ..} | {name settings ..}
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
  [& {:keys [repositories coordinates]}]
  (let [system (repository-system)
        session (repository-session system)
        collect-request (CollectRequest. (map dependency coordinates)
                                         nil
                                         (map make-repository repositories))
        dep-node (.getRoot (.collectDependencies system session collect-request))
        dep-req (DependencyRequest. dep-node nil)
        nodelist-gen (PreorderNodeListGenerator.)]
    (.resolveDependencies system session dep-req)
    (.accept dep-node nodelist-gen)
    (set (.getFiles nodelist-gen))))
