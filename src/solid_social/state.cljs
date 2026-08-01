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
           :posts []
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

(defn refresh-feed!
  "Reload posts from the user's own pod and every contact's pod,
   merged and sorted newest-first."
  []
  (let [{:keys [webid contacts]} @db
        authors (into [webid] contacts)]
    (swap! db assoc :loading-feed? true :error nil)
    (load-profiles! authors)
    (-> (p/all (map pod/load-posts+ authors))
        (p/then (fn [post-lists]
                  (swap! db assoc
                         :loading-feed? false
                         :posts (->> (apply concat post-lists)
                                     (sort-by #(if-let [d (:published %)]
                                                 (.getTime d)
                                                 0)
                                              >)
                                     vec))))
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
                (swap! db assoc :webid nil :posts [] :contacts [] :profiles {})))))

(defn init!
  "Run once on page load: finish any pending OIDC redirect, restore the
   session if there is one, then load contacts and the feed."
  []
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
