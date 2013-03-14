(ns buildy.design-docs
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [cbdrawer.client :as cb]
            [cbdrawer.view :as cb-view]))

(def ^:ensure-installed
  buildboard
  (json/generate-string
    {:views {:builds {:map (slurp (io/resource "views/builds-map.js"))}}}))

; Find all vars in this ns marked :ensure-installed
(def ^:private to-install
  (->> (ns-publics *ns*)
       (filter (comp :ensure-installed meta second))
       (map second)
       (map (juxt (comp name :name meta) deref))))

(defn install-all [factory]
  (let [capis (cb/capi-bases factory)]
    (doseq [[id source] to-install]
      (cb-view/install-ddoc capis id source))))
