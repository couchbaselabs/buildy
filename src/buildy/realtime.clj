(ns buildy.realtime
  (:import [java.util.concurrent ArrayBlockingQueue])
  (:require [taoensso.timbre :as timbre
             :refer [spy trace debug info warn error]]))

(def ^:dynamic *outgoing* nil)

(defn place-important
  "If the session has a queue, put a message on it, blocking if it is not available."
  [msg]
  (when-let [q *outgoing*]
    (.put q msg)))

(defn place-advisory
  "If the session has a queue, put a message on it if it is not full."
  [msg]
  (when-let [q *outgoing*]
    (.offer q msg)))

(defn collect-messages
  "Scrape all the messages out of the session's queue."
  []
  (if-let [q *outgoing*]
    (let [al (java.util.ArrayList.)]
      (.drainTo q al)
      (or (seq (into [] al))
          [(.take q)]))
    (warn "Attempt to collect messages without queue")))

(defn new-queue
  ([] (new-queue 50))
  ([size] (ArrayBlockingQueue. size)))

(defmacro with-queue [q & body]
  `(binding [*outgoing* ~q]
     ~@body))

(defn wrap-queue
  "Ring middleware"
  [handler]
  (fn [{:keys [session] :as rq}]
    (with-queue (:queue session)
      (handler rq))))
