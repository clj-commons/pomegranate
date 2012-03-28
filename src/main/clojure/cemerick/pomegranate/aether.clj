(ns cemerick.pomegranate.aether
  (:require [clojure.java.io :as io]
            clojure.set
            [clojure.string :as str])
  (:import (org.apache.maven.repository.internal DefaultServiceLocator MavenRepositorySystemSession)
           (org.sonatype.aether RepositorySystem)
           (org.sonatype.aether.transfer TransferListener)
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

(def ^{:private true} default-local-repo
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(def maven-central {"central" "http://repo1.maven.org/maven2/"})

; Using HttpWagon (which uses apache httpclient) because the "LightweightHttpWagon"
; (which just uses JDK HTTP) reliably flakes if you attempt to resolve SNAPSHOT
; artifacts from an HTTPS password-protected repository (like a nexus instance)
; when other un-authenticated repositories are included in the resolution.
; My theory is that the JDK HTTP impl is screwing up connection pooling or something,
; and reusing the same connection handle for the HTTPS repo as it used for e.g.
; central, without updating the authentication info.
; In any case, HttpWagon is what Maven 3 uses, and it works.
(def ^{:private true} wagon-factories (atom {"http" #(org.apache.maven.wagon.providers.http.HttpWagon.)
                                             "https" #(org.apache.maven.wagon.providers.http.HttpWagon.)}))

(defn register-wagon-factory!
  "Registers a new no-arg factory function for the given scheme.  The function must return
   an implementation of org.apache.maven.wagon.Wagon."
  [scheme factory-fn]
  (swap! wagon-factories (fn [m]
                           (when-let [fn (m scheme)]
                             (println (format "Warning: replacing existing support for %s repositories (%s) with %s" scheme fn factory-fn)))
                           (assoc m scheme factory-fn))))

(deftype PomegranateWagonProvider []
  WagonProvider
  (release [_ wagon])
  (lookup [_ role-hint]
    (when-let [f (get @wagon-factories role-hint)]
      (f))))

(deftype TransferListenerProxy [listener-fn]
  TransferListener
  (transferCorrupted [_ e] (listener-fn e))
  (transferFailed [_ e] (listener-fn e))
  (transferInitiated [_ e] (listener-fn e))
  (transferProgressed [_ e] (listener-fn e))
  (transferStarted [_ e] (listener-fn e))
  (transferSucceeded [_ e] (listener-fn e)))

(defn- transfer-event
  [^org.sonatype.aether.transfer.TransferEvent e]
  ; INITIATED, STARTED, PROGRESSED, CORRUPTED, SUCCEEDED, FAILED
  {:type (-> e .getType .name str/lower-case keyword)
   ; :get :put
   :method (-> e .getRequestType str/lower-case keyword)
   :transferred (.getTransferredBytes e)
   :error (.getException e)
   :data-buffer (.getDataBuffer e)
   :data-length (.getDataLength e)
   :resource (let [r (.getResource e)]
               {:repository (.getRepositoryUrl r)
                :name (.getResourceName r)
                :file (.getFile r)
                :size (.getContentLength r)
                :transfer-start-time (.getTransferStartTime r)
                :trace (.getTrace r)})})

(defn- default-listener-fn
  [{:keys [type method transferred resource error] :as evt}]
  (let [{:keys [name size repository transfer-start-time]} resource]
    (case type
      :started (do
                 (print (case method :get "Retrieving" :put "Sending")
                        name
                        (if (neg? size)
                          ""
                          (format "(%sk)" (Math/round (double (max 1 (/ size 1024)))))))
                 (when (< 70 (+ 10 (count name) (count repository)))
                   (println) (print "    "))
                 (println (case method :get "from" :put "to") repository))
      (:corrupted :failed) (when error (println (.getMessage error)))
      nil)))

(defn- repository-system
  []
  (.getService (doto (DefaultServiceLocator.)
                 (.addService RepositoryConnectorFactory FileRepositoryConnectorFactory)
                 (.addService RepositoryConnectorFactory WagonRepositoryConnectorFactory)
                 (.addService WagonProvider PomegranateWagonProvider))
               org.sonatype.aether.RepositorySystem))

(defn- construct-transfer-listener
  [transfer-listener]
  (cond
    (instance? TransferListener transfer-listener) transfer-listener

    (= transfer-listener :stdout)
    (TransferListenerProxy. (comp default-listener-fn transfer-event))

    (fn? transfer-listener)
    (TransferListenerProxy. (comp transfer-listener transfer-event))

    :else (TransferListenerProxy. (fn [_]))))

(defn- repository-session
  [repository-system local-repo offline? transfer-listener]
  (-> (MavenRepositorySystemSession.)
    (.setLocalRepositoryManager (.newLocalRepositoryManager repository-system
                                  (-> (io/file (or local-repo default-local-repo))
                                    .getAbsolutePath
                                    LocalRepository.)))
    (.setOffline (boolean offline?))
    (.setTransferListener (construct-transfer-listener transfer-listener))))

(def update-policies {:daily RepositoryPolicy/UPDATE_POLICY_DAILY
                      :always RepositoryPolicy/UPDATE_POLICY_ALWAYS
                      :never RepositoryPolicy/UPDATE_POLICY_NEVER})

(def checksum-policies {:fail RepositoryPolicy/CHECKSUM_POLICY_FAIL
                        :ignore RepositoryPolicy/CHECKSUM_POLICY_IGNORE
                        :warn RepositoryPolicy/CHECKSUM_POLICY_WARN})

(defn- policy
  [policy-settings enabled?]
  (RepositoryPolicy.
    (boolean enabled?)
    (update-policies (:update policy-settings :daily))
    (checksum-policies (:checksum policy-settings :warn))))

(defn- set-policies
  [repo settings]
  (doto repo
    (.setPolicy true (policy settings (:snapshots settings true)))
    (.setPolicy false (policy settings (:releases settings true)))))

(defn- set-authentication
  [repo {:keys [username password passphrase private-key-file] :as settings}]
  (if (or username password private-key-file passphrase)
    (.setAuthentication repo (Authentication. username password private-key-file passphrase))
    repo))

(defn- make-repository
  [[id settings]]
  (let [settings-map (if (string? settings)
                       {:url settings}
                       settings)]
    (doto (RemoteRepository. id
                             (:type settings-map "default")
                             (str (:url settings-map)))
      (set-policies settings-map)
      (set-authentication settings-map))))

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
    "Deploy the jar-file kwarg using the pom-file kwarg and coordinates
kwarg to the repository kwarg.

  :coordinates - [group/name \"version\"]
  :jar-file - a file pointing to the jar
  :pom-file - a file pointing to the pom
  :repository - {name url} | {name settings}
    settings:
      :url - URL of the repository
      :snapshots - use snapshots versions? (default true)
      :releases - use release versions? (default true)
      :username - username to log in with
      :password - password to log in with
      :passphrase - passphrase to log in wth
      :private-key-file - private key file to log in with
      :update - :daily (default) | :always | :never
      :checksum - :fail (default) | :ignore | :warn

  :local-repo - path to the local repository (defaults to ~/.m2/repository)
  :transfer-listener - same as provided to resolve-dependencies"
  [& {:keys [coordinates jar-file pom-file repository local-repo transfer-listener]}]
  (let [system (repository-system)
        session (repository-session system local-repo false transfer-listener)
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

  :coordinates - [group/name \"version\"]
  :jar-file - a file pointing to the jar
  :pom-file - a file pointing to the pom
  :local-repo - path to the local repository (defaults to ~/.m2/repository)
  :transfer-listener - same as provided to resolve-dependencies"
  [& {:keys [coordinates jar-file pom-file local-repo transfer-listener]}]
  (let [system (repository-system)
        session (repository-session system local-repo false transfer-listener)
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

    :coordinates - [[group/name \"version\" & settings] ..]
      settings:
      :scope - the maven scope for the dependency (default \"compile\")
      :optional? - is the dependency optional? (default \"false\")
      :exclusions - which sub-dependencies to skip : [group/name & settings]
        settings:
        :classifier (default \"*\")
        :extension  (default \"*\")

    :repositories - {name url ..} | {name settings ..}
      (defaults to {\"central\" \"http://repo1.maven.org/maven2/\"}
      settings:
      :url - URL of the repository
      :snapshots - use snapshots versions? (default true)
      :releases - use release versions? (default true)
      :username - username to log in with
      :password - password to log in with
      :passphrase - passphrase to log in wth
      :private-key-file - private key file to log in with
      :update - :daily (default) | :always | :never
      :checksum - :fail (default) | :ignore | :warn

    :local-repo - path to the local repository (defaults to ~/.m2/repository)
    :offline? - if true, no remote repositories will be contacted
    :transfer-listener - the transfer listener that will be notifed of dependency
      resolution and deployment events.
      Can be:
        - nil (the default), i.e. no notification of events
        - :stdout, corresponding to a default listener implementation that writes
            notifications and progress indicators to stdout, suitable for an
            interactive console program
        - a function of one argument, which will be called with a map derived from
            each event.
        - an instance of org.sonatype.aether.transfer.TransferListener"
  [& {:keys [repositories coordinates retrieve local-repo transfer-listener offline?]
      :or {retrieve true}}]
  (let [repositories (or repositories maven-central)
        system (repository-system)
        session (repository-session system local-repo offline? transfer-listener)
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

