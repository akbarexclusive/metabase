(ns release
  (:require [clojure.string :as str]
            [environ.core :as env]
            [flatland.ordered.map :as ordered-map]
            [metabuild-common.core :as u]
            [release
             [check-prereqs :as check-prereqs]
             [common :as c]
             [draft-release :as draft-release]
             [elastic-beanstalk :as eb]
             [git-tags :as git-tags]
             [set-build-options :as set-build-options]
             [uberjar :as uberjar]
             [version-info :as version-info]]
            [release.common.slack :as slack]))

(set! *warn-on-reflection* true)

(def ^:private steps*
  (ordered-map/ordered-map
   :build-uberjar                       uberjar/build-uberjar!
   :upload-uberjar                      uberjar/upload-uberjar!
   :push-git-tags                       git-tags/push-tags!
   :publish-draft-release               draft-release/create-draft-release!
   :publish-elastic-beanstalk-artifacts eb/publish-elastic-beanstalk-artifacts!
   :update-version-info                 version-info/update-version-info!))

(defn- do-steps! [steps]
  (slack/post-message! "%s started building %s `v%s` from branch `%s`..."
                       (env/env :user)
                       (str/upper-case (name (c/edition)))
                       (c/version)
                       (c/branch))
  (doseq [step-name steps]
    (slack/post-message! "Starting step `%s` :flushed:" step-name)
    (let [thunk (or (get steps* step-name)
                    (throw (ex-info (format "Invalid step name: %s" step-name)
                                    {:found (set (keys steps*))})))]
      (thunk))
    (slack/post-message! "Finished `%s` :partyparrot:" step-name))
  (u/announce "Success."))

(defn -main [& steps]
  (u/exit-when-finished-nonzero-on-exception
    (check-prereqs/check-prereqs)
    (set-build-options/prompt-and-set-build-options!)
    (let [steps (or (seq (map u/parse-as-keyword steps))
                    (keys steps*))]
      (do-steps! steps))))
