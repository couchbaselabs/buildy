(ns buildy.manifest
  "Working with our repo manifests and git stuff"
  (:import [java.nio.file Path Paths]
           [org.eclipse.jgit.transport RefSpec])
  (:require [clj-jgit.porcelain :as g]
            [clojure.data.xml :as dxml]
            [clojure.java.io :as io]
            [clj-http.client :as http]))

(defonce git-dir (atom (str (System/getenv "HOME") "/buildy-git-cache")))

(defn only-tag [coll tag]
  (filter #(= tag (:tag %)) coll))

(defn read-manifest
  "Parse manifest XML"
  [source]
  (let  [parsed (dxml/parse source)
         remotes (only-tag (:content parsed) :remote)
         projects (only-tag (:content parsed) :project)
         defaults (-> (only-tag (:content parsed) :default)
                      first :attrs)
         ]
    {:remotes (into {}
                    (map (fn [x-remote]
                           (let [{:as atts :keys [name]} (:attrs x-remote)]
                             [name atts])) remotes))
     :projects (into {}
                     (map (fn [x-project]
                            (let [{:as atts :keys [name]} (:attrs x-project)]
                              [name (merge defaults atts)])) projects))
     :defaults defaults}))

(defn setup-remote [repo remote project]
  (let [remote-name (:name remote)
        remote-url (str (:fetch remote) (:name project))]
    (-> repo
        (.getRepository)
        (.getConfig)
        (as-> config
              (do (.setString config "remote" remote-name "url" remote-url) 
                  (.save config))))))

(defn clone-or-update
  [project remote]
  (let [project-name (:name project)
        project-dir (str @git-dir "/" project-name)
        project-dir-f (io/file project-dir)
        remote-name (:name remote)
        remote-url (str (:fetch remote) project-name)]
    (if (.exists project-dir-f)
      (g/with-repo project-dir
        (setup-remote repo remote project)
        (try (-> repo
                 (.fetch)
                 (.setRemote remote-name)
                 ; i have no idea what i'm doing
                 ;(.setRefSpecs [(RefSpec. (str "+refs/head/" project ":/*:refs/remotes/"
                 ;                              remote-name "/*"))])
                 (.setRefSpecs [(RefSpec. "+refs/heads/master")])
                 (.call))
          (catch Exception e
            (str e))))
      ;else
      (do (g/git-clone remote-url project-dir)
          (g/with-repo project-dir (setup-remote repo remote project))))))

(defn clone-projects
  "Fetch projects in manifest and store in git cache"
  [manifest]
  (into {} (map (fn [[k project]]
                  (let [remote ((:remotes manifest) (:remote project))]
                    [k (future (clone-or-update project remote))]))
                (:projects manifest))))

(defn niceify-commit [commit]
  (let [beaned (bean commit)
        author (:authorIdent beaned)
        author-beaned (bean author)]
    (merge (select-keys beaned [:shortMessage :commitTime :name])
           {:author
            (select-keys author-beaned [:emailAddress :name])})))

(defn commits-for-project [project]
  (g/with-repo (str @git-dir "/" (:name project))
    (g/git-log repo (:revision project))))

(defn project-comparison [project-a project-b]
  (let [commits-a (commits-for-project project-a)
        commits-b (commits-for-project project-b)
        only-a (remove (set commits-b) commits-a)
        only-b (remove (set commits-a) commits-b)]
    [only-a only-b]))

(defn compare-builds [manifest-a manifest-b]
  (clone-projects manifest-a)
  (clone-projects manifest-b)
  (let [project-names (keys (:projects manifest-a))]
    (keep identity
          (for [projname project-names]
            (let [project-a (get-in manifest-a [:projects projname])
                  project-b (get-in manifest-b [:projects projname])
                  [only-a only-b] (project-comparison project-a project-b)]
              (when (some (comp pos? count) [only-a only-b])
                {:name projname
                 :onlya (map niceify-commit only-a)
                 :onlyb (map niceify-commit only-b)}))))))

(defn attach-logs [manifest]
  (clone-projects manifest)
  (update-in manifest [:projects]
             (fn [projects]
               (into {} (map (fn [[project-name project]]
                               [project-name (assoc project :log
                                                    (map niceify-commit
                                                         (take 10 (commits-for-project project))))])
                             projects)))))

(comment
  (def tmf
    (with-open [ms (io/input-stream (str "http://bruce.cbfs.hq.couchbase.com:3000/manifest/"
                                         "couchbase-server-community_x86_2.0.2-731-rel.deb"))]
      (read-manifest ms)))

  (let [manifest tmf
        projname "couchdb"
        project (-> manifest :projects (get projname))
        remote ((:remotes manifest) (:remote project))]
    (clone-or-update project remote))

  (def cdbdir (str @git-dir "/couchdb"))

  (g/with-repo cdbdir
    (-> repo
        (.getRepository)
        (.getConfig)
        (as-> config
              (do (.setString config "remote" "test" "url" "http://example.com/test.git")
                  (.save config)))))

  (def c (clone-projects tmf))

  (def testc (g/with-repo cdbdir
    (-> (g/git-log repo (:revision ((:projects tmf) "couchdb")))
        (->>
          first))))

  (pprint (bean testc))
  (do c)
  (-> c
      vals
      (->> (map deref)
           (map #(.getTrackingRefUpdates %))
           ))
  )
