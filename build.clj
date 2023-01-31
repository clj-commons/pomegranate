(ns build
  (:require [build-shared]
            [clojure.tools.build.api :as b]))

(def version (build-shared/lib-version))
(def lib (build-shared/lib-artifact-name))

;; build constants
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn jar [_]
  (println "jarring version" version)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :scm {:tag (build-shared/version->tag version)}
                :basis basis
                :src-dirs ["src/clojure"]})
  (b/copy-dir {:src-dirs ["src/clojure"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar {})
  (println "installing version" version)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [opts]
  (jar opts)
  (println "deploy")
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   (merge {:installer :remote
           :artifact jar-file
           :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
          opts))
  opts)
