(ns pod-browser.state
  "One Reagent atom holds the whole browser; the functions below are the
   only things that change it."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [pod-browser.pod :as pod]
            [reagent.core :as r]
            [solid-shared.auth :as auth]))

(defonce db
  (r/atom {:checking-session? true
           :webid nil
           :roots []            ; storage roots from the profile
           :path nil            ; container currently listed
           :entries []
           :loading? false
           :open nil            ; the resource being viewed, if any
           :opening nil         ; its url while the fetch is in flight
           :menu nil            ; {:x :y :entry} for the context menu
           :confirm nil         ; entry awaiting delete confirmation
           :error nil}))

(defn- describe
  "A fetch that never reaches the server rejects with a bare
   TypeError: 'Failed to fetch', which says nothing about why. Keep the
   error's type alongside its message, and put the whole object in the
   console where its stack and cause are inspectable."
  [context ^js err]
  (js/console.error context err)
  (str context ": "
       (when-let [n (some-> err .-name)] (str n " — "))
       (or (some-> err .-message) (str err))))

(defn- set-error! [msg]
  (swap! db assoc :error msg :loading? false))

(defn dismiss-error! []
  (swap! db assoc :error nil))

;; ---------------------------------------------------------------------------
;; Viewing a resource

(defn close-file! []
  (when-let [object-url (:object-url (:open @db))]
    (pod/release-object-url! object-url))
  (swap! db assoc :open nil :opening nil))

(defn open-file! [{:keys [url] :as entry}]
  (close-file!)
  (swap! db assoc :opening url :menu nil)
  (-> (pod/read-resource+ url)
      (p/then (fn [resource]
                ;; ignore a read the user has already navigated away from
                (if (= url (:opening @db))
                  (swap! db assoc :open (merge entry resource) :opening nil)
                  (when-let [o (:object-url resource)]
                    (pod/release-object-url! o)))))
      (p/catch #(do (swap! db assoc :opening nil)
                    (set-error! (describe (str "Couldn't read " url) %))))))

;; ---------------------------------------------------------------------------
;; Navigating

(defn open-container! [url]
  (close-file!)
  (swap! db assoc :path url :loading? true :error nil :menu nil :entries [])
  (-> (pod/list-container+ url)
      (p/then (fn [entries]
                ;; a slower listing for a container we've since left
                ;; must not replace the one on screen
                (when (= url (:path @db))
                  (swap! db assoc :entries entries :loading? false))))
      (p/catch (fn [e]
                 (when (= url (:path @db))
                   (set-error! (describe (str "Couldn't list " url) e)))))))

(defn refresh! []
  (when-let [path (:path @db)]
    (open-container! path)))

(defn open-entry! [{:keys [container?] :as entry}]
  (if container?
    (open-container! (:url entry))
    (open-file! entry)))

(defn crumbs
  "Breadcrumbs from the storage root down to the current container, as
   [{:label :url}]. Anything above the root is left alone — a pod is the
   top of the world as far as this app is concerned."
  []
  (let [{:keys [path roots]} @db
        root (or (first (filter #(str/starts-with? (or path "") %) roots))
                 (first roots))]
    (when (and path root)
      (let [rest-path (subs path (count root))
            segments (remove str/blank? (str/split rest-path #"/"))]
        (into [{:label (pod/entry-name root) :url root}]
              (map-indexed
               (fn [i segment]
                 {:label (js/decodeURIComponent segment)
                  :url (str root (str/join "/" (take (inc i) segments)) "/")})
               segments))))))

;; ---------------------------------------------------------------------------
;; Context menu and deletion

(defn show-menu! [entry x y]
  (swap! db assoc :menu {:entry entry :x x :y y}))

(defn hide-menu! []
  (swap! db assoc :menu nil))

(defn ask-delete! [entry]
  (swap! db assoc :confirm entry :menu nil))

(defn cancel-delete! []
  (swap! db assoc :confirm nil))

(defn confirm-delete! []
  (when-let [entry (:confirm @db)]
    (swap! db assoc :confirm nil :loading? true)
    (-> (pod/delete+ entry)
        (p/then (fn [_]
                  (when (= (:url entry) (:url (:open @db)))
                    (close-file!))
                  ;; drop it from the listing straight away rather than
                  ;; leaving it on screen for a slow round trip; the
                  ;; refresh behind it is what confirms
                  (swap! db update :entries
                         (fn [es] (vec (remove #(= (:url %) (:url entry)) es))))
                  (refresh!)))
        (p/catch #(set-error! (describe (str "Couldn't delete " (:name entry)) %))))))

;; ---------------------------------------------------------------------------
;; Session

(defn login! [issuer]
  (swap! db assoc :error nil)
  (-> (auth/login! issuer)
      (p/catch #(set-error! (str "Login failed: " (.-message %))))))

(defn logout! []
  (-> (auth/logout!)
      (p/then (fn [_]
                (close-file!)
                (swap! db assoc :webid nil :roots [] :path nil :entries [])))))

(defn init! []
  (auth/recover-from-url!)
  (-> (auth/handle-redirect!)
      (p/then (fn [_]
                (swap! db assoc :checking-session? false)
                (when (auth/logged-in?)
                  (let [webid (auth/web-id)]
                    (swap! db assoc :webid webid :loading? true)
                    (p/then (pod/storage-roots+ webid)
                            (fn [roots]
                              (swap! db assoc :roots roots)
                              (open-container! (first roots))))))))
      (p/catch (fn [e]
                 (swap! db assoc :checking-session? false)
                 (set-error! (str "Session error: " (.-message e)))))))
