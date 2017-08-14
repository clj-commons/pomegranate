(ns cemerick.pomegranate.aether
  (:refer-clojure :exclude  [type proxy])
  (:require [clojure.java.io :as io]
            clojure.set
            [clojure.string :as str]
            clojure.stacktrace)
  (:import (org.eclipse.aether RepositorySystem)
           (org.eclipse.aether.transport.wagon WagonTransporterFactory
                                               WagonProvider)
           (org.eclipse.aether.transport.file FileTransporterFactory)
           (org.eclipse.aether.transfer TransferListener)
           (org.eclipse.aether.artifact Artifact)
           (org.eclipse.aether.spi.connector RepositoryConnectorFactory)
           (org.eclipse.aether.spi.connector.transport TransporterFactory)
           (org.eclipse.aether.repository Proxy  Authentication
                                          RepositoryPolicy LocalRepository RemoteRepository RemoteRepository$Builder
                                          MirrorSelector)
           (org.eclipse.aether.util.repository DefaultProxySelector AuthenticationBuilder)
           (org.eclipse.aether.graph Dependency Exclusion DependencyNode)
           (org.eclipse.aether.collection CollectRequest)
           (org.eclipse.aether.resolution DependencyRequest ArtifactRequest
                                          ArtifactResult VersionRequest)
           (org.eclipse.aether.artifact DefaultArtifact ArtifactProperties)
           (org.eclipse.aether.util.artifact SubArtifact)
           (org.eclipse.aether.deployment DeployRequest)
           (org.eclipse.aether.installation InstallRequest)
           (org.eclipse.aether.util.version GenericVersionScheme)
           (org.eclipse.aether.connector.basic BasicRepositoryConnectorFactory)
           (org.eclipse.aether.impl DefaultServiceLocator$ErrorHandler)
           (org.apache.maven.repository.internal MavenRepositorySystemUtils)))

