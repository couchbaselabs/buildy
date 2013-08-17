(defproject buildy "0.1.0-SNAPSHOT"
  :description "build dashboard"
  :url "http://example.com/FIXME"
  :license {:name "WTFPL"
            :url "http://www.wtfpl.net/"}
  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/groups/public/"}
  :main buildy.app
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/data.xml "0.0.7"] ; XML
                 [org.clojure/core.memoize "0.5.6"] ; Cachin'
                 [cheshire "5.0.2"] ; JSON
                 [compojure "1.1.5"] ; Routin'
                 [ring/ring-devel "1.1.8"] ; Stacktraces
                 [hiccup "1.0.2"] ; HTMLn'
                 [clj-http "0.6.4"] ; HTTP'in
                 [clj-jgit "0.2.1"] ; Gittin'
                 [apage43/cbdrawer "0.1.0"] ; Couchbasin'
                 [com.taoensso/timbre "1.5.2"] ; Loggin'
                 [me.raynes/conch "0.5.0"] ; Shellin'
                 [aleph "0.3.0-rc1"] ; Networkin'
                 ])
