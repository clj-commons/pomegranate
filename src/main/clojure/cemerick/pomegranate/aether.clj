(ns cemerick.pomegranate.aether
  "An abstraction over the Maven Artifact Resolver."
  (:refer-clojure :exclude  [type proxy])
  (:require [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.stacktrace :as stacktrace])
  (:import (org.eclipse.aether RepositorySystem RepositorySystemSession DefaultRepositorySystemSession)
           (org.eclipse.aether.transport.wagon WagonTransporterFactory
                                               WagonProvider)
           (org.eclipse.aether.transport.file FileTransporterFactory)
           (org.eclipse.aether.transfer TransferListener)
           (org.eclipse.aether.artifact Artifact)
           (org.eclipse.aether.spi.connector RepositoryConnectorFactory)
           (org.eclipse.aether.spi.connector.transport TransporterFactory)
           (org.eclipse.aether.repository Proxy
                                          RepositoryPolicy LocalRepository RemoteRepository$Builder
                                          MirrorSelector)
           (org.eclipse.aether.util.repository DefaultProxySelector AuthenticationBuilder)
           (org.eclipse.aether.graph Dependency Exclusion DependencyNode)
           (org.eclipse.aether.collection CollectRequest CollectResult)
           (org.eclipse.aether.resolution DependencyRequest DependencyResult ArtifactRequest
                                          ArtifactResult VersionRequest)
           (org.eclipse.aether.artifact DefaultArtifact ArtifactProperties)
           (org.eclipse.aether.deployment DeployRequest)
           (org.eclipse.aether.installation InstallRequest)
           (org.eclipse.aether.util.version GenericVersionScheme)
           (org.eclipse.aether.connector.basic BasicRepositoryConnectorFactory)
           (org.eclipse.aether.impl DefaultServiceLocator$ErrorHandler)
           (org.apache.maven.repository.internal MavenRepositorySystemUtils)))

(def ^{:private true} default-local-repo
  (io/file (System/getProperty "user.home") ".m2" "repository"))

(def maven-central
  "Although we call this var `maven-central`, it is used as a map of default maven repositories for resolution."
  {"central" "https://repo1.maven.org/maven2/"} )

