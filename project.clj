(defproject buildy "0.1.0-SNAPSHOT"
  :description "build dashboard"
  :url "http://example.com/FIXME"
  :license {:name "WTFPL"
            :url "http://www.wtfpl.net/"}
  :profiles {:dev {:plugins [[lein-ring "0.8.3"]]}}
  :ring {:handler buildy.app/handler
         :init buildy.app/on-startup}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/data.xml "0.0.7"] ; XML
                 [cheshire "5.0.2"] ; JSON
                 [compojure "1.1.5"] ; Routing
                 [hiccup "1.0.2"] ; HTML
                 [clj-http "0.6.4"] ; HTTP
                 [clj-jgit "0.2.1"] ; Git
                 [apage43/cbdrawer "0.1.0"] ; Couchbase
                 [com.taoensso/timbre "1.5.2"] ; Logging
                 ])
