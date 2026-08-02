(ns solid-social.state
  "A single Reagent atom holds all app state; the functions below are
   the only things that change it. No re-frame — for an app this size a
   plain atom keeps the moving parts visible."
  (:require [promesa.core :as p]
            [reagent.core :as r]
            [solid-social.auth :as auth]
            [solid-social.pod :as pod]))

(defonce db
  (r/atom {:checking-session? true
           :webid nil
           :contacts []
           :posts []             ; every author's posts, newest first
           :posts-by-author {}   ; webid -> that author's posts
           :profiles {}          ; webid -> {:name :avatar}
           :loading-feed? false
           :posting? false
           :error nil}))

(defn- set-error! [msg]
  (swap! db assoc :error msg :posting? false :loading-feed? false))

(defn- load-profiles! [webids]
  (doseq [webid webids
          :when (not (contains? (:profiles @db) webid))]
    (p/then (pod/load-profile+ webid)
            #(swap! db assoc-in [:profiles webid] %))))

(defn- published-at [post]
  (if-let [d (:published post)] (.getTime d) 0))

(defn- rebuild-feed! [by-author]
  (swap! db assoc
         :posts-by-author by-author
         :posts (vec (sort-by published-at > (mapcat val by-author)))))

(defn- merge-author-posts!
  "Replace one author's posts and rebuild the merged feed. Each pod
   answers at its own pace, so results are shown as they arrive rather
   than making the whole feed wait on the slowest contact."
  [author posts]
  (rebuild-feed! (assoc (:posts-by-author @db) author posts)))

;; A refresh that is still in flight when another starts must not write
;; its results over the newer one.
(defonce ^:private feed-run (atom 0))

(defn refresh-feed!
  "Reload posts from the user's own pod and every contact's pod,
   merged and sorted newest-first."
  []
  (let [{:keys [webid contacts]} @db
        authors (into [webid] contacts)
        run (swap! feed-run inc)
        current? #(= run @feed-run)]
    (swap! db assoc :loading-feed? true :error nil)
    ;; drop anyone no longer followed before fetching
    (rebuild-feed! (select-keys (:posts-by-author @db) authors))
    (load-profiles! authors)
    (-> (p/all (mapv (fn [author]
                       (p/then (pod/load-posts+ author)
                               #(when (current?) (merge-author-posts! author %))))
                     authors))
        (p/then #(when (current?) (swap! db assoc :loading-feed? false)))
        (p/catch #(set-error! (str "Couldn't load feed: " (.-message %)))))))

(defn submit-post!
  "Publish a post (text plus optional media files) to the user's own
   pod, then refresh the feed. Calls on-done once the post is saved."
  [content files on-done]
  (swap! db assoc :posting? true :error nil)
  (-> (pod/save-post+ (:webid @db) content files)
      (p/then (fn [_]
                (swap! db assoc :posting? false)
                (on-done)
                (refresh-feed!)))
      (p/catch #(set-error! (str "Couldn't publish post: " (.-message %))))))

(defn- save-contacts! [contacts]
  (swap! db assoc :contacts contacts)
  (-> (pod/save-contacts+ (:webid @db) contacts)
      (p/then (fn [_] (refresh-feed!)))
      (p/catch #(set-error! (str "Couldn't save contacts: " (.-message %))))))

(defn add-contact! [webid]
  (let [webid (.trim webid)
        contacts (:contacts @db)]
    (when (and (seq webid) (not (some #{webid} contacts)))
      (save-contacts! (conj contacts webid)))))

(defn remove-contact! [webid]
  (save-contacts! (vec (remove #{webid} (:contacts @db)))))

(defn login! [issuer]
  (swap! db assoc :error nil)
  (-> (auth/login! issuer)
      (p/catch #(set-error! (str "Login failed: " (.-message %))))))

(defn logout! []
  (-> (auth/logout!)
      (p/then (fn [_]
                (pod/forget-caches!)
                (swap! db assoc
                       :webid nil :contacts [] :profiles {}
                       :posts [] :posts-by-author {})))))

(defn init!
  "Run once on page load: finish any pending OIDC redirect, restore the
   session if there is one, then load contacts and the feed."
  []
  (auth/recover-from-url!)
  (-> (auth/handle-redirect!)
      (p/then (fn [_]
                (swap! db assoc :checking-session? false)
                (when (auth/logged-in?)
                  (let [webid (auth/web-id)]
                    (swap! db assoc :webid webid)
                    (p/then (pod/load-contacts+ webid)
                            (fn [contacts]
                              (swap! db assoc :contacts contacts)
                              (refresh-feed!)))))))
      (p/catch (fn [e]
                 (swap! db assoc :checking-session? false)
                 (set-error! (str "Session error: " (.-message e)))))))