(def ^{:private true} wagon-factories
  ;; there were issues with the HTTP lightweight Wagon (now deprecated by Maven),
  ;; so we match the Maven tool itself and use HttpWagon.
  (atom {"https" #(org.apache.maven.wagon.providers.http.HttpWagon.)
         "http" #(throw (Exception. "Tried to use insecure HTTP repository."))}))

(defn register-wagon-factory!
  "⚙️  This is considered a lower level function.
   It allows you to add communication channels to talk to maven repositories that aren't over the included https channel.

   Registers a no-arg `factory-fn` function for the given `scheme`. The `factory-fn`
   must return an implementation of `org.apache.maven.wagon.Wagon`."
  [scheme factory-fn]
  (swap! wagon-factories (fn [m]
                           (when-let [fn (and (not= scheme "http") (m scheme))]
                             (println (format "Warning: replacing existing support for %s repositories (%s) with %s" scheme fn factory-fn)))
                           (assoc m scheme factory-fn))))

(deftype PomegranateWagonProvider []
  WagonProvider
  (release  [_ _wagon])
  (lookup [_ role-hint]
          (when-let [f (get @wagon-factories role-hint)]
            (try
              (f)
              (catch Exception e
                (stacktrace/print-cause-trace e)
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

(defn- stdout-listener-fn
  [{:keys [type method _transferred resource error] :as _evt}]
  (let [{:keys [name size repository _transfer-start-time]} resource]
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
      (:corrupted :failed) (when error (println (.getMessage ^Exception error)))
      nil)))

(defn- repository-system
  []
  (let [error-handler (clojure.core/proxy [DefaultServiceLocator$ErrorHandler] []
                        (serviceCreationFailed [type-clazz impl-clazz ^Throwable e]
                          (stacktrace/print-cause-trace e)))]
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
    (TransferListenerProxy. (comp stdout-listener-fn transfer-event))

    (fn? transfer-listener)
    (TransferListenerProxy. (comp transfer-listener transfer-event))

    :else (TransferListenerProxy. (fn [_]))))

(defn- generate-checksums-by-default
  "Return repository `session` configured with pomegranate checksum defaults.

   By default, maven resolver does not generate checksums for .asc files, but pomegranate prefers to do so.

   We automatically upconvert the legacy (and removed) `aether.checksums.forSignature`
   to its replacement `aether.checksums.omitChecksumsForExtensions`.
   If both options are specified `aether.checksums.omitChecksumsForExtensions` takes precedence."
  [^DefaultRepositorySystemSession session]
  (let [config (.getConfigProperties session)
        option "aether.checksums.omitChecksumsForExtensions"
        value-to-generate-checksums "" ;; as per maven docs
        value (get config option)
        legacy-option "aether.checksums.forSignature"
        legacy-value (get config legacy-option)]
    (if (and (nil? value)
             (or (nil? legacy-value) (= "true" legacy-value) (true? legacy-value)))
      (.setConfigProperty session option value-to-generate-checksums)
      session)))

(defn repository-session
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.

   Returns a repository session created from options map.

   Typically used when overriding via `:repository-session-fn` option available
   in various aether fns. In this usage, your `:repository-session-fn` will typically
   pass through the options map to `repository-session` but then will tweak the
   returned session to your specific needs."
  [{:keys [repository-system local-repo offline? transfer-listener mirror-selector]}]
  (let [session (org.apache.maven.repository.internal.MavenRepositorySystemUtils/newSession)
        session (doto session
                  (.setLocalRepositoryManager (.newLocalRepositoryManager
                                               ^RepositorySystem repository-system
                                               session
                                               (LocalRepository.
                                                (io/file (or local-repo default-local-repo)))))
                  (.setMirrorSelector mirror-selector)
                  (.setOffline (boolean offline?))
                  (.setTransferListener (construct-transfer-listener transfer-listener)))]
    (generate-checksums-by-default session)))

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
  (doto ^org.eclipse.aether.repository.RemoteRepository$Builder repo-builder
    (.setSnapshotPolicy (policy settings (:snapshots settings true)))
    (.setReleasePolicy (policy settings (:releases settings true)))))

(defn- authentication
  [{:keys [username password passphrase private-key-file] :as _settings}]
  (-> (AuthenticationBuilder.)
      (.addUsername username)
      (.addPassword ^String password)
      (.addPrivateKey ^String private-key-file ^String passphrase)
      .build))

(defn- set-authentication
  [repo-builder {:keys [username password passphrase private-key-file] :as settings}]
  (if (or username password private-key-file passphrase)
    (.setAuthentication ^org.eclipse.aether.repository.RemoteRepository$Builder repo-builder (authentication settings))
    repo-builder))

(defn- set-proxy
  [repo-builder {:keys [type host port non-proxy-hosts]
                 :or {type "http"}
                 :as proxy}]
  (if (and host port)
    (let [prx-sel (doto (DefaultProxySelector.)
                    (.add (Proxy. type host port (authentication proxy))
                          non-proxy-hosts))
          prx (.getProxy prx-sel (.build ^org.eclipse.aether.repository.RemoteRepository$Builder repo-builder))] ; ugg.
      ;; Don't know how to get around "building" the repo for this
      (.setProxy ^org.eclipse.aether.repository.RemoteRepository$Builder repo-builder prx))
    repo-builder))

(defn make-repository
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.

   Returns an `org.eclipse.aether.repository.RemoteRepository` instance for repository `id` with `settings` using `proxy`

   - `id` - name of maven repository
   - `settings` - is either a string representing the URL of the repository or an options map:
      - `:url` - string URL of the repository
      - `:snapshots` (optional, default `true`) - use snapshots versions?
      - `:releases` (optional, default `true`) - use release versions?
      - `:username` (as required by repository) - login username for repository
      - `:password` (as required by repository) - login password for repository
      - `:passphrase` (as required by repository) - login passphrase for repository
      - `:private-key-file` - (as required by repository) login private key file for repository
      - `:update` - (optional) `:daily` (default) | `:always` | `:never`
      - `:checksum` - (optional) `:fail` (default) | `:ignore` | `:warn`
   - `proxy` - same as `:proxy` for [[deploy]]"
  ^org.eclipse.aether.repository.RemoteRepository
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
  [[_group-artifact _version & {:keys [_scope _optional _exclusions]} :as dep-spec]]
  (DefaultArtifact. (coordinate-string dep-spec)))

(defn dependency
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.

   Returns an `org.eclipse.aether.graph.Dependency` instance converted from a lein-style dependency vector."
  ([dep-spec] (dependency dep-spec "compile"))
  ([[_group-artifact _version & {:keys [scope optional exclusions]
                                 :as   _opts
                                 :or   {optional false}}
     :as dep-spec]
    default-scope]
   (Dependency. (artifact dep-spec)
                (or scope default-scope)
                optional
                (map (comp exclusion normalize-exclusion-spec) exclusions))))

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
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.
   Consider instead: [[deploy]].

   Deploy artifact `:files` to `:repository`.

   kwarg options:
   - `:files` - describes files to be deployed.
      Map key is artifact vector coordinate and value is associated `java.io.File` file.
      All artifacts should have the same version, group and artifact IDs.
      Examples of artifact vectors keys:
      - `'[group/artifact \"1.0.0\"]` or
      - `'[group/artifact \"1.0.0\" :extension \"pom\"]`
   - `:repository` - same as [[deploy]]
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:transfer-listener` - same as [[deploy]]
   - `:proxy` - same as [[deploy]]
   - `:repository-session-fn` - (optional, defaults to [[repository-session]])"

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
    (.deploy ^RepositorySystem system session
             (doto (DeployRequest.)
               (.setArtifacts (vec (map (partial create-artifact files) (keys files))))
               (.setRepository (first (map #(make-repository % proxy) repository)))))))

(defn install-artifacts
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.
   Consider instead: [[install]].

   Deploy the `:files` arg using the coordinates kwarg to the repository kwarg.

   kwarg options:
   - `:files` - see [[deploy]]
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:transfer-listener` - see [[deploy]]
   - `:repository-session-fn` - (optional, defaults to [[repository-session]])"

  [& {:keys [files local-repo transfer-listener repository-session-fn]}]
  (let [system (repository-system)
        session ((or repository-session-fn
                     repository-session)
                 {:repository-system system
                  :local-repo local-repo
                  :offline? false
                  :transfer-listener transfer-listener})]
    (.install ^RepositorySystem system session
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
  "Deploy `:jar-file` and `:pom-file` to `:coordinates` in maven `:repository`.

   For more control use `:artifact-map` in place of, or in addition to, `:jar-file` and `:pom-file`.

   kwarg options:
   - `:coordinates` - lein-style `'[group/name \"version\"]`
   - `:artifact-map` - (optional) describes artifacts to be deployed, see also `:jar-file` and `:pom-file`.
      Map key is a partial artifact vector and value is associated `java.io.File` file.
      Coordinates automatically populated from `:coordinates` and should not be respecified.
      Examples of partial artifact vectors keys:
      - `[]` - for a jar
      - `[:extension \"pom\"]` - for a pom
   - `:jar-file` - a `java.io.File` pointing to the jar
   - `:pom-file` - a `java.io.File` pointing to the pom
   - `:repository` - single entry map of `name` to either
     - `url` string
     - `settings` map of
        - `:url` - string URL of the repository
        - `:snapshots` (optional, default `true`) - use snapshots versions?
        - `:releases` (optional, default `true`) - use release versions?
        - `:username` (as required by repository) - login username for repository
        - `:password` (as required by repository) - login password for repository
        - `:passphrase` (as required by repository) - login passphrase for repository
        - `:private-key-file` - (as required by repository) login private key file for repository
        - `:update` - (optional) `:daily` (default) | `:always` | `:never`
        - `:checksum` - (optional) `:fail` (default) | `:ignore` | `:warn`
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:transfer-listener` - (optional, default no listener), can be:
      - `:stdout`, writes notifications and progress indicators to stdout, suitable for an
         interactive console program
      - a function of one argument, which will be called with a map derived from
        each event:
        - `:type` - `:initiated`, `:started`, `:progressed`, `:corrupted`, `:succeeded`, or `:failed`
        - `:method` - `:get` or `:put`
        - `:transferred` - number of bytes transferred
        - `:error` - the `Exception` that occured, if any, during the transfer
        - `:data-buffer` - the `java.nio.ByteBuffer` holding the transferred bytes since the last event
        - `:data-length` - the number of bytes transferred since the last event
        - `:resource` - a map of:
           - `:repository` - string URL of the repository
           - `:name` - string path of the resource relative to the repository's base url
           - `:file` - the local `File` being uploaded or downloaded
           - `:size` - the size of the resource
           - `:transfer-start-time` - long epoch
           - `:trace` - `org.eclipse.aether.RequestTrace` instance
     - an instance of `org.eclipse.aether.transfer.TransferListener`
   - `:proxy` - (optional, default no proxy) the `:host` scheme and `:type` must match
     - `:host` - proxy hostname
     - `:type` - (optional, default `\"http\"`) | `\"https\"`
     - `:port` - proxy port
     - `:non-proxy-hosts` - (optional) The list of hosts to exclude from proxying
     - `:username` - (optional) login username
     - `:password` - (optional) login password
     - `:passphrase` - (optional) login passphrase
     - `:private-key-file` - (optional) login private key file
   - `:repository-session-fn` - (optional, defaults to [[repository-session]])"
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
  "Install `:jar-file` and `:pom-file` to `:coordinates` in `:local-repo`.

   For more control use `:artifact-map` in place of, or in addition to, `:jar-file` and `:pom-file`.

   kwarg options:
   - `:coordinates` - lein-style `'[group/name \"version\"]`
   - `:artifact-map` - same as [[deploy]]
   - `:jar-file` - a `java.io.File` pointing to the jar
   - `:pom-file` - a `java.io.File` pointing to the pom
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:transfer-listener` - same as [[deploy]]"
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
                           cset/union
                           (->> (.getChildren n)
                             (map #(.getDependency ^DependencyNode %))
                             (map dep-spec)
                             set))
                g))
            {}
            (tree-seq (constantly true)
                      #(seq (.getChildren ^DependencyNode %))
                      node))))

(defn- mirror-selector-fn
  "Default mirror selection function.  The first argument should be a map
   like that described as the :mirrors argument in resolve-dependencies.
   The second argument should be a repository spec, also as described in
   resolve-dependencies.  Will return the mirror spec that matches the
   provided repository spec."
  [mirrors {:keys [name url _snapshots _releases]}]
  (let [mirrors (filter (fn [[matcher _mirror-spec]]
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
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.
   Consider instead: [[resolve-artifacts]].

   Resolves artifacts for `:coordinates` from `:repositories`.

   Returns an sequence of either :
   - `org.eclipse.aether.ArtifactResult` when `:retrieve true` (the default)
   - `org.eclipse.aether.VersionResult` when `:retrieve false`

   If you don't want to mess with the Aether implementation classes, then use
   [[resolve-artifacts]] instead.

   See [[resolve-artifacts]] for kwarg options."
  [& {:keys [repositories coordinates files retrieve local-repo
             transfer-listener offline? proxy mirrors repository-session-fn]
      :or {retrieve true}}]
  (when repositories
    (assert (seq repositories)
            "Empty but truthy `repositories` value found. Please set to nil for using the default, or add a non-empty coll of repositories."))
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
                          (-> ^Artifact (artifact %)
                              (.setProperties
                               {ArtifactProperties/LOCAL_PATH
                                (.getPath (io/file local-file))}))
                          (artifact %)))
                  vec)
        repositories (vec (map #(let [repo (make-repository % proxy)]
                                  (-> ^RepositorySystemSession session
                                      (.getMirrorSelector)
                                      (.getMirror repo)
                                      (or repo)))
                               repositories))]
    (if retrieve
      (.resolveArtifacts
       ^RepositorySystem system session (map #(ArtifactRequest. % repositories nil) deps))
      (doall
       (for [dep deps]
         (.resolveVersion
          ^RepositorySystem system session (VersionRequest. dep repositories nil)))))))

(defn resolve-artifacts
  "Resolves artifacts for `:coordinates` in `:repositories`.

   Same as [[resolve-artifacts*]], but returns a sequence of lein-style dependency vectors; each
   adorned with metadata:
   - `:dependency` - the Aether dependency object
   - `:file` - the artifact's `java.io.File` on disk.

   kwarg options:
    - `:coordinates` - lein-style `'[[group/name \"version\" & settings] ..]`
      where settings is kwargs of:
      - `:extension` - (optional) - the maven extension type to require, for example: `\"pom\"`
      - `:classifier` - (optional) - the maven classifier to require, for example: `\"sources\"`
      - `:scope` - (optional, default `\"compile\"`) - the maven scope for the dependency
      - `:optional`   - (optional, default `false`) - is the dependency optional?
      - `:exclusions` - (optional) which sub-dependencies to skip : lein-style `[group/name & settings]`
         where settings is kwargs of:
         - `:classifier` (optional, default `\"*\"`)
         - `:extension`  (optional, default `\"*\"`)
   - `:repositories`- (optional, default `{\"central\" \"https://repo1.maven.org/maven2/\"}`)
      map of `name` to either
      - `url` string
      - `settings` map of
        - `:url` - string URL of the repository
        - `:snapshots` (optional, default `true`) - use snapshots versions?
        - `:releases` (optional, defalt `true`) - use release versions?
        - `:username` (as required by repository) - login username for repository
        - `:password` (as required by repository) - login password for repository
        - `:passphrase` (as required by repository) - login passphrase for repository
        - `:private-key-file` - (as required by repository) login private key file for repository
        - `:update` - (optional) `:daily` (default) | `:always` | `:never`
        - `:checksum` - (optional) `:fail` (default) | `:ignore` | `:warn`
   - `:retrieve` - (optional, default `true`) - specify `false` to disable downloading of dependencies
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:offline?` - if `true`, no remote repositories will be contacted
   - `:transfer-listener` - same as [[deploy]]
   - `:proxy` - same as [[deploy]]
   - `:mirrors` - map of `match` to `settings` where:
      - `match` is a string or regex that will be used to match the mirror to
         candidate repositories. Attempts will be made to match the
         string/regex to repository names and URLs, with exact string
         matches preferred. Wildcard mirrors can be specified with
         a match-all regex such as `#\".+\"`.  Excluding a repository
         from mirroring can be done by mapping a string or regex matching
         the repository in question to nil.
      - `settings` includes the following keys, and all those supported by `:repositories` `:settings`.
        - `:name` - name/id of the mirror
        - `:repo-manager` - whether the mirror is a repository manager
   - `:repository-session-fn` - (optional, defaults to [[repository-session]])"
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
  [[dep _version & opts] [sdep _sversion & sopts]]
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
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.

   Returns `coordinates` with any `nil` or missing versions merged in from
   `managed-coordinates`.

   Coordinates are lein-style, e.g. `'[[group/name \"version\" & settings] ..]`"
  [coordinates managed-coordinates]
  (vec (map (partial add-version-from-managed-coords-if-missing managed-coordinates)
            coordinates)))

(defn- coords->Dependencies
  "Converts a coordinates vector to the maven representation, as Dependency objects."
  [files coordinates default-scope]
  (->> coordinates
       (map #(let [^Dependency dep (dependency % default-scope)]
               (if-let [local-file (get files %)]
                 (.setArtifact dep
                               (-> dep
                                   .getArtifact
                                   (.setProperties {ArtifactProperties/LOCAL_PATH
                                                    (.getPath (io/file local-file))})))
                 dep)))
       vec))

(defn resolve-dependencies*
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.
   Consider instead: [[resolve-dependencies]].

   Returns a graph of dependencies for `:coordinates` in `:repositories`.

   Returns an instance of either:
   - `org.eclipse.aether.resolution.DependencyResult` if `:retrieve true` (the default)
   - `org.eclipse.aether.collection.CollectResult` if `:retrieve false`

   If you don't want to mess with the Aether implementation classes, then use
   [[resolve-dependencies]] instead.

   See [[resolve-dependencies]] for kwarg options."
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
        deps (coords->Dependencies files coordinates "compile")
        managed-deps (coords->Dependencies files managed-coordinates nil)
        collect-request (doto (CollectRequest. ^java.util.List deps
                                               ^java.util.List managed-deps
                                               ^java.util.List
                                               (vec (map #(let [repo (make-repository % proxy)]
                                                            (-> ^RepositorySystemSession session
                                                                (.getMirrorSelector)
                                                                (.getMirror repo)
                                                                (or repo)))
                                                         repositories)))
                          (.setRequestContext "runtime"))]
    (if retrieve
      (.resolveDependencies ^RepositorySystem system session (DependencyRequest. collect-request nil))
      (.collectDependencies ^RepositorySystem system session collect-request))))

(defn resolve-dependencies
  "Returns a graph of dependencies for `:coordinates` in `:repositories`.

   Same as [[resolve-dependencies*]], but returns a graph of lein-style dependency vectors; each
   adorned with metadata:
   - `:dependency` - the Aether dependency object
   - `:file` - the artifact's `java.io.File` on disk.

   kwarg options:
   - `:coordinates` - same as [[resolve-artifacts]]
   - `:managed-coordinates` - (optional) lein-style `[['group/name \"version\"] ..]`
      Used to determine version numbers for any entries in `:coordinates` that
      don't explicitly specify them.
   - `:repositories` -  same as [[resolve-artifacts]]
   - `:retrieve` - (optional, default `true`) - specify `false` to disable downloading of dependencies
   - `:local-repo` - (optional, default `~/.m2/repository`) - `java.io.File` path to the local repository
   - `:offline?` - if `true`, no remote repositories will be contacted
   - `:transfer-listener` - same as [[deploy]]
   - `:proxy` - same as [[deploy]]
   - `:mirrors` - same as [[resolve-artifacts]]
   - `:repository-session-fn` - (optional, defaults to [[repository-session]])"
  [& args]
  (let [result (apply resolve-dependencies* args)]
    (if (instance? DependencyResult result)
      (-> ^DependencyResult result
          .getRoot
          dependency-graph)
      (-> ^CollectResult result
          .getRoot
          dependency-graph))))

(defn dependency-files
  "Returns a seq of `java.io.Files`s from dependency metadata in `graph` (as returned from [[resolve-dependencies]])"
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
  "⚙️  This is considered a lower level function, and used by other aether fns.
   Use it if you need to, but there's a good chance you won't need to.

   Returns `true` if the first coordinate `coord` is a version within the second
   coordinate `scoord`. Only the second coordinate is allowed to contain a
   version range."
  [[_dep version & opts :as coord] [_sdep sversion & sopts :as scoord]]
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
  "Returns a dependency hierarchy based on `dep-graph`
   (as returned by [[resolve-dependencies]]) and `root-coordinates`.

   `root-coordinates` should be the root(s) of the hierarchy.

   Siblings are sorted alphabetically."
  [root-coordinates dep-graph]
  (let [root-specs (map (comp dep-spec dependency) root-coordinates)
        hierarchy (for [root (filter
                              #(some (fn [root] (within? % root)) root-specs)
                              (keys dep-graph))]
                    [root (dependency-hierarchy (dep-graph root) dep-graph)])]
    (when (seq hierarchy)
      (into (sorted-map-by #(apply compare (map coordinate-string %&))) hierarchy))))
