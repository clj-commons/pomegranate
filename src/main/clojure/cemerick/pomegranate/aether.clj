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

(defn- repository
  "Create an Aether ArtifactRepository object.
Settings:
:url URL of the repository
:snapshots contains snapshots versions?
:releases contains release versions?
:username username to log in with
:password password to log in with
:passphrase
:private-key-file"
  ([[id settings]]
     (let [settings-map (if (string? settings)
                          {:url settings}
                          settings)
           repo (RemoteRepository. (:id settings-map)
                                   (:type settings-map "default")
                                   (str (:url settings-map)))]
       (set-policies repo settings-map)
       (when-let [auth (authentication settings-map)]
         (.setAuthentication repo auth))
       repo)))

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

(defn exclusion
  [group-artifact & opts]
  (let [group (group group-artifact)
        artifact (name group-artifact)
        opts-map (apply hash-map opts)]
    (Exclusion.
     group
     artifact
     (:classifier opts-map "*")
     (:extension opts-map "*"))))

(defn dependency
  [[group-artifact version & opts]]
  (let [opts-map (apply hash-map opts)]
    (Dependency. (DefaultArtifact. (coordinate-string [group-artifact version]))
                 (:scope opts-map "compile")
                 (boolean (:optional? opts-map false))
                 (map exclusion (:exclusions opts-map)))))

(defn deploy
  [coordinates jar-file pom-file repo]
  (let [system (repository-system)
        session (repository-session system)
        jar-artifact (-> (DefaultArtifact. (coordinate-string coordinates))
                         (.setFile jar-file))
        pom-artifact (-> (SubArtifact. jar-artifact "" "pom")
                         (.setFile pom-file))]
    (.deploy system session (doto (DeployRequest.)
                      (.addArtifact jar-artifact)
                      (.addArtifact pom-artifact)
                      (.setRepository (repository repo))))))

(defn install
  [coordinates jar-file pom-file]
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
  [& {:keys [repositories coordinates]}]
  (let [system (repository-system)
        session (repository-session system)
        collect-request (CollectRequest. (map dependency coordinates)
                                         nil
                                         (map repository repositories))
        dep-node (.getRoot (.collectDependencies system session collect-request))
        dep-req (DependencyRequest. dep-node nil)
        nodelist-gen (PreorderNodeListGenerator.)]
    (.resolveDependencies system session dep-req)
    (.accept dep-node nodelist-gen)
    (set (.getFiles nodelist-gen))))