(def ^{:private true} default-local-repo
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(def maven-central {"central" "https://repo1.maven.org/maven2/"})

;; Using HttpWagon (which uses apache httpclient) because the "LightweightHttpWagon"
;; (which just uses JDK HTTP) reliably flakes if you attempt to resolve SNAPSHOT
;; artifacts from an HTTPS password-protected repository (like a nexus instance)
;; when other un-authenticated repositories are included in the resolution.
;; My theory is that the JDK HTTP impl is screwing up connection pooling or something,
;; and reusing the same connection handle for the HTTPS repo as it used for e.g.
;; central, without updating the authentication info.
;; In any case, HttpWagon is what Maven 3 uses, and it works.
(def ^{:private true} wagon-factories
  (atom {"https" #(org.apache.maven.wagon.providers.http.HttpWagon.)
         "http" #(throw (Exception. "Tried to use insecure HTTP repository."))}))

(defn register-wagon-factory!
  "Registers a new no-arg factory function for the given scheme.  The function
   must return an implementation of org.apache.maven.wagon.Wagon."
  [scheme factory-fn]
  (swap! wagon-factories (fn [m]
                           (when-let [fn (and (not= scheme "http") (m scheme))]
                             (println (format "Warning: replacing existing support for %s repositories (%s) with %s" scheme fn factory-fn)))
                           (assoc m scheme factory-fn))))

(deftype PomegranateWagonProvider []
  WagonProvider
  (release [_ wagon])
  (lookup [_ role-hint]
          (when-let [f (get @wagon-factories role-hint)]
            (try
              (f)
              (catch Exception e
                (clojure.stacktrace/print-cause-trace e)
                (throw e))))))

(deftype TransferListenerProxy [listener-fn]
  TransferListener
  (transferCorrupted [_ e] (listener-fn e))
  (transferFailed [_ e] (listener-fn e))
  (transferInitiated [_ e] (listener-fn e))
  (transferProgressed [_ e] (listener-fn e))
  (transferStarted [_ e] (listener-fn e))
  (transferSucceeded [_ e] (listener-fn e)))

(defn- transfer-event
  [^org.eclipse.aether.transfer.TransferEvent e]
  ;; INITIATED, STARTED, PROGRESSED, CORRUPTED, SUCCEEDED, FAILED
  {:type (-> e .getType .name str/lower-case keyword)
   ;; :get :put
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
  (let [error-handler (clojure.core/proxy [DefaultServiceLocator$ErrorHandler] []
                        (serviceCreationFailed [type-clazz impl-clazz ^Throwable e]
                          (clojure.stacktrace/print-cause-trace e)))]
    (.getService
     (doto (MavenRepositorySystemUtils/newServiceLocator)
       (.setService TransporterFactory WagonTransporterFactory)
       (.setService WagonProvider PomegranateWagonProvider)
       (.addService RepositoryConnectorFactory BasicRepositoryConnectorFactory)
       (.addService TransporterFactory FileTransporterFactory)
       (.setErrorHandler error-handler))
     RepositorySystem)))

(defn- construct-transfer-listener
  [transfer-listener]
  (cond
    (instance? TransferListener transfer-listener) transfer-listener

    (= transfer-listener :stdout)
    (TransferListenerProxy. (comp default-listener-fn transfer-event))

    (fn? transfer-listener)
    (TransferListenerProxy. (comp transfer-listener transfer-event))

    :else (TransferListenerProxy. (fn [_]))))

(defn repository-session
  [{:keys [repository-system local-repo offline? transfer-listener mirror-selector]}]
  (let [session (org.apache.maven.repository.internal.MavenRepositorySystemUtils/newSession)]
    (doto session
      (.setLocalRepositoryManager (.newLocalRepositoryManager
                                   repository-system
                                   session
                                   (LocalRepository.
                                    (io/file (or local-repo default-local-repo)))))
      (.setMirrorSelector mirror-selector)
      (.setOffline (boolean offline?))
      (.setTransferListener (construct-transfer-listener transfer-listener)))))

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
   (checksum-policies (:checksum policy-settings :fail))))

(defn- set-policies
  [repo-builder settings]
  (doto repo-builder
    (.setSnapshotPolicy (policy settings (:snapshots settings true)))
    (.setReleasePolicy (policy settings (:releases settings true)))))

(defn- authentication
  [{:keys [username password passphrase private-key-file] :as settings}]
  (-> (AuthenticationBuilder.)
      (.addUsername username)
      (.addPassword password)
      (.addPrivateKey private-key-file passphrase)
      .build))

(defn- set-authentication
  [repo-builder {:keys [username password passphrase private-key-file] :as settings}]
  (if (or username password private-key-file passphrase)
    (.setAuthentication repo-builder (authentication settings))
    repo-builder))

(defn- set-proxy
  [repo-builder {:keys [type host port non-proxy-hosts]
                 :or {type "http"}
                 :as proxy}]
  (if (and host port)
    (let [prx-sel (doto (DefaultProxySelector.)
                    (.add (Proxy. type host port (authentication proxy))
                          non-proxy-hosts))
          prx (.getProxy prx-sel (.build repo-builder))] ; ugg.
      ;; Don't know how to get around "building" the repo for this
      (.setProxy repo-builder prx))
    repo-builder))

(defn make-repository
  "Produces an Aether RemoteRepository instance from Pomegranate-style repository information"
  [[id settings] proxy]
  (let [settings-map (if (string? settings)
                       {:url settings}
                       settings)]
    (.build
     (doto (RemoteRepository$Builder. (and id (name id))
                                      (:type settings-map "default")
                                      (str (:url settings-map)))
       (set-policies settings-map)
       (set-authentication settings-map)
       (set-proxy proxy)))))

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

(defn- normalize-exclusion-spec [spec]
  (if (symbol? spec)
    [spec]
    spec))

(defn- artifact
  [[group-artifact version & {:keys [scope optional exclusions]} :as dep-spec]]
  (DefaultArtifact. (coordinate-string dep-spec)))

(defn dependency
  "Produces an Aether Dependency instance from Pomegranate-style dependency information"
  [[group-artifact version & {:keys [scope optional exclusions]
                              :as opts
                              :or {scope "compile"
                                   optional false}}
    :as dep-spec]]
  (Dependency. (artifact dep-spec)
               scope
               optional
               (map (comp exclusion normalize-exclusion-spec) exclusions)))

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

(defn- create-artifact
  [files artifact]
  (if-let [file (get files artifact)]
    (-> (coordinate-string artifact)
      DefaultArtifact.
      (.setFile (io/file file)))
    (throw (IllegalArgumentException. (str "No file provided for artifact " artifact)))))

(defn deploy-artifacts
  "Deploy the artifacts kwarg to the repository kwarg.

  :files - map from artifact vectors to file paths or java.io.File objects
           where the file to be deployed for each artifact is to be found
           An artifact vector is e.g.
             '[group/artifact \"1.0.0\"] or
             '[group/artifact \"1.0.0\" :extension \"pom\"].
           All artifacts should have the same version and group and artifact IDs
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
  :transfer-listener - same as provided to resolve-dependencies

  :proxy - proxy configuration, can be nil, the host scheme and type must match
    :host - proxy hostname
    :type - http  (default) | http | https
    :port - proxy port
    :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
    :username - username to log in with, may be null
    :password - password to log in with, may be null
    :passphrase - passphrase to log in wth, may be null
    :private-key-file - private key file to log in with, may be null"

  [& {:keys [files repository local-repo transfer-listener proxy repository-session-fn]}]
  (when (empty? files)
    (throw (IllegalArgumentException. "Must provide valid :files to deploy-artifacts")))
  (when (->> (keys files)
             (map (fn [[ga v]] [(if (namespace ga) ga (symbol (str ga) (str ga))) v]))
             set
             count
             (< 1))
    (throw (IllegalArgumentException.
            (str "Provided artifacts have varying version, group, or artifact IDs: " (keys files)))))
  (let [system (repository-system)
        session ((or repository-session-fn
                     repository-session)
                 {:repository-system system
                  :local-repo local-repo
                  :offline? false
                  :transfer-listener transfer-listener})]
    (.deploy system session
             (doto (DeployRequest.)
               (.setArtifacts (vec (map (partial create-artifact files) (keys files))))
               (.setRepository (first (map #(make-repository % proxy) repository)))))))

(defn install-artifacts
  "Deploy the file kwarg using the coordinates kwarg to the repository kwarg.

  :files - same as with deploy-artifacts
  :local-repo - path to the local repository (defaults to ~/.m2/repository)
  :transfer-listener - same as provided to resolve-dependencies"
  [& {:keys [files local-repo transfer-listener repository-session-fn]}]
  (let [system (repository-system)
        session ((or repository-session-fn
                     repository-session)
                 {:repository-system system
                  :local-repo local-repo
                  :offline? false
                  :transfer-listener transfer-listener})]
    (.install system session
              (doto (InstallRequest.)
                (.setArtifacts (vec (map (partial create-artifact files) (keys files))))))))

(defn- artifacts-for
  "Takes a coordinates map, an a map from partial coordinates to "
  [coordinates file-map]
  (zipmap (map (partial into coordinates) (keys file-map)) (vals file-map)))

(defn- optional-artifact
  "Takes a coordinates map, an a map from partial coordinates to "
  [artifact-coords path]
  (when path {artifact-coords path}))

(defn deploy
  "Deploy the jar-file kwarg using the pom-file kwarg and coordinates
kwarg to the repository kwarg.

  :coordinates - [group/name \"version\"]
  :artifact-map - a map from partial coordinates to file path or File
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
  :transfer-listener - same as provided to resolve-dependencies

  :proxy - proxy configuration, can be nil, the host scheme and type must match
    :host - proxy hostname
    :type - http  (default) | http | https
    :port - proxy port
    :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
    :username - username to log in with, may be null
    :password - password to log in with, may be null
    :passphrase - passphrase to log in wth, may be null
    :private-key-file - private key file to log in with, may be null"
  [& {:keys [coordinates artifact-map jar-file pom-file] :as opts}]
  (when (empty? coordinates)
    (throw
     (IllegalArgumentException. "Must provide valid :coordinates to deploy")))
  (apply deploy-artifacts
    (apply concat (assoc opts
                    :files (artifacts-for
                            coordinates
                            (merge
                             artifact-map
                             (optional-artifact [:extension "pom"] pom-file)
                             (optional-artifact [] jar-file)))))))

(defn install
  "Install the artifacts specified by the jar-file or file-map and pom-file
   kwargs using the coordinates kwarg.

  :coordinates - [group/name \"version\"]
  :artifact-map - a map from partial coordinates to file path or File
  :jar-file - a file pointing to the jar
  :pom-file - a file pointing to the pom
  :local-repo - path to the local repository (defaults to ~/.m2/repository)
  :transfer-listener - same as provided to resolve-dependencies"
  [& {:keys [coordinates artifact-map jar-file pom-file] :as opts}]
  (when (empty? coordinates)
    (throw
     (IllegalArgumentException. "Must provide valid :coordinates to install")))
  (apply install-artifacts
    (apply concat (assoc opts
                    :files (artifacts-for
                            coordinates
                            (merge
                             artifact-map
                             (optional-artifact [:extension "pom"] pom-file)
                             (optional-artifact [] jar-file)))))))

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

(defn- mirror-selector-fn
  "Default mirror selection function.  The first argument should be a map
   like that described as the :mirrors argument in resolve-dependencies.
   The second argument should be a repository spec, also as described in
   resolve-dependencies.  Will return the mirror spec that matches the
   provided repository spec."
  [mirrors {:keys [name url snapshots releases]}]
  (let [mirrors (filter (fn [[matcher mirror-spec]]
                          (or
                            (and (string? matcher) (or (= matcher name) (= matcher url)))
                            (and (instance? java.util.regex.Pattern matcher)
                                 (or (re-matches matcher name) (re-matches matcher url)))))
                        mirrors)]
    (case (count mirrors)
      0 nil
      1 (-> mirrors first second)
      (if (some nil? (map second mirrors))
        ;; wildcard override
        nil
        (throw (IllegalArgumentException.
               (str "Multiple mirrors configured to match repository " {name url} ": "
                 (into {} (map #(update-in % [1] select-keys [:name :url]) mirrors)))))))))

(defn- mirror-selector
  "Returns a MirrorSelector that delegates matching of mirrors to given remote repositories
   to the provided function.  Any returned repository specifications are turned into
   RemoteRepository instances, and configured to use the provided proxy."
  [mirror-selector-fn proxy]
  (reify MirrorSelector
    (getMirror [_ repo]
      (let [repo-spec {:name (.getId repo)
                       :url (.getUrl repo)
                       :snapshots (-> repo (.getPolicy true) .isEnabled)
                       :releases (-> repo (.getPolicy false) .isEnabled)}

            {:keys [name repo-manager content-type] :as mirror-spec}
            (mirror-selector-fn repo-spec)]
        (when-let [mirror (and mirror-spec (make-repository [name mirror-spec] proxy))]
          (-> (RemoteRepository$Builder. mirror)
              (.setMirroredRepositories [repo])
              (.setRepositoryManager (boolean repo-manager))
              (.setContentType (or content-type "default"))
              (.build)))))))


(defn resolve-artifacts*
  "Resolves artifacts for the coordinates kwarg, using repositories from the
   `:repositories` kwarg.

   Retrieval of dependencies can be disabled by providing `:retrieve false` as a
   kwarg.

   Returns an sequence of either `org.eclipse.aether.VersionResult`
   if `:retrieve false`, or `org.eclipse.aether.ArtifactResult` if
   `:retrieve true` (the default).

   If you don't want to mess with the Aether implementation classes, then use
   `resolve-artifacts` instead.

    :coordinates - [[group/name \"version\" & settings] ..]
      settings:
      :extension  - the maven extension (type) to require
      :classifier - the maven classifier to require
      :scope      - the maven scope for the dependency (default \"compile\")
      :optional   - is the dependency optional? (default \"false\")
      :exclusions - which sub-dependencies to skip : [group/name & settings]
        settings:
        :classifier (default \"*\")
        :extension  (default \"*\")

    :repositories - {name url ..} | {name settings ..}
      (defaults to {\"central\" \"https://repo1.maven.org/maven2/\"}
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
        - an instance of org.eclipse.aether.transfer.TransferListener

    :proxy - proxy configuration, can be nil, the host scheme and type must match
      :host - proxy hostname
      :type - http  (default) | http | https
      :port - proxy port
      :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
      :username - username to log in with, may be null
      :password - password to log in with, may be null
      :passphrase - passphrase to log in wth, may be null
      :private-key-file - private key file to log in with, may be null

    :mirrors - {matches settings ..}
      matches - a string or regex that will be used to match the mirror to
                candidate repositories. Attempts will be made to match the
                string/regex to repository names and URLs, with exact string
                matches preferred. Wildcard mirrors can be specified with
                a match-all regex such as #\".+\".  Excluding a repository
                from mirroring can be done by mapping a string or regex matching
                the repository in question to nil.
      settings include these keys, and all those supported by :repositories:
      :name         - name/id of the mirror
      :repo-manager - whether the mirror is a repository manager"

  [& {:keys [repositories coordinates files retrieve local-repo
             transfer-listener offline? proxy mirrors repository-session-fn]
      :or {retrieve true}}]
  (let [repositories (or repositories maven-central)
        system (repository-system)
        mirror-selector-fn (memoize (partial mirror-selector-fn mirrors))
        mirror-selector (mirror-selector mirror-selector-fn proxy)
        session ((or repository-session-fn
                     repository-session)
                 {:repository-system system
                  :local-repo local-repo
                  :offline? offline?
                  :transfer-listener transfer-listener
                  :mirror-selector mirror-selector})
        deps (->> coordinates
                  (map #(if-let [local-file (get files %)]
                          (-> (artifact %)
                              (.setProperties
                               {ArtifactProperties/LOCAL_PATH
                                (.getPath (io/file local-file))}))
                          (artifact %)))
                  vec)
        repositories (vec (map #(let [repo (make-repository % proxy)]
                                  (-> session
                                      (.getMirrorSelector)
                                      (.getMirror repo)
                                      (or repo)))
                               repositories))]
    (if retrieve
      (.resolveArtifacts
       system session (map #(ArtifactRequest. % repositories nil) deps))
      (doall
       (for [dep deps]
         (.resolveVersion
          system session (VersionRequest. dep repositories nil)))))))

(defn resolve-artifacts
  "Same as `resolve-artifacts*`, but returns a sequence of dependencies; each
   artifact's metadata contains the source Aether result object, and the
   artifact's :file on disk."
  [& args]
  (let [{:keys [coordinates]} (apply hash-map args)]
    (->> (apply resolve-artifacts* args)
         (map
          (fn [coord result]
            {:pre [coord result]}
            (let [m (when (instance? ArtifactResult result)
                      {:file (.. ^ArtifactResult result getArtifact getFile)})]
              (with-meta coord
                (merge {:result result} m))))
          coordinates))))


(defn- add-version-from-managed-coord
  "Given an entry from a coordinates vector, and the corresponding entry from the
  managed coordinates vector, update the version number in the coordinate with the
  value from the managed coordinate."
  [coord managed-coord]
  (if-let [managed-version (second managed-coord)]
    (vec (concat [(first coord) managed-version]
                 (nthrest coord 2)))
    (throw (IllegalArgumentException. (str "Provided artifact is missing a version: " coord)))))

(defn- coordinates-match?
  [[dep version & opts] [sdep sversion & sopts]]
  (let [om (apply hash-map opts)
        som (apply hash-map sopts)]
    (and
     (= (group dep)
        (group sdep))
     (= (name dep)
        (name sdep))
     (= (:extension om "jar")
        (:extension som "jar"))
     (= (:classifier om)
        (:classifier som)))))

(defn- find-managed-coord
  "Given an entry from a coordinates vector, and a managed coordinates vector, find
  the entry in the managed coordinates vector (if any) that matches the coordinate."
  [coord managed-coords]
  (first (filter #(coordinates-match? coord %) managed-coords)))

(defn- add-version-from-managed-coords-if-missing
  "Given a managed coordinates vector and an entry from a coordinates vector, check
  to see if the coordinate specifies a version string, and if not, update it with
  the version string from the managed coordinates (if it is defined)."
  [managed-coords coord]
  (if (nil? (second coord))
    (add-version-from-managed-coord coord (find-managed-coord coord managed-coords))
    coord))

(defn merge-versions-from-managed-coords
  "Given a vector of coordinates (e.g. [[group/name <\"version\"> & settings] ..])
  where the version field is optional or can be nil, and a vector of managed coordinates,
  returns an updated vector of coordinates with version numbers merged in from the
  managed-coordinates vector as necessary."
  [coordinates managed-coordinates]
  (vec (map (partial add-version-from-managed-coords-if-missing managed-coordinates)
            coordinates)))

(defn- coords->Dependencies
  "Converts a coordinates vector to the maven representation, as Dependency objects."
  [files coordinates]
  (->> coordinates
       (map #(if-let [local-file (get files %)]
              (.setArtifact (dependency %)
                            (-> (dependency %)
                                .getArtifact
                                (.setProperties {ArtifactProperties/LOCAL_PATH
                                                 (.getPath (io/file local-file))})))
              (dependency %)))
       vec))

(defn resolve-dependencies*
  "Collects dependencies for the coordinates kwarg, using repositories from the
   `:repositories` kwarg.
   Retrieval of dependencies can be disabled by providing `:retrieve false` as a kwarg.
   Returns an instance of either `org.eclipse.aether.collection.CollectResult` if
   `:retrieve false` or `org.eclipse.aether.resolution.DependencyResult` if
   `:retrieve true` (the default).  If you don't want to mess with the Aether
   implementation classes, then use `resolve-dependencies` instead.

    :coordinates - [[group/name <\"version\"> & settings] ..]
      settings:
      :extension  - the maven extension (type) to require
      :classifier - the maven classifier to require
      :scope      - the maven scope for the dependency (default \"compile\")
      :optional   - is the dependency optional? (default \"false\")
      :exclusions - which sub-dependencies to skip : [group/name & settings]
        settings:
        :classifier (default \"*\")
        :extension  (default \"*\")

    :managed-coordinates - [[group/name \"version\"] ..]
      Used to determine version numbers for any entries in `:coordinates` that
      don't explicitly specify them.

    :repositories - {name url ..} | {name settings ..}
      (defaults to {\"central\" \"https://repo1.maven.org/maven2/\"}
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
        - an instance of org.eclipse.aether.transfer.TransferListener

    :proxy - proxy configuration, can be nil, the host scheme and type must match
      :host - proxy hostname
      :type - http  (default) | http | https
      :port - proxy port
      :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
      :username - username to log in with, may be null
      :password - password to log in with, may be null
      :passphrase - passphrase to log in wth, may be null
      :private-key-file - private key file to log in with, may be null

    :mirrors - {matches settings ..}
      matches - a string or regex that will be used to match the mirror to
                candidate repositories. Attempts will be made to match the
                string/regex to repository names and URLs, with exact string
                matches preferred. Wildcard mirrors can be specified with
                a match-all regex such as #\".+\".  Excluding a repository
                from mirroring can be done by mapping a string or regex matching
                the repository in question to nil.
      settings include these keys, and all those supported by :repositories:
      :name         - name/id of the mirror
      :repo-manager - whether the mirror is a repository manager"

  [& {:keys [repositories coordinates managed-coordinates files retrieve local-repo
             transfer-listener offline? proxy mirrors repository-session-fn]
      :or {retrieve true}}]
  (let [repositories (or repositories maven-central)
        system (repository-system)
        mirror-selector-fn (memoize (partial mirror-selector-fn mirrors))
        mirror-selector (mirror-selector mirror-selector-fn proxy)
        session ((or repository-session-fn
                     repository-session)
                 {:repository-system system
                  :local-repo local-repo
                  :offline? offline?
                  :transfer-listener transfer-listener
                  :mirror-selector mirror-selector})
        coordinates (merge-versions-from-managed-coords coordinates managed-coordinates)
        deps (coords->Dependencies files coordinates)
        managed-deps (coords->Dependencies files managed-coordinates)
        collect-request (doto (CollectRequest. deps
                                               managed-deps
                                               (vec (map #(let [repo (make-repository % proxy)]
                                                            (-> session
                                                                (.getMirrorSelector)
                                                                (.getMirror repo)
                                                                (or repo)))
                                                         repositories)))
                          (.setRequestContext "runtime"))]
    (if retrieve
      (.resolveDependencies system session (DependencyRequest. collect-request nil))
      (.collectDependencies system session collect-request))))

(defn resolve-dependencies
  "Same as `resolve-dependencies*`, but returns a graph of dependencies; each
   dependency's metadata contains the source Aether Dependency object, and
   the dependency's :file on disk.  Please refer to `resolve-dependencies*` for details
   on usage, or use it if you need access to Aether dependency resolution objects."
  [& args]
  (-> (apply resolve-dependencies* args)
    .getRoot
    dependency-graph))

(defn dependency-files
  "Given a dependency graph obtained from `resolve-dependencies`, returns a seq of
   files from the dependencies' metadata."
  [graph]
  (->> graph keys (map (comp :file meta)) (remove nil?)))

(defn- exclusion= [spec1 spec2]
  (let [[dep & opts] (normalize-exclusion-spec spec1)
        [sdep & sopts] (normalize-exclusion-spec spec2)
        om (apply hash-map opts)
        som (apply hash-map sopts)]
    (and (= (group dep)
            (group sdep))
         (= (name dep)
            (name sdep))
         (= (:extension om "*")
            (:extension som "*"))
         (= (:classifier om "*")
            (:classifier som "*"))
         spec2)))

(defn- exclusions-match? [excs sexcs]
  (if-let [ex (first excs)]
    (if-let [match (some (partial exclusion= ex) sexcs)]
      (recur (next excs) (remove #{match} sexcs))
      false)
    (empty? sexcs)))

(defn within?
  "Determines if the first coordinate would be a version in the second
   coordinate. The first coordinate is not allowed to contain a
   version range."
  [[dep version & opts :as coord] [sdep sversion & sopts :as scoord]]
  (let [om (apply hash-map opts)
        som (apply hash-map sopts)]
    (and (coordinates-match? coord scoord)
         (= (:scope om "compile")
            (:scope som "compile"))
         (= (:optional om false)
            (:optional som false))
         (exclusions-match? (:exclusions om) (:exclusions som))
         (or (= version sversion)
             (if-let [[_ ver] (re-find #"^(.*)-SNAPSHOT$" sversion)]
               (re-find (re-pattern (str "^" ver "-\\d+\\.\\d+-\\d+$"))
                        version)
               (let [gsv (GenericVersionScheme.)
                     vc (.parseVersionConstraint gsv sversion)
                     v (.parseVersion gsv version)]
                 (.containsVersion vc v)))))))

(defn dependency-hierarchy
  "Returns a dependency hierarchy based on the provided dependency graph
   (as returned by `resolve-dependencies`) and the coordinates that should
   be the root(s) of the hierarchy.  Siblings are sorted alphabetically."
  [root-coordinates dep-graph]
  (let [root-specs (map (comp dep-spec dependency) root-coordinates)
        hierarchy (for [root (filter
                              #(some (fn [root] (within? % root)) root-specs)
                              (keys dep-graph))]
                    [root (dependency-hierarchy (dep-graph root) dep-graph)])]
    (when (seq hierarchy)
      (into (sorted-map-by #(apply compare (map coordinate-string %&))) hierarchy))))
