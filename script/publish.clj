(ns publish
  "Publish work that happens locally on a maintainer's workstation"
  (:require [babashka.tasks :as t]
            [build-shared]
            [clojure.string :as string]
            [lread.status-line :as status]
            [version-clj.core :as v]))

;; Note to lurkers: doc updates are geared to AsciiDoc files.

(def changelog-fname "CHANGELOG.adoc")
(def user-guide-fname "doc/01-user-guide.adoc")
;; this project started with "pomegranate-", then moved to "Release-" but we prefer "v" as a version tag prefix
(def legacy-version-tag-prefixes ["pomegranate-" "Release-"])

(defn- raw-tags[]
  (->>  (t/shell {:out :string}
                 ;; specifying the url ensures we don't get tags for some dev fork
                 "git ls-remote --tags --refs" (format "https://github.com/%s.git" (build-shared/lib-github-coords)))
          :out
          string/split-lines))

(defn- parse-raw-tag [raw-tag-line]
  (let [pattern (re-pattern (str "refs/tags/((?:"
                                 (string/join "|" legacy-version-tag-prefixes) "|"
                                 build-shared/version-tag-prefix ")(\\d+\\..*))"))]
    (some->> (re-find pattern raw-tag-line)
             rest
             (zipmap [:tag :version]))))

(defn- most-recent-tag [parsed-tags]
  (->>  parsed-tags
        (sort-by :version v/version-compare)
        reverse
        first
        :tag))

(defn last-release-tag []
  (->>  (raw-tags)
        (keep parse-raw-tag)
        (most-recent-tag)))

(defn- not-fork? []
  (let [origin-url (->> (t/shell {:out :string} "git remote get-url origin")
                        :out
                        string/trim)]
    (some #{origin-url} [(format "https://github.com/%s.git" (build-shared/lib-github-coords))
                         (format "git@github.com:%s.git" (build-shared/lib-github-coords))])))

(defn- master-branch? []
  (let [current-branch (->> (t/shell {:out :string} "git rev-parse --abbrev-ref HEAD")
                            :out
                            string/trim)]
    (= "master" current-branch)))

(defn- uncommitted-code? []
  (-> (t/shell {:out :string}
               "git status --porcelain")
      :out
      string/trim
      seq))

(defn- local-branch? []
  (let [{:keys [exit]} (t/shell {:continue true :out :string :err :out}
                                "git rev-parse --symbolic-full-name @{u}")]
    (not (zero? exit))))

(defn- unpushed-commits? []
  (let [{:keys [exit :out]} (t/shell {:continue true :out :string}
                                     "git cherry -v")]
    (and (zero? exit) (-> out string/trim seq))))

