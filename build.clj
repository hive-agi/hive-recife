(ns build
  "Source jar + Clojars deploy. Version = the repo's VERSION file (matching the
   GitHub release tag), read from ./version.edn for coordinates.

   Tasks (clojure -T:build <task>): jar | install | deploy | clean."
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deps-deploy.deps-deploy :as dd]))

(def ^:private cfg (edn/read-string (slurp "version.edn")))
(def lib (:lib cfg))
(def version
  (let [f (io/file "VERSION")]
    (if (.exists f)
      (str/trim (slurp f))
      (format "0.%s.%s" (:minor cfg 0) (b/git-count-revs nil)))))
(def ^:private class-dir "target/classes")
(def ^:private src-dirs (:src-dirs cfg ["src"]))
(def ^:private jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn- write-pom []
  (b/write-pom
   {:class-dir class-dir
    :lib       lib
    :version   version
    :basis     (basis)
    :src-dirs  src-dirs
    :scm       {:url (:scm-url cfg)
                :tag (b/git-process {:git-args "rev-parse HEAD"})}
    :pom-data  [[:licenses
                 [:license
                  [:name (get-in cfg [:license :name] "MIT")]
                  [:url  (get-in cfg [:license :url]
                                 "https://opensource.org/licenses/MIT")]]]]}))

(defn clean [_] (b/delete {:path "target"}))

(defn jar
  "Build the source jar (pom + copied sources) under target/."
  [_]
  (clean nil)
  (write-pom)
  (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Built" (str lib) version "->" jar-file))

(defn install
  "Build + install to the local ~/.m2 repository (offline; for verification)."
  [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Installed" (str lib) version "to ~/.m2"))

(defn deploy
  "Build + deploy to Clojars. Requires CLOJARS_USERNAME / CLOJARS_PASSWORD."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Deployed" (str lib) version "to Clojars"))
