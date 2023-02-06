(ns cemerick.pomegranate-isolated-test
  "These tests modify the classpath and are to be run in their own isolated process.
  They are not REPL friendly and will fail if run multiple times."
  (:require [cemerick.pomegranate :as p]
            [cemerick.pomegranate.aether :as aether]
            [cemerick.pomegranate.test-report]
            [clojure.test :refer [deftest is]]))

;; Simulate dynamic classloader available by default in a REPL session
(defn bind-dynamic-loader
  "Ensures the clojure.lang.Compiler/LOADER var is bound to a DynamicClassLoader,
  so that we can add to Clojure's classpath dynamically."
  []
  (when-not (bound? Compiler/LOADER)
    (.bindRoot Compiler/LOADER (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader)))))

(defn simulated-repl-loader [] (deref clojure.lang.Compiler/LOADER))

(deftest add-dependency-to-classpath
  (is (thrown-with-msg? java.io.FileNotFoundException #"Could not locate"
                        (require '[clojure.math.numeric-tower :as math])))
  (bind-dynamic-loader)
  ;; numeric tower is hosted on maven so default repositories is fine
  (p/add-dependencies :classloader (simulated-repl-loader)
                      :coordinates [['org.clojure/math.numeric-tower "0.0.5"]])
  (require '[clojure.math.numeric-tower :as math])
  ;; need a resolve because require is not top-level (requiring-resolve is Clojure 1.10+ so we avoid it)
  (is (= 4 ((resolve 'math/expt) 2 2))))

(deftest add-dependencies-to-classpath
  (is (thrown-with-msg? java.io.FileNotFoundException #"Could not locate"
                        ;; not crazy about single segment ns as an example but matches README
                        (require '[incanter.core :as i])))
  (bind-dynamic-loader)
  ;; incanter is hosted on clojars
  ;; pick an ancient version for compatibility with Pomegranate supported min clojure version
  (p/add-dependencies :classloader (simulated-repl-loader)
                      :coordinates [['incanter/incanter "1.4.1"]]
                      :repositories (assoc aether/maven-central "clojars" "https://repo.clojars.org"))
  (require '[incanter.core :as i])
  ;; need a resolve because require is not top-level (requiring-resolve is Clojure 1.10+ so we avoid it)
  (is (= 3 ((resolve 'i/length) [1 2 3]))))