(defn- commit-matches-master? []
  (let [master-head-sha (-> (t/shell {:out :string} (format "git ls-remote https://github.com/%s.git master" (build-shared/lib-github-coords)))
                             :out
                             (string/split #"\s+")
                             first)
        local-head-sha (-> (t/shell {:out :string} "git rev-parse HEAD")
                           :out
                           string/trim)]
    (= master-head-sha local-head-sha)))

(defn- analyze-changelog
  "Certainly not fool proof, but should help for common mistakes"
  []
  (let [content (slurp changelog-fname)
        valid-attrs ["[minor breaking]" "[breaking]"]
        [_ attr content :as match] (re-find #"(?ims)^== Unreleased ?(.*?)$(.*?)(== v\d|\z)" content)]
    (if (not match)
      [{:error :section-missing}]
      (cond-> []
        (and attr
             (not (string/blank? attr))
             (not (contains? (set valid-attrs) attr)))
        (conj {:error :suffix-invalid :valid-attrs valid-attrs :found attr})

        ;; without any words of a reasonable min length, we consider section blank
        (not (re-find #"(?m)[\p{L}]{3,}" content))
        (conj {:error :content-missing})))))

(defn- release-checks []
  (let [changelog-findings (reduce (fn [acc n] (assoc acc (:error n) n))
                                   {}
                                   (analyze-changelog))]
    [{:check "not on fork"
      :result (if (not-fork?) :pass :fail)}
     {:check "on master branch"
      :result (if (master-branch?) :pass :fail)}
     {:check "no uncommitted code"
      :result (if (uncommitted-code?) :fail :pass)}
     {:check "no unpushed commits"
      :result (if (or (local-branch?) (unpushed-commits?)) :fail :pass)}
     {:check "in synch with master HEAD"
      :result (if (commit-matches-master?) :pass :fail)}
     {:check "changelog has unreleased section"
      :result (if (:section-missing changelog-findings) :fail :pass)}
     {:check "changelog unreleased section attributes valid"
      :result (cond
                (:section-missing changelog-findings) :skip
                (:suffix-invalid changelog-findings) :fail
                :else :pass)
      :msg (when-let [{:keys [valid-attrs found]} (:suffix-invalid changelog-findings)]
             (format "expected attributes to absent or one of %s, but found: %s" (string/join ", " valid-attrs) found))}
     {:check "changelog unreleased section has content"
      :result (cond
                (:section-missing changelog-findings) :skip
                (:content-missing changelog-findings) :fail
                :else :pass)}]))

(defn- bump-version!
  "bump version stored in deps.edn"
  []
  (t/shell "bb neil version patch --no-tag"))

(defn- update-file! [fname desc match replacement]
  (let [old-content (slurp fname)
        new-content (string/replace-first old-content match replacement)]
    (if (= old-content new-content)
      (status/die 1 "Expected to %s in %s" desc fname)
      (spit fname new-content))))

(defn- update-user-guide! [version]
  (status/line :detail "Applying version %s to user guide" version)
  (update-file! user-guide-fname
                "update :lib-version: adoc attribute"
                #"(?m)^(:lib-version: )(.*)$"
                (str "$1" version)))

(defn- yyyy-mm-dd-now-utc []
  (-> (java.time.Instant/now) str (subs 0 10)))

(defn- update-changelog! [version release-tag last-release-tag]
  (status/line :detail "Applying version %s to changelog" version)
  (update-file! changelog-fname
                "update unreleased header"
                #"(?ims)^== Unreleased(.*?)($.*?)(== v\d|\z)"
                (str
                  ;; add Unreleased section for next released
                 "== Unreleased\n\n"
                  ;; replace "Unreleased" with actual version
                 "== v" version
                 ;; followed by any attributes
                 "$1"
                  ;; followed by datestamp (local time is fine)
                 " - " (yyyy-mm-dd-now-utc)
                  ;; followed by an AsciiDoc anchor for easy referencing
                 " [[v" version  "]]"
                  ;; followed by section content
                 "$2"
                  ;; followed by link to commit log
                 (when last-release-tag
                   (str
                    "https://github.com/" (build-shared/lib-github-coords) "/compare/"
                    last-release-tag
                    "\\\\..."  ;; single backslash is escape for AsciiDoc
                    release-tag
                    "[commit log]\n\n"))
                  ;; followed by next section indicator
                 "$3")))

(defn- commit-changes! [version]
  (t/shell "git add deps.edn" changelog-fname user-guide-fname)
  (t/shell "git commit -m" (str "publish: apply version " version)))

(defn- tag! [tag version]
  (t/shell "git tag" tag "-m" (str "For release: " version)))

(defn- push! []
  (t/shell "git push"))

(defn- push-tag! [tag]
  (t/shell "git push origin" tag))

;; task entry points

(defn pubcheck []
  (status/line :head "Performing publish checks")
  (let [check-results (release-checks)
        passed? (every? #(= :pass (:result %)) check-results)]
    (doseq [{:keys [check result msg]} check-results]
      (status/line :detail "%s %s"
                   (case result
                     :pass "✓"
                     :fail "x"
                     :skip "~")
                   check)
      (when msg
        (status/line :detail "  > %s" msg)))
    (when (not passed?)
      (status/die 1 "Release checks failed"))))

(defn -main [& _args]
  (pubcheck)
  (status/line :head "Calculating versions")
  (bump-version!)
  (let [last-release-tag (last-release-tag)
        version (build-shared/lib-version)
        release-tag (build-shared/version->tag version)]
    (status/line :detail "Release version: %s" version)
    (status/line :detail "Release tag: %s" release-tag)
    (status/line :detail "Last release tag: %s" last-release-tag)
    (status/line :head "Updating docs")
    (update-user-guide! version)
    (update-changelog! version release-tag last-release-tag)
    (status/line :head "Committing changes")
    (commit-changes! version)
    (status/line :head "Tagging & pushing")
    (tag! release-tag version)
    (push!)
    (push-tag! release-tag)
    (status/line :detail "\nLocal work done.")
    (status/line :head "Remote work")
    (status/line :detail "The remainging work will be triggered by the release tag on CI:")
    (status/line :detail "- Publish a release jar to clojars")
    (status/line :detail "- Create a GitHub release")
    (status/line :detail "- Inform cljdoc of release")))

;; default action when executing file directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment
  (parse-raw-tag "boo refs/tags/pomegranate-1.8")
  ;; => {:tag "pomegranate-1.8", :version "1.8"}

  (parse-raw-tag "boo refs/tags/Release-1.8")
  ;; => {:tag "Release-1.8", :version "1.8"}

  (parse-raw-tag "boo refs/tags/v1.8")
  ;; => {:tag "v1.8", :version "1.8"}

  (parse-raw-tag "boo refs/tags/1.8")
  ;; => nil

  (parse-raw-tag "boo refs/tags/nope")
  ;; => nil

  (most-recent-tag [{:tag "a" :version "0.0.2"}
                    {:tag "b" :version "7.8.9"}
                    {:tag "c" :version "0.0.4"}
                    {:tag "d" :version "1.2.3"}])
  ;; => "b"

  (->>  (raw-tags)
        (keep parse-raw-tag))
  ;; => ({:tag "Release-1.2.0", :version "1.2.0"}
  ;;     {:tag "Release-1.2.1", :version "1.2.1"}
  ;;     {:tag "pomegranate-0.0.10", :version "0.0.10"}
  ;;     {:tag "pomegranate-0.0.11", :version "0.0.11"}
  ;;     {:tag "pomegranate-0.0.12", :version "0.0.12"}
  ;;     {:tag "pomegranate-0.0.13", :version "0.0.13"}
  ;;     {:tag "pomegranate-0.0.2", :version "0.0.2"}
  ;;     {:tag "pomegranate-0.0.3", :version "0.0.3"}
  ;;     {:tag "pomegranate-0.0.4", :version "0.0.4"}
  ;;     {:tag "pomegranate-0.0.5", :version "0.0.5"}
  ;;     {:tag "pomegranate-0.0.6", :version "0.0.6"}
  ;;     {:tag "pomegranate-0.0.7", :version "0.0.7"}
  ;;     {:tag "pomegranate-0.0.8", :version "0.0.8"}
  ;;     {:tag "pomegranate-0.0.9", :version "0.0.9"}
  ;;     {:tag "pomegranate-0.2.0", :version "0.2.0"}
  ;;     {:tag "pomegranate-0.3.0", :version "0.3.0"}
  ;;     {:tag "pomegranate-0.3.1", :version "0.3.1"}
  ;;     {:tag "pomegranate-0.4.0", :version "0.4.0"}
  ;;     {:tag "pomegranate-0.4.0-alpha1", :version "0.4.0-alpha1"}
  ;;     {:tag "pomegranate-1.0.0", :version "1.0.0"}
  ;;     {:tag "pomegranate-1.1.0", :version "1.1.0"})

  (last-release-tag)
  ;; => "Release-1.2.1"
)
