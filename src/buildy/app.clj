(ns buildy.app
  (:gen-class)
  (:use compojure.core
        ring.middleware.session
        ring.middleware.stacktrace)
  (:require [compojure.route :as route]
            [taoensso.timbre :as timbre
             :refer [spy trace debug info warn error]]
            [ring.util.response :as response]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [cbdrawer.client :as cb]
            [cbdrawer.transcoders :refer [json-transcoder]]
            [cbdrawer.view :as cb-view]
            [buildy.design-docs :as ddocs]
            [buildy.realtime :as rt]
            [buildy.manifest :as mf]
            [aleph.http :refer [start-http-server wrap-ring-handler
                                wrap-aleph-handler]]
            [lamina.core :as lam]))

(defonce ^:private appcfg* (atom nil))

(defn initial-cfg [init]
  (if-not init
    (let [cbfs-fact (cb/factory "cbfs" "" "http://mango:8091/")]
      (info "Initializing Application")
      (cb/set-transcoder! json-transcoder)
      (ddocs/install-all cbfs-fact)
      {:cbfs-fact cbfs-fact
       :cbfs-bucket (cb/client cbfs-fact)
       :cbfs-capis (cb/capi-bases cbfs-fact)
       :cbfs-base "http://cbfs.hq.couchbase.com:8484/"
       :builds-bot "http://builds.hq.northscale.net/latestbuilds/"})
    init))

(defn appcfg []
  (if-let [cfg @appcfg*]
    cfg
    (swap! appcfg* initial-cfg)))

(defn json-response [obj]
  (-> (response/response (json/generate-string obj))
      (response/content-type "application/json; charset=utf-8")))

(defn cbfs-builds-list []
  (let [{:keys [cbfs-capis]} (appcfg)
        build-view (cb-view/view-url cbfs-capis "buildboard" "builds")]
    (json-response
      (cb-view/view-seq build-view
                        {:endkey ["date"]
                         :startkey ["date" {}]
                         :descending true
                         :include_docs true}))))

(defn proxy-to [url & [rq]]
  (let [resp (http/get url {:as :stream
                            :headers (:headers rq)
                            :throw-exceptions false})
        headers (select-keys (:headers resp)
                             ["content-type"
                              "last-modified"
                              "etag"
                              "content-length"] )]
    (assoc (select-keys resp [:body :status])
           :headers headers)))

(defn get-manifest [build]
  (let [{:keys [cbfs-base builds-bot]} (appcfg)
        cbfsurl (str cbfs-base "builds/" build ".manifest.xml")
        boturl (str builds-bot build ".manifest.xml")
        meta-resp (http/get cbfsurl {:throw-exceptions false})]
    (if (= 200 (:status meta-resp))
      (io/input-stream cbfsurl)
      ;otherwise
      (do (info "Manifest not in CBFS, copying to CBFS, and proxying to buildbot:" build boturl)
          (let [bot-resp (http/get boturl)]
            (http/put cbfsurl {:body (:body bot-resp)}))
          (io/input-stream cbfsurl)))))

(defn download-build [rq]
  (let [{:keys [cbfs-base]} (appcfg)
        build (get-in rq [:params :build])]
    (proxy-to (str cbfs-base "builds/" build) rq)))

(defn teardown []
  (cb/shutdown (:cbfs-bucket (appcfg)))
  (reset! appcfg* nil))

(defmacro asyncly [& body]
  `(wrap-aleph-handler (fn [ch# rq#]
                         (future
                           (lam/enqueue ch# (do ~@body))))))

(defroutes app-routes
  (GET "/ensure-queue" {:keys [session]}
       (assoc (json-response "OK") :session
              {:headers {"cache-control" "no-cache, no-store, must-revalidate"
                         "pragma" "no-cache"
                         "expires" 0}
               :queue (or (:queue session)
                          (rt/new-queue))}))
  (GET "/scrape-queue" [] (asyncly (json-response (rt/collect-messages))))
  (GET "/allbuilds" [] (cbfs-builds-list))
  (GET "/get/:build" rq (download-build rq))
  (GET "/manifest/:build" [build] (get-manifest build))
  (GET "/manifest-info/:build" [build]  (json-response
                                            (-> build
                                                get-manifest
                                                mf/read-manifest
                                                mf/attach-logs
                                                mf/describe-projects)))
  (GET "/comparison-info/:build-a/:build-b" [build-a build-b]
       (json-response
         (apply mf/compare-builds
                (mapv (comp mf/read-manifest get-manifest) [build-a build-b]))))
  (GET "/" [] {:body (slurp (io/resource "public/index.html"))
               :headers {"content-type" "text/html"}})
  (route/resources "/")
  (route/not-found "404!"))

(def handler
  (-> app-routes
      (wrap-stacktrace)
      (rt/wrap-queue)
      (wrap-session)))

(defn -main [& args]
  (appcfg)
  (start-http-server (wrap-ring-handler handler) {:port 3000}))
