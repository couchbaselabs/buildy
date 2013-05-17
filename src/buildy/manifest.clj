(ns buildy.manifest
  "Working with our repo manifests and git stuff"
  (:import [org.eclipse.jgit.transport RefSpec]
           java.security.MessageDigest)
  (:require [clj-jgit.porcelain :as g]
            [taoensso.timbre :as timbre
             :refer [spy trace debug info warn error]]
            [me.raynes.conch :refer [programs]]
            [clojure.data.xml :as dxml]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [buildy.realtime :as rt]
            [clj-http.client :as http]))

(defonce git-dir (atom (str (System/getenv "HOME") "/buildy-git-cache")))

(programs git)

(defn- md5sum
  [^String s]
  (let [digest ^MessageDigest (MessageDigest/getInstance "MD5")]
    (.reset digest)
    (.toString (BigInteger. 1 (.digest digest (.getBytes s "UTF-8"))) 16)))

(defn- gravhash
  [^String email]
  (-> email .trim .toLowerCase md5sum))

(defn tagged
  "Return children with tag t."
  [element t]
  (some->> (:content element)
           (filter (comp (partial = t)
                         :tag))))

(defn assoc-attr-map
  "assoc element's attribute list into map with it's 'name' attribute as the key,
   optionally prefilling with a default values map."
  ([acc element] (assoc-attr-map {} acc element))
  ([default acc element]
   (let [attrs (:attrs element)
         k (:name attrs)]
     (assoc acc k (merge default attrs)))))

(defn read-manifest
  "Parse manifest XML"
  [source]
  (let  [parsed (dxml/parse source)
         remotes (tagged parsed :remote)
         projects (tagged parsed :project)
         defaults (-> parsed (tagged :default) first :attrs)]
    {:remotes (reduce assoc-attr-map {} remotes)
     :projects (reduce (partial assoc-attr-map defaults) {} projects)
     :defaults defaults}))

(defn setup-remote [repo remote project]
  (let [remote-name (:name remote)
        remote-url (str (:fetch remote) (:name project))]
    (-> repo
        (.getRepository)
        (.getConfig)
        (as-> config
              (do (.setString config "remote" remote-name "url" remote-url)
                  (.setString config "remote" remote-name "fetch"
                              (str"+refs/heads/*:refs/remotes/" remote-name "/*"))
                  (.save config))))))

(defn repo-has-commit? [repo commit]
  (try (-> repo (g/git-log commit) first)
    (catch Exception e false)))

(defn project-up-to-date? [project]
  (let [repo-dir (str @git-dir "/" (:name project))]
    (and (.exists (io/file repo-dir))
         (g/with-repo repo-dir
           (repo-has-commit? repo (:revision project))))))

(defn git-describe
  [project committish]
  (git (str "--git-dir=" @git-dir "/" project "/.git") "describe" committish))

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
        (rt/place-advisory {:kind "git" :message
                            (str project-name " $ git remote update\n"
                                 (git (str "--git-dir=" @git-dir "/" project-name "/.git") "remote" "update"))}))
      ;else
      (do (g/git-clone remote-url project-dir)
          (g/with-repo project-dir (setup-remote repo remote project))))))

(defn clone-projects
  "Fetch projects in manifest and store in git cache, in parallel,
   blocking until all fetches are finished."
  [manifest]
  (doseq [fut (doall (for [project (-> manifest :projects vals)]
                       (future (when-not (project-up-to-date? project)
                                 (clone-or-update
                                   project ((:remotes manifest) (:remote project)))))))]
    (deref fut)))

(defn footer-map [commit]
  (into {} (for [fl (.getFooterLines commit)]
             [(.getKey fl) (.getValue fl)])))

(defn niceify-commit [commit]
  (let [beaned (bean commit)
        author (:authorIdent beaned)
        author-beaned (bean author)
        footkvs (footer-map commit)]
    (merge (select-keys beaned [:shortMessage :commitTime :name])
           (if-let [gerrit-url (get footkvs "Reviewed-on")]
             {:gerriturl gerrit-url})
           {:author
            (merge (select-keys author-beaned [:emailAddress :name])
                   {:gravatar (gravhash (:emailAddress author-beaned))})})))

(defn commits-for-project [project]
  (rt/place-advisory {:kind "git" :message (str "Examining git commits for " (:name project))})
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
  (let [projects-a (set (keys (:projects manifest-a)))
        projects-b (set (keys (:projects manifest-b)))
        projects-to-compare (cset/intersection projects-a projects-b)
        projects-only-a (cset/difference projects-a projects-b)
        projects-only-b (cset/difference projects-b projects-a)]
    {:projectsonlya projects-only-a
     :projectsonlyb projects-only-b
     :compared (keep identity
          (for [projname projects-to-compare]
            (let [project-a (get-in manifest-a [:projects projname])
                  project-b (get-in manifest-b [:projects projname])
                  [only-a only-b] (project-comparison project-a project-b)]
              (when (some (comp pos? count) [only-a only-b])
                {:name projname
                 :onlya (map niceify-commit only-a)
                 :onlyb (map niceify-commit only-b)}))))}))

(defn describe-projects [manifest]
  (rt/place-advisory {:kind "git" :message "describin'"})
  (reduce (fn [manifest project]
            (assoc-in manifest [:projects (:name project) :describe]
                      (git-describe (:name project) (:revision project))))
          manifest (-> manifest :projects vals)))

(defn attach-logs [manifest]
  (clone-projects manifest)
  (reduce (fn [manifest project]
            (assoc-in manifest [:projects (:name project) :log]
                      (->> project
                           commits-for-project
                           (take 10)
                           (map niceify-commit))))
          manifest (-> manifest :projects vals)))
