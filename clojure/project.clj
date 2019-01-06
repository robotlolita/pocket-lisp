(defproject pocket-lisp "0.1.0-SNAPSHOT"
  :description "A minimal implementation of Lisp in Clojure"
  :url "https://github.com/robotlolita/pl-playground"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [slingshot "0.12.2"]
                 [org.clojure/tools.cli "0.4.1"]
                 [instaparse "1.4.9"]]
  :plugins [[lein-eftest "0.5.3"]
            [lein-codox "0.10.5"]
            [lein-marginalia "0.9.1"]]
  :eftest {:capture-output? false}
  :main pocket-lisp.core)
