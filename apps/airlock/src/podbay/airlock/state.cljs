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
           :member-access nil   ; what a container grants its contents (ACP)
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
           :delete-contents? false ; ...and everything inside it
           :creating nil        ; {:kind :file|:container :name ""}
           :uploading nil       ; how many files are in flight
           :revoking nil        ; a WebID awaiting revoke confirmation
           :moving nil          ; {:entry :target} while renaming/moving
           :move-busy? false
           :error nil}))

(defn- blocked?
  "Did this fail before any answer we're allowed to read?

   A fetch the browser refuses to expose rejects with a bare TypeError
   and no status — there is deliberately no way to tell *why* from
   script, since that would leak what the response said. A dropped
   connection looks identical. So this identifies the shape of the
   failure, not its cause."
  [^js err]
  (and (nil? (some-> err .-statusCode))
       (nil? (some-> err .-status))
       (= "TypeError" (some-> err .-name))))

(defn- describe
  "A fetch that never reaches the server rejects with a bare
   TypeError: 'Failed to fetch', which says nothing about why. Keep the
   error's type alongside its message, and put the whole object in the
   console where its stack and cause are inspectable.

   For that case the message says what can be established and offers the
   test that settles it, rather than naming a cause. Opening the URL in a
   tab is the discriminator: loading there but not here means the
   response carried no CORS headers — something other than the pod
   answered, a CDN intercepting the path being the usual reason — while
   failing in both means the resource or the network is the problem."
  [context ^js err]
  (js/console.error context err)
  (if (blocked? err)
    (str context ": the browser wouldn't let this app read the response, so "
         "there is nothing to show. Either the answer carried no CORS "
         "headers — which happens when something other than the pod "
         "answers, such as a CDN serving that path itself — or the "
         "request never completed. Opening the URL in a new tab tells "
         "you which: if it loads there, it's the former.")
    (str context ": "
         (when-let [n (some-> err .-name)] (str n " — "))
         (or (some-> err .-message) (str err)))))

(defn- set-error! [msg]
  (swap! db assoc :error msg :loading? false))

(defn dismiss-error! []
  (swap! db assoc :error nil))

;; ---------------------------------------------------------------------------
;; Viewing a resource

(defn close-file! []
  (when-let [object-url (:object-url (:open @db))]
    (pod/release-object-url! object-url))
  (swap! db assoc :open nil :opening nil :access nil :member-access nil
                  :propagation nil :editing nil))

