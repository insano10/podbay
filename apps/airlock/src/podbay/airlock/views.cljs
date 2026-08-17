(ns podbay.airlock.views
  "Reagent components. Everything renders from state/db; user actions
   call functions in podbay.airlock.state."
  (:require [clojure.string :as str]
            [podbay.airlock.pod :as pod]
            [podbay.airlock.state :as state]
            [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; Formatting

(defn- format-size [bytes]
  (when bytes
    (let [units ["B" "KB" "MB" "GB"]
          idx (min (dec (count units))
                   (if (pos? bytes)
                     (int (/ (js/Math.log bytes) (js/Math.log 1024)))
                     0))
          value (/ bytes (js/Math.pow 1024 idx))]
      (str (if (or (zero? idx) (>= value 100))
             (js/Math.round value)
             (.toFixed value 1))
           " " (nth units idx)))))

(defn- format-date [^js/Date d]
  (when d (.toLocaleString d)))

(defn- short-webid [webid]
  (try
    (.-host (js/URL. webid))
    (catch :default _ webid)))

(defn- icon [{:keys [container? media-type]}]
  (cond
    container? "📁"
    (nil? media-type) "📄"
    (str/starts-with? media-type "image/") "🖼️"
    (str/starts-with? media-type "video/") "🎬"
    (str/starts-with? media-type "audio/") "🎵"
    (re-find #"turtle|n3|trig|triples|json" media-type) "🔗"
    :else "📄"))

;; ---------------------------------------------------------------------------
;; Login

(def ^:private providers
  [["solidcommunity.net" "https://solidcommunity.net"]
   ["Inrupt PodSpaces" "https://login.inrupt.com"]
   ["solidweb.org" "https://solidweb.org"]
   ["Community Solid Server (local)" "http://localhost:3000"]])

(defn login-view []
  (r/with-let [issuer (r/atom "https://solidcommunity.net")]
    [:div.login
     [:div.login-box
      [:h1 "Airlock"]
      [:p.tagline
       "Browse the files in your Solid pod. Nothing is sent anywhere — "
       "this page talks directly to your pod from your browser."]
      [:label {:for "issuer"} "Your Solid identity provider"]
      [:input#issuer {:type "url"
                      :value @issuer
                      :spell-check false
                      :on-change #(reset! issuer (.. % -target -value))
                      :on-key-down #(when (= "Enter" (.-key %))
                                      (state/login! @issuer))}]
      [:div.provider-buttons
       (for [[label url] providers]
         ^{:key url}
         [:button.chip {:class (when (= url @issuer) "active")
                        :on-click #(reset! issuer url)}
          label])]
      [:button.primary {:on-click #(state/login! @issuer)} "Connect"]]]))

;; ---------------------------------------------------------------------------
;; Listing

(defn- breadcrumbs []
  (let [crumbs (state/crumbs)]
    [:nav.crumbs
     (for [[i {:keys [label url]}] (map-indexed vector crumbs)]
       ^{:key url}
       [:span.crumb
        (when (pos? i) [:span.sep "/"])
        (if (= (inc i) (count crumbs))
          [:span.current label]
          [:button.link {:on-click #(state/open-container! url)} label])])]))

(defn- row [{:keys [url name container? size modified media-type] :as entry}]
  (let [{:keys [open opening]} @state/db]
    [:tr.row {:class (when (or (= url (:url open)) (= url opening)) "selected")
              :on-click #(state/open-entry! entry)
              :on-context-menu (fn [^js e]
                                 (.preventDefault e)
                                 (state/show-menu! entry (.-clientX e) (.-clientY e)))}
     [:td.cell-icon (icon entry)]
     [:td.cell-name [:span.name name] (when container? [:span.slash "/"])]
     [:td.cell-type (or media-type (when container? "container") "")]
     [:td.cell-size (or (format-size size) "")]
     [:td.cell-date (or (format-date modified) "")]]))

(defn- attached-rows [url]
  (let [{:keys [show-attached? attached]} @state/db]
    (when show-attached?
      (for [[rel target] (sort (select-keys (get attached url) ["acl" "describedby"]))]
        ^{:key (str url rel)}
        [:tr.row.attached-row
         {:on-click #(state/open-url! target)}
         [:td.cell-icon "↳"]
         [:td.cell-name {:col-span 4}
          [:span.name (pod/entry-name target)]
          [:span.rel (if (= rel "acl") " access control" " description")]]]))))

(defn- upload-control []
  ;; bumping the key remounts the input, which is the only reliable way
  ;; to clear it — otherwise picking the same file twice does nothing
  (r/with-let [input-key (r/atom 0)]
    (let [uploading (:uploading @state/db)]
      [:label.upload {:title "Upload files into this container"}
       [:input {:key @input-key
                :type "file"
                :multiple true
                :disabled (boolean uploading)
                :on-change (fn [^js e]
                             (state/upload-files! (.. e -target -files))
                             (swap! input-key inc))}]
       (if uploading (str "Uploading " uploading "…") "↑ Upload")])))

(defn- listing []
  (let [{:keys [entries loading? path show-attached?]} @state/db]
    [:div.listing
     [:div.listing-tools
      [:button.subtle {:on-click #(state/ask-new! :file)
                       :title "Create an empty file here"} "+ File"]
      [:button.subtle {:on-click #(state/ask-new! :container)
                       :title "Create a container here"} "+ Container"]
      [upload-control]
      [:span.tool-sep]
      [:label.reveal
       [:input {:type "checkbox"
                :checked (boolean show-attached?)
                :on-change state/toggle-attached!}]
       "Show attached resources"]
      (when show-attached?
        [:span.hint "One extra request per item — these aren't in the listing."])]
     (cond
       (and loading? (empty? entries))
       [:p.hint "Loading…"]

       (empty? entries)
       [:p.hint "This container is empty."]

       :else
       [:table.files
        [:thead
         [:tr
          [:th.cell-icon ""] [:th "Name"] [:th "Type"] [:th "Size"] [:th "Modified"]]]
        (into [:tbody]
              (mapcat (fn [e]
                        (into [^{:key (:url e)} [row e]]
                              (attached-rows (:url e))))
                      entries))])
     (when path
       [:p.path-hint path])]))

;; ---------------------------------------------------------------------------
;; Viewer: content in one pane, metadata beside it

(def ^:private access-modes
  [[:read "Read"
    "Allowed to read this resource."]
   [:append "Append"
    "Allowed to add to the resource, without reading it or replacing what's there
e.g. an inbox that lets strangers leave a message they can't then read back."]
   [:write "Write"
    "Allowed to change or delete the resource. Includes the ability to append."]
   [:control-read "See sharing"
    "Allowed to read the access control resource
e.g. to see who this resource is shared with."]
   [:control-write "Change sharing"
    "Allowed to change who this resource is shared with.
Note: Losing this permission means losing the ability to grant it back."]])

(defn- modes-granted
  "The permissions actually granted, as chips. An access map with
   everything false is a real answer — it means no access — so say so
   rather than rendering nothing."
  [access]
  (let [granted (for [[k label explain] access-modes :when (get access k)]
                  [label explain])]
    (if (seq granted)
      (into [:span.modes]
            (for [[label explain] granted]
              ^{:key label} [:span.mode {:title explain} label]))
      [:span.modes [:span.mode.none "No access"]])))

(defn- add-person []
  (r/with-let [webid (r/atom "")
               modes (r/atom #{:read})
               ;; ticked by default: "share this folder" nearly always
               ;; means its contents, and a grant that stops at the
               ;; container is the failure this whole area was fixing
               contents? (r/atom true)
               toggle! (fn [m] (swap! modes #(if (% m) (disj % m) (conj % m))))]
    (let [busy? (:access-busy? @state/db)
          container? (str/ends-with? (or (:url (:access @state/db)) "") "/")
          valid? (str/starts-with? (str/trim @webid) "http")]
      [:div.grant
       [:input {:type "url"
                :placeholder "Give access to a WebID"
                :value @webid
                :spell-check false
                :disabled busy?
                :on-change #(reset! webid (.. % -target -value))}]
       [:div.grant-modes
        (for [[m label] [[:read "Read"] [:append "Append"] [:write "Write"]]]
          ^{:key m}
          [:label [:input {:type "checkbox"
                           :checked (contains? @modes m)
                           :on-change #(toggle! m)}] label])]
       (when container?
         [:label.grant-contents
          [:input {:type "checkbox"
                   :checked @contents?
                   :disabled busy?
                   :on-change #(swap! contents? not)}]
          "and everything inside it"])
       [:button.primary
        {:disabled (or busy? (not valid?) (empty? @modes))
         :on-click (fn []
                     (state/grant-agent!
                      (str/trim @webid)
                      (into {} (for [m [:read :append :write]] [m (contains? @modes m)]))
                      {:contents? @contents?})
                     (reset! webid ""))}
        (if busy? "Applying…" "Grant")]])))

(defn- inherited-note
  "What was written to make the container's contents inherit the grant.

   Reported separately from the check below because they answer
   different questions: this one says whether the rule was written, that
   one says whether the server honoured it. Collapsing them into a
   single pass/fail was what made a failure impossible to place."
  []
  (when-let [{:keys [ok? mechanism acr-url message]} (:inherited @state/db)]
    (if ok?
      [:p.hint
       (case mechanism
         :acp "Also wrote an ACP member policy, so the rule applies to
               everything in this container."
         :wac "Also wrote an acl:default authorisation, so the rule applies
               to everything in this container."
         "Also wrote an inherited access rule for this container's contents.")
       (when acr-url
         [:<> " "
          [:a {:href acr-url :target "_blank" :rel "noopener"}
           "Open the access control resource"]
          " to see it — look for #podbay-member-read-policy."])]
      [:p.warn
       "Couldn't write the rule that makes this container's contents
        inherit the grant"
       (when message [:<> ": " [:span.mono message]])
       ". The container itself was still changed."])))

(defn- propagation-note
  "Whether the grant reached the container's contents. Only shown where
   the answer means something — see state/check-propagation!, which
   doesn't run on ACP."
  []
  (when-let [{:keys [child reached?]} (:propagation @state/db)]
    [:p {:class (if reached? "hint" "warn")}
     (if reached?
       (str "Checked “" child "” inside this container: the grant reached it.")
       (str "Checked “" child "” inside this container: the grant did NOT reach
             it. A file with its own access rules doesn't inherit the
             container's — grant on that file directly."))]))

(defn- agent-grants
  "Subjects are WebIDs, plus :public for a rule naming everyone. Public
   sorts first and is labelled in words — it is the one entry where the
   difference matters most and a URL would say least."
  [subjects]
  [:dl
   (for [[subject access] (sort-by #(if (= :public (key %)) "" (key %)) subjects)]
     ^{:key (str subject)}
     [:<>
      (if (= :public subject)
        [:dt.agent.everyone "Anyone on the web"]
        [:dt.agent {:title subject} (short-webid subject)])
      [:dd [modes-granted access]]])])

(defn- member-access-note
  "Access that doesn't live on this resource: what a container grants
   its contents, and what this resource inherits from above.

   ACP keeps both in the containers' access control resources, where
   the access API can't see them. Without this, a file in a shared
   container reads as shared with nobody — technically 'nothing is set
   here', but anyone would read it as 'nobody can see this'."
  []
  (when-let [{:keys [own inherited]} (:member-access @state/db)]
    (when (or (seq (:agents own)) (seq inherited))
      [:div.member-access
       (when (seq (:agents own))
         [:<>
          [:h3 "Everything inside this container"]
          [agent-grants (:agents own)]])

       (when (seq inherited)
         [:<>
          [:h3 "Inherited from"]
          ;; every level here grants something or failed to be read —
          ;; a container that adds nothing is filtered out upstream
          (for [{:keys [container agents error]} inherited]
            ^{:key container}
            [:div.inherited-from
             [:p.from {:title container} (state/container-label container)]
             (if error
               [:p.hint "Couldn't read this container's rules — "
                [:span.mono error]]
               [agent-grants agents])])])])))

(defn- sharing-pane []
  (let [{:keys [status public agents message url]} (:access @state/db)
        busy? (:access-busy? @state/db)
        public-read? (boolean (:read public))]
    [:div.sharing
     [:h2 "Sharing"]
     (case status
       :loading [:p.hint "Checking…"]

       ;; being refused is the normal answer for anything you don't own,
       ;; so explain it rather than presenting it as a fault
       :denied [:p.hint "Only someone with control access can see how this
                         resource is shared — so this is someone else's to
                         change, not yours."]

       :ready
       [:<>
        [:dl
         [:dt "Everyone"]
         [:dd (if public
                [modes-granted public]
                [:span.unreported "not reported by this server"])]]
        [:label.public-toggle
         [:input {:type "checkbox"
                  :checked public-read?
                  :disabled busy?
                  :on-change (fn []
                               (if public-read?
                                 (state/set-public! {:read false})
                                 ;; widening access to everyone is the one
                                 ;; irreversible thing here — confirm it
                                 (state/ask-public! {:read true})))}]
         "Readable by anyone"]

        (if (seq agents)
          [:dl
           (for [[agent-webid access] (sort agents)]
             ^{:key agent-webid}
             [:<>
              [:dt.agent {:title agent-webid} (short-webid agent-webid)]
              [:dd
               [modes-granted access]
               [:button.subtle.revoke
                {:disabled busy?
                 :title (str "Remove access for " agent-webid)
                 :on-click #(state/ask-revoke! agent-webid)}
                "Revoke"]]])]
          ;; Carefully worded. On ACP this list is only what is set on
          ;; *this* resource: access inherited from a container lives in
          ;; that container's rules and is invisible here, so claiming
          ;; nobody has access would be false for every file in a shared
          ;; container.
          [:p.hint "Nobody has been given access here."])

        [member-access-note]
        [add-person]
        [inherited-note]
        [propagation-note]
        (when (str/ends-with? (or url "") "/")
          [:p.hint "Granting here covers the container itself, and its
                    contents too unless you untick that. Files with access
                    rules of their own keep them either way."])]

       nil)
     (when (and (= :denied status) message)
       [:details.raw-headers
        [:summary "Why"]
        [:p.mono message]])]))

(defn- metadata-pane [{:keys [url status ok? content-type headers size modified]
                       :as open}]
  [:aside.meta
   [:h2 "Details"]
   [:dl
    [:dt "URL"]
    [:dd [:a {:href url :target "_blank" :rel "noopener"} url]]
    [:dt "Status"]
    [:dd {:class (when-not ok? "bad")} status (when ok? " OK")]
    (when content-type [:dt "Content type"])
    (when content-type [:dd content-type])
    (when-let [s (or (get headers "content-length") (some-> size str))]
      [:<> [:dt "Size"] [:dd (format-size (js/parseInt s 10))]])
    (when-let [m (or (get headers "last-modified") (format-date modified))]
      [:<> [:dt "Modified"] [:dd m]])
    (when-let [w (get headers "wac-allow")]
      [:<> [:dt "Your access"] [:dd w]])
    (when-let [e (get headers "etag")]
      [:<> [:dt "ETag"] [:dd.mono e]])]
   (when-let [links (not-empty (select-keys (:links open) ["acl" "describedby"]))]
     [:div.attached
      [:h2 "Attached"]
      [:p.hint "Discovered from the Link header — never by guessing a
                filename, since servers name these differently."]
      (for [[rel target] (sort links)]
        ^{:key rel}
        [:button.link-row {:on-click #(state/open-url! target)
                           :title target}
         (case rel
           "acl" "Access control resource"
           "describedby" "Description (.meta)"
           rel)])])
   [:details.raw-headers
    [:summary "All response headers"]
    [:dl
     (for [[k v] (sort headers)]
       ^{:key k} [:<> [:dt k] [:dd.mono v]])]]
   [sharing-pane]])

(defn- editor [draft]
  (let [saving? (:saving? @state/db)]
    [:div.editor
     [:textarea.editor-text
      {:value draft
       :spell-check false
       :disabled saving?
       :on-change #(state/update-draft! (.. % -target -value))
       ;; Cmd/Ctrl-Enter saves, Escape abandons — the shortcuts anyone
       ;; editing a config file by hand will try first
       :on-key-down (fn [^js e]
                      (cond
                        (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e)))
                        (state/save-edit!)

                        (= "Escape" (.-key e))
                        (state/cancel-edit!)))}]
     [:div.editor-actions
      [:span.hint "Saved as-is — your comments and formatting are kept."]
      [:button.subtle {:on-click state/cancel-edit! :disabled saving?} "Cancel"]
      [:button.primary {:on-click state/save-edit! :disabled saving?}
       (if saving? "Saving…" "Save")]]]))

(defn- content-pane [{:keys [text object-url image? content-type name url] :as open}]
  (let [editing (:editing @state/db)
        editing? (= (:url editing) url)]
    [:section.content
     (cond
       editing? [editor (:draft editing)]

       image? [:img.preview {:src object-url :alt name}]

       (some? text)
       [:<>
        (when (state/editable? open)
          [:div.content-actions
           [:button.subtle {:on-click state/start-edit!} "✎ Edit"]])
        [:pre.code [:code text]]]

       object-url
       [:div.no-preview
        [:p "No preview for " [:code (or content-type "this type")] "."]
        [:a.primary {:href object-url :download name} "Download"]]

       :else
       [:p.hint "Nothing to show."])
     (when (and (not image?) (nil? text) (nil? object-url))
       [:a {:href url :target "_blank" :rel "noopener"} "Open raw"])]))

(defn- viewer []
  (let [{:keys [open opening]} @state/db]
    (cond
      (and opening (nil? open))
      [:div.viewer [:p.hint "Opening…"]]

      open
      [:div.viewer
       [:header.viewer-head
        [:span.viewer-title (icon open) " " (:name open)]
        [:button.subtle {:on-click state/close-file!
                         :title "Close"} "✕"]]
       [:div.panes
        [content-pane open]
        [metadata-pane open]]]

      :else nil)))

;; ---------------------------------------------------------------------------
;; Context menu and confirmation

(defn- context-menu []
  (when-let [{:keys [entry x y]} (:menu @state/db)]
    [:<>
     [:div.menu-backdrop {:on-click state/hide-menu!
                          :on-context-menu (fn [^js e]
                                             (.preventDefault e)
                                             (state/hide-menu!))}]
     [:ul.menu {:style {:left (str x "px") :top (str y "px")}}
      [:li [:button {:on-click #(state/open-entry! entry)}
            (if (:container? entry) "Open container" "Open")]]
      (when (:container? entry)
        [:li [:button {:on-click (fn []
                                   (state/hide-menu!)
                                   (state/show-details! entry))}
              "Details and sharing"]])
      [:li [:button {:on-click (fn []
                                 (.writeText js/navigator.clipboard (:url entry))
                                 (state/hide-menu!))}
            "Copy URL"]]
      [:li [:button {:on-click (fn []
                                 (js/window.open (:url entry) "_blank" "noopener")
                                 (state/hide-menu!))}
            "Open raw in new tab"]]
      [:li [:button {:on-click #(state/ask-move! entry)} "Rename or move…"]]
      [:li.sep]
      [:li [:button.danger {:on-click #(state/ask-delete! entry)} "Delete…"]]]]))

(defn- new-dialog []
  (when-let [{:keys [kind name]} (:creating @state/db)]
    (let [container? (= kind :container)]
      [:div.modal-backdrop {:on-click state/cancel-new!}
       [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
        [:h2 "New " (if container? "container" "file")]
        [:label {:for "new-name"} "Name"]
        [:input#new-name
         {:type "text"
          :value name
          :auto-focus true
          :spell-check false
          :placeholder (if container? "notes" "notes.ttl")
          :on-change #(state/update-new-name! (.. % -target -value))
          :on-key-down #(when (= "Enter" (.-key %)) (state/confirm-new!))}]
        [:p.hint
         (if container?
           "Created in the container you're viewing."
           [:<> "Created empty in the container you're viewing, then opened
                 for editing. The content type is guessed from the
                 extension — "
            [:code (pod/content-type-for (if (str/blank? name) "x" name))]
            " for this name."])]
        [:div.modal-actions
         [:button {:on-click state/cancel-new!} "Cancel"]
         [:button.primary {:on-click state/confirm-new!} "Create"]]]])))

(defn- move-dialog []
  (when-let [{:keys [entry target]} (:moving @state/db)]
    (let [busy? (:move-busy? @state/db)
          {:keys [name container?]} entry]
      [:div.modal-backdrop {:on-click state/cancel-move!}
       [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
        [:h2 "Rename or move " (if container? "container" "file")]
        [:p [:strong name]]
        [:label {:for "move-target"} "New URL"]
        [:input#move-target
         {:type "url"
          :value target
          :spell-check false
          :disabled busy?
          :on-change #(state/update-move-target! (.. % -target -value))
          :on-key-down #(when (= "Enter" (.-key %)) (state/confirm-move!))}]
        [:p.hint "Edit the last segment to rename, or the path to move it
                  elsewhere. Containers move everything inside them."]
        [:ul.caveats
         [:li [:strong "Sharing does not follow."] " Access belongs to a
               URL, so the copy inherits whatever the destination gives
               it — check its sharing afterwards."]
         [:li [:strong "Nothing pointing at the old URL is updated."]
              " Type index registrations, and posts referencing media by
                absolute URL, will still name the old location."]
         [:li "Each item is copied before the original is removed, so a
               failure part way leaves things in both places rather than
               losing them."]]
        [:div.modal-actions
         [:button {:on-click state/cancel-move! :disabled busy?} "Cancel"]
         [:button.primary {:on-click state/confirm-move! :disabled busy?}
          (if busy? "Moving…" "Move")]]]])))

(defn- confirm-revoke []
  (when-let [webid (:revoking @state/db)]
    (let [self? (state/revoking-self? webid)]
      [:div.modal-backdrop {:on-click state/cancel-revoke!}
       [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
        [:h2 (if self? "Remove your own access?" "Remove access?")]
        [:p [:strong (short-webid webid)]]
        [:p.url webid]
        (if self?
          [:<>
           [:p.warning
            "This removes your ability to read and change this resource."]
           [:p.hint
            "Your control over its sharing is kept, so you can grant
             yourself access again — without it you would be locked out
             of the rules governing your own file, with no way back."]]
          [:p.warning
           "This removes read, write and control for this person. They
            keep nothing but whatever public access the resource has."])
        [:div.modal-actions
         [:button {:on-click state/cancel-revoke!} "Cancel"]
         [:button.danger {:on-click state/confirm-revoke!} "Remove access"]]]])))

(defn- confirm-public []
  (when-let [access (:confirm-public @state/db)]
    [:div.modal-backdrop {:on-click state/cancel-public!}
     [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
      [:h2 "Make this readable by anyone?"]
      [:p.url (:url (:access @state/db))]
      [:p.warning
       "Anyone on the web will be able to read this without signing in,
        and you can't know who already has. Revoking later doesn't undo
        a copy someone has taken."]
      [:div.modal-actions
       [:button {:on-click state/cancel-public!} "Cancel"]
       [:button.danger {:on-click #(state/set-public! access)}
        "Make public"]]]]))

(defn- confirm-delete []
  (when-let [{:keys [name container? url]} (:confirm @state/db)]
    [:div.modal-backdrop {:on-click state/cancel-delete!}
     [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
      [:h2 "Delete " (if container? "container" "file") "?"]
      [:p [:strong name]]
      [:p.url url]
      [:p.warning
       "This permanently removes it from your pod and cannot be undone."]
      (when container?
        [:<>
         [:label.recursive
          [:input {:type "checkbox"
                   :checked (boolean (:delete-contents? @state/db))
                   :on-change state/toggle-delete-contents!}]
          "Delete everything inside it too"]
         [:p.hint
          (if (:delete-contents? @state/db)
            "Every resource beneath this will be removed, one request at a
             time, deepest first. There is no undo and no partial
             rollback — whatever gets deleted stays deleted."
            "A container must be empty before a server will delete it, so
             this will fail unless it already is.")]])
      [:div.modal-actions
       [:button {:on-click state/cancel-delete!} "Cancel"]
       [:button.danger {:on-click state/confirm-delete!} "Delete"]]]]))

;; ---------------------------------------------------------------------------
;; Shell

(defn- address-bar []
  (r/with-let [draft (r/atom "")]
    [:div.address
     [:input {:type "url"
              :placeholder "Go to any pod URL — https://…/ for a container"
              :value @draft
              :spell-check false
              :on-change #(reset! draft (.. % -target -value))
              :on-key-down #(when (= "Enter" (.-key %))
                              (state/open-url! @draft))}]
     [:button.subtle {:on-click #(state/open-url! @draft)
                      :title "Go"} "→"]]))

(defn- header []
  (let [{:keys [webid loading?]} @state/db]
    [:header.app-header
     [:div.brand "Airlock"]
     [breadcrumbs]
     [address-bar]
     [:div.session
      [:button.subtle {:on-click state/show-current-details!
                       :title "Details and sharing for this container"}
       "ⓘ"]
      [:button.subtle {:on-click state/refresh!
                       :disabled loading?
                       :title "Refresh"}
       (if loading? "⟳ …" "⟳")]
      [:a.webid {:href webid :target "_blank" :rel "noopener" :title webid}
       (short-webid webid)]
      [:button.subtle {:on-click state/logout!} "Sign out"]]]))

(defn app []
  (let [{:keys [checking-session? webid error]} @state/db]
    (cond
      checking-session?
      [:div.centered [:p.hint "Connecting…"]]

      (nil? webid)
      [login-view]

      :else
      [:div.shell
       [header]
       (when error
         [:div.error
          [:span error]
          [:button.subtle {:on-click state/dismiss-error!} "✕"]])
       [:main.body
        [listing]
        [viewer]]
       [context-menu]
       [confirm-delete]
       [confirm-public]
       [confirm-revoke]
       [move-dialog]
       [new-dialog]])))
