(ns cemerick.pomegranate-test
  (:require [cemerick.pomegranate :as p]
            [cemerick.pomegranate.test-report]
            clojure.java.io)
  (:use clojure.test))

(deftest resources
  (is (= (first (p/resources "META-INF/MANIFEST.MF"))
        (clojure.java.io/resource "META-INF/MANIFEST.MF")))
  
  ; the last classloader should be ext, for e.g. $JAVA_HOME/lib/ext/*
  (is (->> (p/resources [(last (p/classloader-hierarchy))] "META-INF/MANIFEST.MF")
        (map str)
        (filter #(.contains ^String % "clojure"))
        empty?))
  
  (is (->> (p/resources (butlast (p/classloader-hierarchy)) "META-INF/MANIFEST.MF")
        (map str)
        (filter #(.contains ^String % "clojure"))
        seq)))
