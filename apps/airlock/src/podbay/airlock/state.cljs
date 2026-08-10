(ns podbay.airlock.state
  "One Reagent atom holds the whole browser; the functions below are the
   only things that change it."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [podbay.airlock.pod :as pod]
            [reagent.core :as r]
            [podbay.shared.auth :as auth]))

(defonce db
  (r/atom {:checking-session? true
           :webid nil
           :roots []            ; storage roots from the profile
           :path nil            ; container currently listed
           :entries []
           :loading? false
           :open nil            ; the resource being viewed, if any
           :access nil          ; who that resource is shared with
           :editing nil         ; {:url :draft} while a file is being edited
           :saving? false
           :show-attached? false ; reveal each entry's .acl / .meta
           :attached {}          ; entry url -> {rel url}
           :access-busy? false
           :propagation nil     ; did a container grant reach its contents?
           :confirm-public nil  ; a public grant awaiting confirmation
           :opening nil         ; its url while the fetch is in flight
           :menu nil            ; {:x :y :entry} for the context menu
           :confirm nil         ; entry awaiting delete confirmation
           :moving nil          ; {:entry :target} while renaming/moving
           :move-busy? false
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
  (swap! db assoc :open nil :opening nil :access nil :propagation nil
                  :editing nil))

(defn- load-access!
  "Fetch who a resource is shared with, alongside its contents. Kept in
   its own key rather than merged into :open because it resolves
   separately, and because being refused is a normal outcome worth
   showing as such."
  [url]
  (swap! db assoc :access {:url url :status :loading})
  (let [current? #(= url (:url (:access @db)))]
    (-> (pod/access+ url)
        (p/then (fn [access]
                  (when (current?)
                    (swap! db assoc :access
                           (merge {:url url :status :ready} access)))))
        (p/catch (fn [e]
                   (js/console.warn "Couldn't read access for" url e)
                   (when (current?)
                     (swap! db assoc :access
                            {:url url
                             :status :denied
                             :message (or (some-> ^js e .-message) (str e))})))))))

(defn open-file! [{:keys [url] :as entry}]
  (close-file!)
  (swap! db assoc :opening url :menu nil)
  (load-access! url)
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
;; Editing

(defn- reload-listing!
  "Re-read the current container without disturbing the open file — a
   save changes its size and modified time, so the row is stale."
  []
  (when-let [path (:path @db)]
    (-> (pod/list-container+ path)
        (p/then (fn [entries]
                  (when (= path (:path @db))
                    (swap! db assoc :entries entries))))
        (p/catch (fn [_] nil)))))

(defn editable?
  "Only text we successfully read, and never a container — a container's
   representation is generated from what it holds, not stored."
  [{:keys [ok? text container?]}]
  (boolean (and ok? (some? text) (not container?))))

(defn start-edit! []
  (when-let [open (:open @db)]
    (when (editable? open)
      (swap! db assoc :editing {:url (:url open) :draft (:text open)}))))

(defn update-draft! [text]
  (swap! db assoc-in [:editing :draft] text))

(defn cancel-edit! []
  (swap! db assoc :editing nil))

(defn save-edit! []
  (let [{:keys [url draft]} (:editing @db)
        open (:open @db)]
    (when (and url (= url (:url open)))
      (swap! db assoc :saving? true :error nil)
      (-> (pod/save-text+ open draft)
          (p/then (fn [_]
                    (swap! db assoc
                           :saving? false
                           :editing nil
                           ;; keep what was saved on screen rather than
                           ;; re-reading: the server has it now, and a
                           ;; re-read costs a round trip on a slow pod
                           :open (assoc open :text draft))
                    ;; size and modified changed, so the listing is stale
                    (reload-listing!)))
          (p/catch (fn [e]
                     (swap! db assoc :saving? false)
                     ;; the draft is deliberately left in place so a
                     ;; failed save never loses what was typed
                     (set-error! (describe (str "Couldn't save " url) e))))))))

;; ---------------------------------------------------------------------------
;; Navigating

(declare load-attached!)

(defn open-container! [url]
  (close-file!)
  (swap! db assoc :path url :loading? true :error nil :menu nil
                  :entries [] :attached {})
  (-> (pod/list-container+ url)
      (p/then (fn [entries]
                ;; a slower listing for a container we've since left
                ;; must not replace the one on screen
                (when (= url (:path @db))
                  (swap! db assoc :entries entries :loading? false)
                  ;; a new folder needs its own attachments looked up
                  (when (:show-attached? @db) (load-attached!)))))
      (p/catch (fn [e]
                 (when (= url (:path @db))
                   (set-error!
                    (str (describe (str "Couldn't list " url) e)
                         ;; access is per resource, not a path you walk
                         ;; down, so being refused a container says
                         ;; nothing about what's inside it
                         (when (#{401 403} (some-> ^js e .-statusCode))
                           " — access is granted per resource, so a folder
                             shared with you opens directly by its URL even
                             when the folder above it doesn't."))))))))

(defn load-attached!
  "One HEAD per entry, because a container lists its children but not
   what's attached to them. Rows fill in as they arrive rather than
   waiting for the slowest."
  []
  (doseq [{:keys [url]} (:entries @db)
          :when (not (contains? (:attached @db) url))]
    (p/then (pod/attached+ url)
            #(swap! db assoc-in [:attached url] (or % {})))))

(defn toggle-attached! []
  (let [on? (not (:show-attached? @db))]
    (swap! db assoc :show-attached? on?)
    (when on? (load-attached!))))

(defn refresh! []
  (when-let [path (:path @db)]
    (open-container! path)))

(defn open-entry! [{:keys [container?] :as entry}]
  (if container?
    (open-container! (:url entry))
    (open-file! entry)))

(defn show-details!
  "Open a resource in the viewer *without* navigating into it. The only
   way to see a container's own metadata and sharing, since clicking a
   folder means 'go inside' — and a container's representation is its
   listing, which reads perfectly well as Turtle."
  [entry]
  (open-file! entry))

(defn show-current-details! []
  (when-let [path (:path @db)]
    (show-details! {:url path :name (pod/entry-name path) :container? true})))

(defn open-url!
  "Jump to any pod URL. A trailing slash means a container, so browse
   it; anything else is a resource, so open it. This is how you reach a
   pod that isn't the one you signed in with — access is still decided
   by the server, but the app shouldn't be the thing stopping you."
  [url]
  (let [url (str/trim url)]
    (when (seq url)
      (if (str/ends-with? url "/")
        (open-container! url)
        (open-file! {:url url :name (pod/entry-name url) :container? false})))))

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
;; Changing who can see things
;;
;; WAC (Community Solid Server) inherits: a grant on a container covers
;; what's inside it unless a child overrides. ACP (Inrupt ESS) composes
;; policies instead, and a container's own access is not necessarily its
;; members'. universalAccess unifies the API, not the semantics — so
;; after every change to a container we check a resource inside it and
;; report what actually happened rather than assuming it worked.

(defn- check-propagation!
  "Did a grant on this container reach the things inside it?"
  [container-url webid]
  (let [child (->> (:entries @db)
                   (remove :container?)
                   first)]
    (when (and child (= container-url (:path @db)))
      (-> (pod/agent-access+ (:url child) webid)
          (p/then (fn [child-access]
                    (swap! db assoc :propagation
                           {:child (:name child)
                            :reached? (boolean (:read child-access))})))
          (p/catch (fn [_] (swap! db assoc :propagation nil)))))))

(defn- after-access-change! [url webid]
  (swap! db assoc :access-busy? false)
  (load-access! url)
  (when (and webid (str/ends-with? url "/"))
    (check-propagation! url webid)))

(defn grant-agent! [webid access]
  (when-let [url (:url (:access @db))]
    (swap! db assoc :access-busy? true :propagation nil)
    (-> (pod/set-agent-access+ url webid access)
        (p/then (fn [_] (after-access-change! url webid)))
        (p/catch (fn [e]
                   (swap! db assoc :access-busy? false)
                   (set-error! (describe (str "Couldn't change access for " webid) e)))))))

(defn revoke-agent! [webid]
  (grant-agent! webid {:read false :append false :write false
                       :control-read false :control-write false}))

(defn set-public! [access]
  (when-let [url (:url (:access @db))]
    (swap! db assoc :access-busy? true :propagation nil :confirm-public nil)
    (-> (pod/set-public-access+ url access)
        (p/then (fn [_] (after-access-change! url nil)))
        (p/catch (fn [e]
                   (swap! db assoc :access-busy? false)
                   (set-error! (describe "Couldn't change public access" e)))))))

(defn ask-public! [access]
  (swap! db assoc :confirm-public access))

(defn cancel-public! []
  (swap! db assoc :confirm-public nil))

;; ---------------------------------------------------------------------------
;; Context menu and deletion

(defn show-menu! [entry x y]
  (swap! db assoc :menu {:entry entry :x x :y y}))

(defn hide-menu! []
  (swap! db assoc :menu nil))

(defn ask-move! [entry]
  (swap! db assoc :moving {:entry entry :target (:url entry)} :menu nil))

(defn update-move-target! [target]
  (swap! db assoc-in [:moving :target] target))

(defn cancel-move! []
  (swap! db assoc :moving nil))

(defn- normalise-target
  "A container's URL must end in a slash — servers treat the two forms as
   different resources, and creating one without would make a file."
  [target container?]
  (let [t (str/trim target)]
    (if (and container? (not (str/ends-with? t "/")))
      (str t "/")
      t)))

(defn confirm-move! []
  (when-let [{:keys [entry target]} (:moving @db)]
    (let [target (normalise-target target (:container? entry))]
      (if (or (str/blank? target) (= target (:url entry)))
        (cancel-move!)
        (do
          (swap! db assoc :move-busy? true :error nil)
          (-> (pod/move+ entry target)
              (p/then (fn [_]
                        (swap! db assoc :move-busy? false :moving nil)
                        (when (= (:url entry) (:url (:open @db)))
                          (close-file!))
                        (refresh!)))
              (p/catch (fn [e]
                         (swap! db assoc :move-busy? false :moving nil)
                         ;; a copy-then-delete that failed part way leaves
                         ;; items in both places — say so, and reload so
                         ;; what's on screen matches the pod
                         (refresh!)
                         (set-error!
                          (str (describe (str "Couldn't move " (:name entry)) e)
                               " — nothing was deleted before being copied, so"
                               " anything already moved now exists in both places."))))))))))

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