(defn container-label
  "A container as a short path relative to the pod, e.g.
   social/posts/default-private/. The storage root's own name is
   uninformative on ESS, where it's a UUID, so that renders as the pod."
  [url]
  (let [{:keys [roots]} @db
        root (first (filter #(str/starts-with? (or url "") %) roots))]
    (if (and root (not= url root))
      (subs url (count root))
      "the pod itself")))

(defn- storage-root-of
  "Which of the pod's storage roots contains `url`. The walk up the
   container chain stops there — above a storage root is someone else's
   business, and on ESS isn't part of this pod at all."
  [url]
  (let [{:keys [roots]} @db]
    (or (first (filter #(str/starts-with? (or url "") %) roots))
        (first roots))))

(defn- load-access!
  "Fetch who a resource is shared with, alongside its contents. Kept in
   its own key rather than merged into :open because it resolves
   separately, and because being refused is a normal outcome worth
   showing as such."
  [url]
  (swap! db assoc :access {:url url :status :loading} :member-access nil)
  (let [current? #(= url (:url (:access @db)))]
    ;; Access inherited from a container lives only on that container
    ;; and is invisible to the access API, so it's read separately.
    ;; Best-effort: failing to read it must not take the pane with it.
    (-> (pod/access-context+ url (storage-root-of url))
        (p/then (fn [m] (when (and m (current?))
                          (swap! db assoc :member-access m))))
        (p/catch (fn [e] (js/console.warn "Couldn't read inherited access" e))))
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
          (p/then (fn [{:keys [etag]}]
                    (swap! db assoc
                           :saving? false
                           :editing nil
                           ;; keep what was saved on screen rather than
                           ;; re-reading — the server has it now — but
                           ;; take the new validator, or the next save
                           ;; would look like a conflict with itself
                           :open (assoc open :text draft :etag etag))
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
                  ;; a new container needs its own attachments looked up
                  (when (:show-attached? @db) (load-attached!)))))
      (p/catch (fn [e]
                 (when (= url (:path @db))
                   (set-error!
                    (str (describe (str "Couldn't list " url) e)
                         ;; access is per resource, not a path you walk
                         ;; down, so being refused a container says
                         ;; nothing about what's inside it
                         (when (#{401 403} (some-> ^js e .-statusCode))
                           " — access is granted per resource, so a container
                             shared with you opens directly by its URL even
                             when the container above it doesn't."))))))))

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
   container means 'go inside' — and a container's representation is its
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
  "Did a grant on this container reach the things inside it?

   Only answerable on WAC. solid-client's ACP implementation of
   getAgentAccess evaluates `getPolicyUrlAll` and `getAcrPolicyUrlAll`
   — the policies attached directly to the resource — and never
   `getMemberPolicyUrlAll`, nor any ancestor's access control resource,
   which it is candid about (`TODO: add support for external
   resources`). An inherited member policy is precisely what it cannot
   see, so on ACP it isn't run at all — an answer that can't be trusted
   either way is worth less than the request it costs.

   On WAC it earns its place: a child carrying its own .acl genuinely
   does not inherit the container's, and this is what notices."
  [container-url webid]
  (let [child (->> (:entries @db)
                   (remove :container?)
                   first)]
    (when (and child
               (= container-url (:path @db))
               (not= :acp (:mechanism (:inherited @db))))
      (-> (pod/agent-access+ (:url child) webid)
          (p/then (fn [child-access]
                    (swap! db assoc :propagation
                           {:child (:name child)
                            :reached? (boolean (:read child-access))})))
          (p/catch (fn [_] (swap! db assoc :propagation nil)))))))

(defn- after-access-change!
  "`contents?` says whether the change was meant to reach the
   container's contents. When it wasn't, there is nothing to verify —
   reporting that the grant didn't reach them would be scolding the
   user for what they asked for."
  [url webid contents?]
  (swap! db assoc :access-busy? false)
  (load-access! url)
  (when (and webid contents? (str/ends-with? url "/"))
    (check-propagation! url webid)))

(defn- container-url? [url] (str/ends-with? url "/"))

(defn grant-agent!
  "Change one person's access to the thing currently open.

   On a container this is two writes, not one: the container's own
   access, and the access its contents inherit. Both are needed and
   neither implies the other — without the first the container can't be
   opened, and without the second every file in it is refused. They're
   sequential because the second reads the access control resource the
   first has just rewritten.

   `:contents?` (default true) covers the second write. Off, the grant
   stops at the container, which is what an inbox wants: append to the
   container so people can drop files in, without append on what's
   already there. Revoking always passes true — leaving inherited
   access behind after taking it away is the direction that hurts."
  ([webid access] (grant-agent! webid access {:contents? true}))
  ([webid access {:keys [contents?] :or {contents? true}}]
   (when-let [url (:url (:access @db))]
     (swap! db assoc :access-busy? true :propagation nil :inherited nil)
     (-> (pod/set-agent-access+ url webid access)
         (p/then (fn [_]
                   (when (and contents? (container-url? url))
                     ;; reported separately from the grant itself: it is
                     ;; a distinct write against a distinct API, and when
                     ;; sharing doesn't work this is the half that decides
                     ;; whether the cause is "we didn't write it" or "the
                     ;; server didn't honour it"
                     (-> (pod/set-inherited-access+ url webid access)
                         (p/then #(swap! db assoc :inherited (assoc % :ok? true)))
                         (p/catch (fn [e]
                                    (js/console.warn "Inherited access failed" e)
                                    (swap! db assoc :inherited
                                           {:ok? false :message (.-message e)})
                                    nil))))))
         (p/then (fn [_] (after-access-change! url webid contents?)))
         (p/catch (fn [e]
                    (swap! db assoc :access-busy? false)
                    (set-error! (describe (str "Couldn't change access for " webid) e))))))))

(defn ask-revoke! [webid]
  (swap! db assoc :revoking webid))

(defn cancel-revoke! []
  (swap! db assoc :revoking nil))

(defn revoking-self?
  "Is the agent about to be revoked the person doing the revoking?"
  [webid]
  (= webid (:webid @db)))

(defn confirm-revoke! []
  (when-let [webid (:revoking @db)]
    (swap! db assoc :revoking nil)
    ;; Never take control away from yourself. Control is what lets you
    ;; read and rewrite the access control resource, so removing it is
    ;; the one change this app cannot undo — you'd be locked out of the
    ;; rules governing your own file, with no way back through the UI.
    (grant-agent! webid
                  (cond-> {:read false :append false :write false}
                    (not (revoking-self? webid)) (assoc :control false)))))

(defn set-authenticated!
  "Change what anyone signed in with a WebID may do here — the tier
   between one named person and the whole web.

   **This one does not reach a container's contents**, unlike an agent
   or public grant. The case the tier exists for is an inbox: Append on
   the container is what lets someone POST a message, while Append on
   the *contents* would let a signed-in stranger add triples to
   somebody else's message already sitting there. Not read it — append
   only — so the risk is pollution rather than disclosure, and it's
   further blunted by their not being able to list the container. But it
   is access nobody asked for, and a grant should be no wider than the
   sentence the user clicked.

   The cost of that choice: 'anyone signed in may read this folder and
   its files' isn't expressible. Nothing wants it today, and adding the
   same 'and everything inside it' checkbox the agent grant has would
   restore it if anything does.

   Not propagating is enforced rather than merely omitted: any inherited
   rule for this tier is actively cleared. An earlier version did
   propagate, so a container set up with it carries a member policy this
   app would otherwise be unable to remove — the app wrote it, so the
   app should be able to take it away."
  [access]
  (when-let [url (:url (:access @db))]
    (swap! db assoc :access-busy? true :error nil :inherited nil)
    (-> (pod/set-authenticated-access+ url access)
        (p/then (fn [_]
                  (when (container-url? url)
                    ;; best-effort: clearing a rule that was never there
                    ;; is a no-op, and failing to must not fail the grant
                    (-> (pod/set-inherited-access+
                         url :authenticated
                         {:read false :append false :write false})
                        (p/catch (fn [e]
                                   (js/console.warn
                                    "Couldn't clear inherited access for
                                     signed-in people" e)
                                   nil))))))
        (p/then (fn [_] (after-access-change! url nil false)))
        (p/catch (fn [e]
                   (swap! db assoc :access-busy? false)
                   (set-error! (describe "Couldn't change access for signed-in
                                          people" e)))))))

(defn set-public!
  "Change what everyone — including people who aren't signed in — can do.

   On a container this reaches the contents too, the same as an agent
   grant: making a folder public and finding every file in it still
   private would be the same failure, only quieter, because there is no
   named person to notice it isn't working."
  [access]
  (when-let [url (:url (:access @db))]
    (swap! db assoc :access-busy? true :propagation nil :confirm-public nil
                    :inherited nil)
    (-> (pod/set-public-access+ url access)
        (p/then (fn [_]
                  (when (container-url? url)
                    (-> (pod/set-inherited-access+ url :public access)
                        (p/then #(swap! db assoc :inherited (assoc % :ok? true)))
                        (p/catch (fn [e]
                                   (js/console.warn "Public inherited access failed" e)
                                   (swap! db assoc :inherited
                                          {:ok? false :message (.-message e)})
                                   nil))))))
        (p/then (fn [_] (after-access-change! url nil false)))
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

(defn upload-files!
  "Upload picked files into the container being viewed."
  [file-list]
  (let [path (:path @db)
        files (vec (array-seq file-list))
        here (set (map :name (:entries @db)))
        clashes (filter #(here (.-name ^js %)) files)
        fresh (remove #(here (.-name ^js %)) files)]
    ;; a PUT over an existing name replaces it without a word, so don't
    (when (seq clashes)
      (set-error!
       (str "Skipped " (str/join ", " (map #(.-name ^js %) clashes))
            " — already here. Uploading would have replaced them silently;"
            " rename or delete first.")))
    (when (seq fresh)
      (swap! db assoc :uploading (count fresh))
      (-> (p/all (mapv (fn [^js f]
                         (pod/upload-file+
                          (str path (js/encodeURIComponent (.-name f))) f))
                       fresh))
          (p/then (fn [_]
                    (swap! db assoc :uploading nil)
                    (reload-listing!)))
          (p/catch (fn [e]
                     (swap! db assoc :uploading nil)
                     ;; some may have landed before the failure
                     (reload-listing!)
                     (set-error! (describe "Couldn't upload" e))))))))

(defn ask-new! [kind]
  (swap! db assoc :creating {:kind kind :name ""} :menu nil))

(defn update-new-name! [name]
  (swap! db assoc-in [:creating :name] name))

(defn cancel-new! []
  (swap! db assoc :creating nil))

(defn confirm-new! []
  (when-let [{:keys [kind name]} (:creating @db)]
    (let [path (:path @db)
          clean (str/trim name)
          container? (= kind :container)]
      (cond
        (str/blank? clean)
        (cancel-new!)

        ;; a PUT to an existing URL would replace it without a word
        (some #(= clean (:name %)) (:entries @db))
        (do (cancel-new!)
            (set-error! (str "“" clean "” already exists here.")))

        :else
        (let [url (str path (js/encodeURIComponent clean) (when container? "/"))]
          (swap! db assoc :creating nil :loading? true)
          (-> (if container?
                (pod/create-container+ url)
                (pod/create-file+ url (pod/content-type-for clean)))
              (p/then (fn [_]
                        (swap! db assoc :loading? false)
                        (reload-listing!)
                        ;; a new empty file is only useful open, so put
                        ;; the cursor where it's about to be needed
                        (when-not container?
                          (p/then (open-file! {:url url :name clean :container? false})
                                  (fn [_] (start-edit!))))))
              (p/catch (fn [e]
                         (swap! db assoc :loading? false)
                         (set-error! (describe (str "Couldn't create " url) e))))))))))

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
      (cond
        (or (str/blank? target) (= target (:url entry)))
        (cancel-move!)

        ;; Moving a container into itself makes every child a new child
        ;; of the source, which the recursive move then finds and moves
        ;; again — nesting until something gives out. Refuse it.
        (and (:container? entry) (str/starts-with? target (:url entry)))
        (do (swap! db assoc :moving nil)
            (set-error!
             (str "Can't move " (:name entry) " inside itself — that would
                   nest it endlessly.")))
        :else
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
  ;; always starts off: escalating to a recursive delete must be a
  ;; deliberate act each time, not a setting that persists
  (swap! db assoc :confirm entry :menu nil :delete-contents? false))

(defn toggle-delete-contents! []
  (swap! db update :delete-contents? not))

(defn cancel-delete! []
  (swap! db assoc :confirm nil))

(defn confirm-delete! []
  (when-let [entry (:confirm @db)]
    (let [recursive? (and (:container? entry) (:delete-contents? @db))]
      (swap! db assoc :confirm nil :delete-contents? false :loading? true)
      (-> (if recursive? (pod/delete-tree+ entry) (pod/delete+ entry))
          (p/then (fn [_]
                    (when (= (:url entry) (:url (:open @db)))
                      (close-file!))
                    ;; drop it from the listing straight away rather than
                    ;; leaving it on screen for a slow round trip; the
                    ;; refresh behind it is what confirms
                    (swap! db update :entries
                           (fn [es] (vec (remove #(= (:url %) (:url entry)) es))))
                    (refresh!)))
          (p/catch (fn [e]
                     ;; a recursive delete that failed part way has
                     ;; already removed things — reload rather than
                     ;; leaving the listing describing a past state
                     (refresh!)
                     (set-error! (describe (str "Couldn't delete " (:name entry)) e))))))))

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
