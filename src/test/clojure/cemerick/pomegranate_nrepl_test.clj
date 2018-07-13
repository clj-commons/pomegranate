(ns cemerick.pomegranate-nrepl-test
  (:require [nrepl.core :as repl]
            [nrepl.server :as server])
  (:use clojure.test))

(def port 7888)

(defn nrepl-eval [code]
  (with-open [conn (repl/connect :port port)]
    (-> (repl/client conn 1000)
        (repl/message {:op :eval :code code})
        repl/response-values)))

;; without this openjdk-7 fail in travis, but there's no exception
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (doto (System/err)
       (.println (str "Thread:" (.getName thread) " raised:" (.toString ex)))
       (.flush)))))

(deftest add-dependencies-inside-nrepl
  (let [server (server/start-server :port port)
        resp (nrepl-eval "(require 'cemerick.pomegranate)
                          (str (cemerick.pomegranate/add-dependencies :coordinates '[[javax.servlet/servlet-api \"2.5\"]]))
                          (str (import 'javax.servlet.http.HttpServlet))")]
    (is (= resp [nil
                 "{[javax.servlet/servlet-api \"2.5\"] nil}"
                 "class javax.servlet.http.HttpServlet"])) ;; nil means class not loaded
    (server/stop-server server)))
