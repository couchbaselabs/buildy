(ns buildy.app
  (:gen-class)
  (:use compojure.core
        ring.middleware.session
        ring.middleware.stacktrace)
  (:require [compojure.route :as route]
            [compojure.handler :as ch]
            [taoensso.timbre :as timbre
             :refer [spy trace debug info warn error]]
            [ring.util.response :as response]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [cbdrawer.client :as cb]
            [clojure.core.memoize :as memo]
            [cbdrawer.transcoders :refer [json-transcoder]]
            [cbdrawer.view :as cb-view]
            [buildy.design-docs :as ddocs]
            [buildy.realtime :as rt]
            [buildy.manifest :as mf]
            [ring.util.response :refer [redirect]]
            [aleph.http :refer [start-http-server wrap-ring-handler
                                wrap-aleph-handler]]
            [lamina.core :as lam]))

(defonce ^:private appcfg* (atom nil))

(defn initial-cfg [init {:keys [dev] :or {dev false}}]
  (if-not init
    (let [cbfs-fact (cb/factory "cbfs" "" "http://mango:8091/")]
      (info "Initializing Application")
      (cb/set-transcoder! json-transcoder)
      (ddocs/install-all cbfs-fact (if dev "dev_" ""))
      {:cbfs-fact cbfs-fact
       :ddoc-prefix (if dev "dev_" "")
       :cbfs-bucket (cb/client cbfs-fact)
       :cbfs-capis (cb/capi-bases cbfs-fact)
       :cbfs-base "http://cbfs.hq.couchbase.com:8484/"
       :builds-bot "http://builds.hq.northscale.net/latestbuilds/"})
    init))

(defn appcfg [& {:as kwargs}]
  (if-let [cfg @appcfg*]
    cfg
    (swap! appcfg* initial-cfg kwargs)))

(defn json-response [obj]
  (-> (response/response (json/generate-string obj))
      (response/content-type "application/json; charset=utf-8")))

(defn cbfs-builds-list* []
  (let [{:keys [cbfs-capis ddoc-prefix]} (appcfg)
        build-view (cb-view/view-url cbfs-capis (str ddoc-prefix "buildboard") "builds")]
    (cb-view/view-seq build-view
                        {:endkey ["date"]
                         :startkey ["date" {}]
                         :full_set true
                         :descending true
                         :include_docs true})))

(def cbfs-builds-list (memo/ttl cbfs-builds-list* :ttl/threshold 60000))

(defn cbfs-filtercats []
  (let [{:keys [cbfs-capis ddoc-prefix]} (appcfg)
        build-view (cb-view/view-url cbfs-capis (str ddoc-prefix "buildboard") "builds")]
    (-> (cb-view/view-seq build-view
                        {:reduce true
                         :full_set true})
        first :value)))

(defn proxy-to [url & [rq]]
  (let [resp (http/get url {:as :stream
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
      (:body (http/get cbfsurl {:as :stream}))
      ;otherwise
      (do (info "Manifest not in CBFS, copying to CBFS, and proxying to buildbot:" build boturl)
          (let [bot-resp (http/get boturl)]
            (http/put cbfsurl {:body (:body bot-resp)}))
          (:body (http/get cbfsurl {:as :stream}))))))

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

(defn remove-projects [manifest & to-remove]
  (update-in manifest [:projects]
             (fn [projects]
               (let [all-ps (keys projects)]
                 (select-keys projects (remove (set to-remove) all-ps))))))

(defn apply-filter [buildfilter buildlist]
  (if buildfilter
    (->> buildlist
         (filter
           (fn [row]
             (every?
               (fn [cat]
                 (when-let [rowcat (-> row :value cat)]
                   (some #(= rowcat %) (buildfilter cat))))
               (filter
                 (comp
                   #(not (.startsWith % "_"))
                   name)
                 (keys buildfilter))))))
    buildlist))

(defroutes app-routes
  (GET "/ensure-queue" {:keys [session]}
       (assoc (json-response "OK") :session
              {:headers {"cache-control" "no-cache, no-store, must-revalidate"
                         "pragma" "no-cache"
                         "expires" 0}
               :queue (or (:queue session)
                          (rt/new-queue))}))
  (GET "/scrape-queue" [] (asyncly (json-response (rt/collect-messages))))
  (GET "/allbuilds" [skip limit buildfilter]
       (->> (cbfs-builds-list)
            (apply-filter (some-> buildfilter (json/parse-string true)))
            (drop (or (some-> skip read-string) 0))
            (take (or (some-> limit read-string) 20))
            json-response))
  (GET "/get/:build" [build] (redirect (str "http://cbfs-ext.hq.couchbase.com/builds/" build)))
  (GET "/filtercats" rq (json-response (cbfs-filtercats)))
  (GET "/manifest/:build" [build] {:headers {"content-type" "text/xml"}
                                   :body (get-manifest build)})
  (GET "/manifest-info/:build" [build]  (json-response
                                          (-> build
                                              get-manifest
                                              mf/read-manifest
                                              (remove-projects "testrunner")
                                              mf/attach-logs
                                              mf/describe-projects)))
  (GET "/comparison-info/:build-a/:build-b" [build-a build-b]
       (json-response
         (apply mf/compare-builds
                (mapv #(-> %
                           get-manifest
                           mf/read-manifest
                           (remove-projects "testrunner"))
                      [build-a build-b]))))
  (GET "/" [] {:body (slurp (io/resource "public/index.html"))
               :headers {"content-type" "text/html"}})
  (POST "/my-hooks/my-hooks/my-hooks"
        {{:as params :keys [event]} :params}
        (println "Got event from build stuff" event
                 (pr-str params))
        "OK I GOT YOUR HOOK THX")

  (route/resources "/")
  (route/not-found "404!"))

(def handler
  (-> #'app-routes
      (ch/api)
      (wrap-stacktrace)
      (rt/wrap-queue)
      (wrap-session)))

(defn -main [& args]
  (if (= ["dev"] args)
    (appcfg :dev true)
    (appcfg))
  (start-http-server (wrap-ring-handler #'handler) {:port 3000}))
