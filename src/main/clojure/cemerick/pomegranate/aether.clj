(ns cemerick.pomegranate.aether
  (:require [clojure.java.io :as io])
  (:import org.apache.maven.repository.internal.DefaultServiceLocator
           org.sonatype.aether.RepositorySystem
           org.sonatype.aether.spi.connector.RepositoryConnectorFactory
           org.apache.maven.wagon.providers.http.LightweightHttpWagon
           (org.sonatype.aether.connector.wagon WagonProvider WagonRepositoryConnectorFactory)
           (org.sonatype.aether.connector.file FileRepositoryConnectorFactory)
           
           org.apache.maven.repository.internal.MavenRepositorySystemSession
           (org.sonatype.aether.repository LocalRepository RemoteRepository)
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

(defn repository-system
  []
  (.getService (doto (DefaultServiceLocator.)
                 (.addService RepositoryConnectorFactory FileRepositoryConnectorFactory)
                 (.addService RepositoryConnectorFactory WagonRepositoryConnectorFactory)
                 (.setServices WagonProvider (into-array WagonProvider [(HttpProvider.)])))
               RepositorySystem))

(defn repository-session
  [repository-system]
  (doto (MavenRepositorySystemSession.)
    (.setLocalRepositoryManager (.newLocalRepositoryManager repository-system
                                                            (-> *local-repo*
                                                              .getAbsolutePath
                                                              LocalRepository.)))))

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

(defn repository
  ([[id config]] (repository id config))
  ([id config]
     (RemoteRepository. id "default" config)))

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
