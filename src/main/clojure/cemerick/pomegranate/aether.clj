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
           (org.sonatype.aether.graph Dependency)
           (org.sonatype.aether.collection CollectRequest)
           (org.sonatype.aether.resolution DependencyRequest)
           (org.sonatype.aether.util.graph PreorderNodeListGenerator)
           (org.sonatype.aether.util.artifact DefaultArtifact)))

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
                                                            (-> (io/file (System/getProperty "user.home") ".m2" "repository")
                                                              .getAbsolutePath
                                                              LocalRepository.)))))

(defn- coordinate-string
  ([group-artifact version]
    (let [group (or (namespace group-artifact) (name group-artifact))
          artifact (name group-artifact)]
      (str group \: artifact \: version)))
  ([[group-artifact version]]
    (coordinate-string group-artifact version)))

(defn repository
  ([[id config]] (repository id config))
  ([id config]
    (RemoteRepository. id "default" config)))

(defn resolve-dependencies
  [& {:keys [repositories coordinates]}]
  (let [system (repository-system)
        session (repository-session system)
        collect-request (CollectRequest. (map #(Dependency. (DefaultArtifact. (coordinate-string %)) "compile") coordinates)
                                         nil
                                         (map repository (merge {"central" "http://repo1.maven.org/maven2/"}
                                                                repositories)))
        dep-node (.getRoot (.collectDependencies system session collect-request))
        dep-req (DependencyRequest. dep-node nil)
        nodelist-gen (PreorderNodeListGenerator.)]
    (.resolveDependencies system session dep-req)
    (.accept dep-node nodelist-gen)
    (set (.getFiles nodelist-gen))))
