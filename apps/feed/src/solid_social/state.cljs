(ns solid-social.state
  "A single Reagent atom holds all app state; the functions below are
   the only things that change it. No re-frame — for an app this size a
   plain atom keeps the moving parts visible."
  (:require [promesa.core :as p]
            [reagent.core :as r]
            [solid-shared.auth :as auth]
            [solid-social.pod :as pod]))

(defonce db
  (r/atom {:checking-session? true
           :webid nil
           :contacts []
           :posts []             ; every author's posts, newest first
           :posts-by-author {}   ; webid -> that author's posts
           :profiles {}          ; webid -> {:name :avatar}
           :unreadable {}        ; webid -> why their pod couldn't be read
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

(defn- fetch-authors!
  "Load each author's posts, merging every one into the feed as it
   arrives. Results from a superseded refresh are dropped.

   A pod that can't be read is recorded against that author rather than
   breaking the whole feed — but it is recorded, not silently treated as
   an empty pod, so a transient failure can't masquerade as 'no posts'."
  [authors]
  (let [run @feed-run
        current? #(= run @feed-run)]
    (load-profiles! authors)
    (p/all (mapv (fn [author]
                   (-> (pod/load-posts+ author)
                       (p/then (fn [posts]
                                 (when (current?)
                                   (swap! db update :unreadable dissoc author)
                                   (merge-author-posts! author posts))))
                       (p/catch (fn [e]
                                  (js/console.error
                                   "Couldn't load posts from" author e)
                                  (when (current?)
                                    (swap! db assoc-in [:unreadable author]
                                           (or (some-> ^js e .-message) (str e))))))))
                 authors))))

(defn refresh-feed!
  "Reload posts from the user's own pod and every contact's pod,
   merged and sorted newest-first."
  []
  (let [{:keys [webid contacts]} @db
        authors (into [webid] contacts)]
    (swap! feed-run inc)
    (swap! db assoc :loading-feed? true :error nil)
    ;; drop anyone no longer followed before fetching
    (rebuild-feed! (select-keys (:posts-by-author @db) authors))
    (-> (fetch-authors! authors)
        (p/then #(swap! db assoc :loading-feed? false))
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
                       :posts [] :posts-by-author {} :unreadable {})))))

(defn init!
  "Run once on page load: finish any pending OIDC redirect, restore the
   session if there is one, then load contacts and the feed."
  []
  (auth/recover-from-url!)
  (-> (auth/handle-redirect!)
      (p/then
       (fn [_]
         (swap! db assoc :checking-session? false)
         (when (auth/logged-in?)
           (let [webid (auth/web-id)]
             (swap! db assoc :webid webid :loading-feed? true :error nil)
             (swap! feed-run inc)
             ;; Your own posts and your contact list are independent, and
             ;; on a slow pod every round trip is seconds — so start both
             ;; at once rather than making the feed wait on the contacts
             ;; fetch before it asks for anything.
             (-> (p/all [(fetch-authors! [webid])
                         (p/let [contacts (pod/load-contacts+ webid)]
                           (swap! db assoc :contacts contacts)
                           (fetch-authors! contacts))])
                 (p/then (fn [_] (swap! db assoc :loading-feed? false)))
                 (p/catch #(set-error! (str "Couldn't load feed: "
                                            (.-message %)))))))))
      (p/catch (fn [e]
                 (swap! db assoc :checking-session? false)
                 (set-error! (str "Session error: " (.-message e)))))))
