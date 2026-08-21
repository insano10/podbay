(ns podbay.comms.views
  "Reagent components. Everything renders from the state/db atom;
   user actions call functions in podbay.comms.state."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [reagent.core :as r]
            [podbay.comms.mentions :as mentions]
            [podbay.comms.pod :as pod]
            [podbay.comms.state :as state]))

;; ---------------------------------------------------------------------------
;; Helpers

(def ^:private image-exts #{"jpg" "jpeg" "png" "gif" "webp" "avif" "svg"})
(def ^:private video-exts #{"mp4" "webm" "ogv" "mov" "m4v"})

(defn- url-ext [url]
  (-> url (str/split #"[?#]") first (str/split #"\.") last str/lower-case))

;; Both live in state, which holds the profiles they read — mentions
;; need the same answers when a post is saved, where there is no view.
(def ^:private display-name state/display-name)
(def ^:private short-webid state/short-webid)

(defn- avatar-initial
  "One letter for someone with no picture. Can't just take the first
   character of display-name: unresolved, that's the whole WebID, and
   every one of those would be an H for https."
  [webid]
  (-> (or (get-in @state/db [:profiles webid :name]) (short-webid webid))
      first str str/upper-case))

(defn- format-date [^js/Date d]
  (if d (.toLocaleString d) "unknown date"))

;; ---------------------------------------------------------------------------
;; Login

(def ^:private providers
  [["solidcommunity.net" "https://solidcommunity.net"]
   ["Inrupt PodSpaces" "https://login.inrupt.com"]
   ["solidweb.org" "https://solidweb.org"]])

(defn login-view []
  (r/with-let [issuer (r/atom "https://solidcommunity.net")]
    [:div.login
     [:h1 "Comms"]
     [:p.tagline
      "A timeline of the people you choose, from data they own. "
      "No server, no ads, no tracking — this page talks directly to Solid pods."]
     [:div.login-box
      [:label {:for "issuer"} "Your Solid identity provider"]
      [:input#issuer
       {:type "url"
        :value @issuer
        :on-change #(reset! issuer (.. % -target -value))}]
      [:div.provider-buttons
       (for [[label url] providers]
         ^{:key url}
         [:button.chip {:class (when (= url @issuer) "active")
                        :on-click #(reset! issuer url)}
          label])]
      [:button.primary {:on-click #(state/login! @issuer)} "Log in"]
      [:p.hint
       "No pod yet? Any provider above lets you register one for free."]]]))

;; ---------------------------------------------------------------------------
;; Composer

(def ^:private source-label
  "A short, recognisable form of the container a post came from — the
   last couple of path segments, since the origin is already obvious
   from the author and the full URL is in the tooltip. Lives in pod so
   state can label a destination without reaching into the views."
  pod/short-container-name)

(defn- app-name-from-url
  "A name for an app from its client identifier URL alone, for when the
   document itself can't be read.

   The path is the informative part: one host can serve any number of
   apps, so `insano10.github.io` says nothing, while `podbay/comms` says
   which app wrote the post. The last segment is the document's own
   filename (clientid.jsonld) and is dropped. Falls back to the host for
   an identity published at a bare domain, which has no path to use."
  [generator]
  (try
    (let [url (js/URL. generator)
          segments (->> (str/split (.-pathname url) #"/")
                        (remove str/blank?)
                        butlast)]
      (if (seq segments)
        (str/join "/" segments)
        (.-host url)))
    (catch :default _ generator)))

(defn- app-label
  "What to call the app that wrote a post. Its client identifier
   document names itself; until that resolves — or if it never does —
   the URL is a reasonable stand-in."
  [generator]
  (let [known (get (:apps @state/db) generator)]
    (if (string? known)
      known
      (app-name-from-url generator))))

(defn- audience-line
  "Who can read the container a post is headed for. Choosing where to
   post doesn't make it private; this says what the access control
   actually is, before anything is written."
  []
  (let [{:keys [status public? agents code message]} (:destination-access @state/db)]
    (case status
      :loading [:span.audience.checking "Checking who can read this…"]
      :unknown [:span.audience {:title message}
                (case code
                  404 "This container doesn't exist yet — posting will create it."
                  403 "Seeing who can read this needs control access, which you
                       don't have here."
                  401 "Not signed in for this pod."
                  [:<> "Couldn't check who can read this. "
                   [:button.link {:on-click state/recheck-destination!} "Try again"]])]
      :ready (cond
               public?
               [:span.audience.wide "⚠ Anyone on the web can read this container."]

               (pos? agents)
               [:span.audience (str "Shared with " agents
                                    (if (= 1 agents) " person." " people."))]

               :else
               [:span.audience "Only you can read this container."])
      nil)))

(defn- destination-picker []
  (let [{:keys [destinations destination destinations-status]} @state/db]
    (cond
      ;; failing to work out where a post goes must not render as
      ;; nothing — that reads as "there is nowhere", not "we couldn't ask"
      (= :failed destinations-status)
      [:div.destination
       [:span.audience "Couldn't work out where this will be posted. "
        [:button.link {:on-click state/recheck-destination!} "Try again"]]]

      destination
      [:div.destination
       ;; always name the destination, even when there's only one — the
       ;; access line beneath it is meaningless without a subject
       (if (> (count destinations) 1)
         [:label "Posting to "
          [:select {:value destination
                    :on-change #(state/choose-destination! (.. % -target -value))}
           (for [url destinations]
             ^{:key url} [:option {:value url} (state/audience-label url)])]]
         [:span "Posting to "
          [:span.where {:title destination} (state/audience-label destination)]])
       [audience-line]]

      :else nil)))

;; ---------------------------------------------------------------------------
;; Mention autocomplete
;;
;; Typing @ offers the people you follow. This only helps you type the
;; name — nothing is recorded here. The mentions written to the pod are
;; parsed out of the finished text when the post is saved, so editing
;; the sentence afterwards can never leave the two disagreeing.

(def ^:private mention-fragment
  "An @ and the partial name after it, at the very end of the text
   before the caret. Stops at whitespace runs so a name being typed can
   contain single spaces ('@Jenny Be…'), and at a newline, since a
   mention can't span one."
  #"@([^@\n]*)$")

(defn- fragment-at
  "The partial name being typed before the caret, or nil. The pattern is
   anchored to the end, so the @ sits a known distance back and there's
   no need to ask the regex for an index."
  [text caret]
  (when-let [[_ fragment] (re-find mention-fragment (subs text 0 caret))]
    ;; two spaces means the thought moved on; no name has them
    (when-not (re-find #"\s\s" fragment)
      {:fragment fragment
       :start (- caret (count fragment) 1)})))

(defn- suggestions
  "Candidates matching what's been typed after the @, or nil when the
   caret isn't in a mention. An exact match still suggests, so the list
   doesn't vanish the moment a short name is complete."
  [text caret]
  (when-let [{:keys [fragment]} (fragment-at text caret)]
    (let [q (str/lower-case (str "@" fragment))]
      (->> (state/mention-candidates)
           (filter #(str/starts-with? (str/lower-case (:label %)) q))
           (take 6)
           vec))))

(defn- insert-mention
  "Replace the fragment being typed with the full label, plus a trailing
   space so you can carry straight on — unless the text already has one
   there, which it does whenever you go back to fix a mention mid
   sentence. Returns [new-text new-caret]."
  [text caret {:keys [label]}]
  (let [{:keys [start]} (fragment-at text caret)
        rest-of (subs text caret)
        inserted (cond-> label
                   (not (str/starts-with? rest-of " ")) (str " "))]
    [(str (subs text 0 start) inserted rest-of)
     (+ start (count inserted))]))

(defn- mention-menu [items active on-pick]
  [:ul.mention-menu
   (doall
    (for [[i {:keys [webid label] :as item}] (map-indexed vector items)]
      ^{:key webid}
      [:li {:class (when (= i active) "active")
            ;; mousedown, not click: click fires after the textarea has
            ;; already lost focus and closed the menu
            :on-mouse-down (fn [e] (.preventDefault e) (on-pick item))}
       [:span.mention-name label]
       [:span.mention-webid (short-webid webid)]]))])

(defn composer []
  (r/with-let [text (r/atom "")
               files (r/atom [])
               caret (r/atom 0)
               active (r/atom 0)
               ;; Escape closes the menu without closing anything else;
               ;; cleared on the next keystroke so typing brings it back
               dismissed? (r/atom false)
               textarea (r/atom nil)
               ;; bumping this key remounts the file input, which is the
               ;; only reliable way to clear it after posting
               input-key (r/atom 0)
               clear! (fn []
                        (reset! text "")
                        (reset! files [])
                        (reset! caret 0)
                        (swap! input-key inc))
               sync-caret! (fn [e] (reset! caret (.. e -target -selectionStart)))
               pick! (fn [item]
                       (let [[s c] (insert-mention @text @caret item)]
                         (reset! text s)
                         (reset! caret c)
                         (reset! active 0)
                         ;; the caret belongs after the name just
                         ;; inserted, not where React would leave it
                         (when-let [^js el @textarea]
                           (js/requestAnimationFrame
                            #(doto el (.focus) (.setSelectionRange c c))))))]
    (let [{:keys [posting?]} @state/db
          can-post? (and (not posting?)
                         (or (seq (str/trim @text)) (seq @files)))
          items (when-not @dismissed? (suggestions @text @caret))
          open? (seq items)]
      [:div.composer
       [:div.compose-box
        [:textarea
         {:placeholder "What's happening? Use @ to mention someone."
          :value @text
          :rows 3
          :ref #(reset! textarea %)
          :on-change (fn [e]
                       (reset! text (.. e -target -value))
                       (reset! active 0)
                       (reset! dismissed? false)
                       (sync-caret! e))
          ;; the caret moves without the text changing, and that alone
          ;; decides whether the menu should be open
          :on-click sync-caret!
          :on-key-up (fn [e]
                       (when-not (#{"ArrowDown" "ArrowUp" "Enter" "Tab" "Escape"}
                                  (.-key e))
                         (sync-caret! e)))
          :on-key-down
          (fn [^js e]
            (when open?
              (case (.-key e)
                "ArrowDown" (do (.preventDefault e)
                                (swap! active #(mod (inc %) (count items))))
                "ArrowUp" (do (.preventDefault e)
                              (swap! active #(mod (dec %) (count items))))
                ;; Enter picks rather than posting or breaking the line,
                ;; which is what every other mention menu does
                ("Enter" "Tab") (do (.preventDefault e)
                                    (pick! (nth items @active)))
                "Escape" (do (.preventDefault e) (reset! dismissed? true))
                nil)))}]
        (when open? [mention-menu items @active pick!])]
       [:div.composer-actions
        [:label.file-label
         [:input {:key @input-key
                  :type "file"
                  :accept "image/*,video/*"
                  :multiple true
                  :on-change #(reset! files (vec (array-seq (.. % -target -files))))}]
         "📎 Add photos / videos"]
        (when (seq @files)
          [:span.file-count (count @files) " file(s) selected"])
        [:button.primary
         {:disabled (not can-post?)
          :on-click #(state/submit-post! (str/trim @text) @files clear!)}
         (if posting? "Publishing…" "Post")]]
       [destination-picker]])))

;; ---------------------------------------------------------------------------
;; Contacts

(defn audiences-panel
  "Manage the containers Comms posts into.

   An audience is a container this app created, with the label kept
   here rather than in the container's name — see docs/following.md.
   Until one exists the composer falls back to whatever the pod
   registers for as:Note, so this panel is additive: nothing breaks
   before it's used."
  []
  (r/with-let [new-label (r/atom "")
               adopt-label (r/atom "")
               adopting (r/atom nil)]
    (let [{:keys [audiences audience-busy?]} @state/db
          unmanaged (state/adoptable)
          ;; Dereferenced here rather than inside the `for` below. A
          ;; lazy seq is realised outside the component's reactive
          ;; context, so a deref in its body isn't tracked and clicking
          ;; Adopt would change the atom without redrawing anything.
          naming @adopting
          adopt-name @adopt-label]
      [:details.audiences
       [:summary "Audiences (" (count audiences) ")"]
       [:p.hint
        "Where your posts go. Each one is a separate container, so who
         can read it is decided per audience rather than all at once."]
       [:div.contact-add
        [:input {:type "text"
                 :placeholder "Friends"
                 :value @new-label
                 :disabled audience-busy?
                 :on-change #(reset! new-label (.. % -target -value))
                 :on-key-down #(when (= "Enter" (.-key %))
                                 (state/create-audience! @new-label)
                                 (reset! new-label ""))}]
        [:button {:disabled (or audience-busy? (str/blank? @new-label))
                  :on-click (fn []
                              (state/create-audience! @new-label)
                              (reset! new-label ""))}
         (if audience-busy? "Working…" "Create")]]

       (when (seq audiences)
         [:ul.contact-list
          (for [{:keys [label container]} audiences]
            ^{:key container}
            [:li
             [:span {:title container} label]
             [:button.subtle {:disabled audience-busy?
                              :on-click #(state/forget-audience! container)
                              :title (str "Stop managing " label
                                          " — the container and its posts stay")}
              "✕"]])])

       (when (seq unmanaged)
         [:div.adopt
          [:p.hint
           "Your pod registers "
           (if (= 1 (count unmanaged)) "a container" "containers")
           " Comms didn't create. Posts only go to audiences, so adopt
            one to keep posting there and to share it as an audience —
            worth knowing another app may also write to it."]
          [:ul.contact-list
           (for [url unmanaged]
             ^{:key url}
             [:li
              [:span {:title url} (source-label url)]
              (if (= url naming)
                [:span.adopt-name
                 [:input {:type "text"
                          :placeholder "Name it"
                          :auto-focus true
                          :value adopt-name
                          :on-change #(reset! adopt-label (.. % -target -value))
                          :on-key-down
                          #(when (= "Enter" (.-key %))
                             (state/adopt-audience! adopt-name url)
                             (reset! adopting nil)
                             (reset! adopt-label ""))}]
                 [:button {:disabled (str/blank? adopt-name)
                           :on-click (fn []
                                       (state/adopt-audience! adopt-name url)
                                       (reset! adopting nil)
                                       (reset! adopt-label ""))}
                  "Adopt"]]
                [:button.subtle {:disabled audience-busy?
                                 :on-click #(reset! adopting url)}
                 "Adopt"])])]])])))

(defn- request-notice
  "What happened when you asked someone for access. Worth saying out
   loud: following worked either way, and the difference between 'asked'
   and 'couldn't ask' decides whether you need to go and tell them.

   The wording is built here rather than when the request was sent,
   because their profile is very likely still loading at that moment —
   composed too early it says a bare WebID and stays that way."
  []
  (when-let [{:keys [webid outcome]} (:request-notice @state/db)]
    (let [who (display-name webid)]
      [:div.notice {:class (when (not= :sent outcome) "warn")}
       (case outcome
         :sent (str "Asked " who " for access to their posts.")
         :no-inbox (str who " has no inbox, so they can't be asked from"
                        " here — send them your WebID and they can give"
                        " you access.")
         :refused (str "Couldn't leave a request for " who
                       " — their pod wouldn't accept it. You'll still see"
                       " anything they make public.")
         (str "Followed " who "."))
       [:button.subtle {:on-click state/dismiss-notice!
                        :title "Dismiss"} "✕"]])))

(defn- no-inbox-panel
  "Shown when this pod advertises no inbox, so follow requests can't
   reach you.

   Informational only. An inbox is a container other people may append
   to, and adding a write surface to someone's pod is not something an
   app should do on their behalf — so this says what's missing, what it
   would cost, and where to do it, and stops."
  []
  (when (= :absent (:status (:own-inbox @state/db)))
    [:div.no-inbox
     [:h3 "Nobody can send you a follow request"]
     [:p
      "Your pod doesn't advertise an inbox, so when someone follows you
       there's nowhere for them to leave a request. Nothing is broken:
       they can still read whatever you've made public, and you can
       still give them access here by pasting their WebID. You just
       won't be told they'd like it."]
     [:p.hint
      "An inbox is a container others may "
      [:strong "append"] " to. Append is not read — someone can leave a
       message and cannot read your inbox or anyone else's. Comms won't
       create one for you: it's a new way for strangers to write to your
       pod, and that's your decision rather than an app's."]
     [:details
      [:summary "How to set one up"]
      [:ol
       [:li "In " [:a {:href "../airlock/" :target "_blank" :rel "noopener"}
                   "Airlock"] ", create a container — " [:code "inbox/"]
        " at the top of your pod is the convention."]
       [:li "Open it, and in Sharing tick "
        [:strong "\"Anyone signed in may add to this\""] ". That grants "
        [:code "Append"] " to anyone holding a WebID: they can leave a
         message and cannot read the inbox or each other's. Needing an
         identity to write is what deters junk without admitting the
         whole web."]
       [:li "Add " [:code "ldp:inbox <inbox/>"] " to your profile
             document, so apps can find it. That one's a hand edit —
             Airlock's editor will do it."]]
      [:p.hint
       "Don't make it publicly writable. The difference between "
       [:em "anyone signed in"] " and " [:em "anyone at all"] " is the
        difference between junk you can attribute and junk you can't."]]]))

(defn followers-panel
  "Who can read your posts, and the one place to change it.

   This is the whole point of the audiences work: giving someone access
   used to mean opening Airlock, finding the container and understanding
   a sharing pane. Here it's a WebID, an audience, and a button."
  []
  (r/with-let [new-follower (r/atom "")
               chosen (r/atom nil)]
    (let [{:keys [audiences followers followers-status follower-busy? requests]}
          @state/db
          ;; dereferenced here, not inside the loops below — a deref in a
          ;; lazy seq isn't tracked, so the panel wouldn't redraw
          typed @new-follower
          picked (or @chosen (:container (first audiences)))
          ready? (and (seq (str/trim typed)) picked (not follower-busy?))]
      [:details.followers
       [:summary "Followers (" (count followers) ")"
        (when (seq requests)
          [:span.badge (count requests) " waiting"])]
       (if (empty? audiences)
         [:p.hint "Make an audience first — that's what you'd be giving
                   someone access to."]
         [:<>
          [:p.hint
           "Anyone here can read the posts in the audience you choose,
            and nothing else. They'll see them without being told where
            to look."]
          [:div.contact-add
           [:input {:type "url"
                    :placeholder "https://alice.solidcommunity.net/profile/card#me"
                    :value typed
                    :spell-check false
                    :disabled follower-busy?
                    :on-change #(reset! new-follower (.. % -target -value))}]
           (when (> (count audiences) 1)
             [:select {:value (or picked "")
                       :disabled follower-busy?
                       :on-change #(reset! chosen (.. % -target -value))}
              (for [{:keys [label container]} audiences]
                ^{:key container} [:option {:value container} label])])
           [:button {:disabled (not ready?)
                     :on-click (fn []
                                 (state/grant-follower! typed picked)
                                 (reset! new-follower ""))}
            (if follower-busy? "Working…" "Give access")]]])

       ;; requests first: something waiting for you should be the first
       ;; thing you see, and it needs the same audience choice as a
       ;; manual grant
       (when (seq requests)
         [:ul.contact-list.requests
          (for [{:keys [url actor]} requests]
            ^{:key url}
            [:li
             [:span.who {:title actor} (display-name actor)
              [:span.asked " asked for access"]]
             [:span.granted
              (if (empty? audiences)
                [:span.hint "make an audience first"]
                [:<>
                 (when (> (count audiences) 1)
                   [:select {:value (or picked "")
                             :disabled follower-busy?
                             :on-change #(reset! chosen (.. % -target -value))}
                    (for [{:keys [label container]} audiences]
                      ^{:key container} [:option {:value container} label])])
                 [:button {:disabled follower-busy?
                           :on-click #(state/grant-follower! actor picked)}
                  "Allow"]])
              [:button.subtle {:disabled follower-busy?
                               :title "Ignore, and remove the request"
                               :on-click #(state/dismiss-request! url)}
               "✕"]]])])

       (case followers-status
         :loading [:p.hint "Checking…"]
         :failed [:p.warn "Couldn't read who you've given access to. "
                  [:button.link {:on-click state/load-followers!} "Try again"]]
         (when (seq followers)
           [:ul.contact-list
            (for [{:keys [webid containers]} followers]
              ^{:key webid}
              [:li
               [:span.who {:title webid} (display-name webid)]
               [:span.granted
                (for [c containers]
                  ^{:key c}
                  [:span.granted-audience
                   (state/audience-label c)
                   [:button.subtle
                    {:disabled follower-busy?
                     :title (str "Remove " (display-name webid) " from "
                                 (state/audience-label c))
                     :on-click #(state/revoke-follower! webid c)}
                    "✕"]])]])]))])))

(defn contacts-panel []
  (r/with-let [new-contact (r/atom "")]
    (let [{:keys [contacts]} @state/db]
      [:details.contacts
       [:summary "Following (" (count contacts) ")"]
       [:div.contact-add
        [:input {:type "url"
                 :placeholder "https://alice.solidcommunity.net/profile/card#me"
                 :value @new-contact
                 :on-change #(reset! new-contact (.. % -target -value))
                 :on-key-down #(when (= "Enter" (.-key %))
                                 (state/add-contact! @new-contact)
                                 (reset! new-contact ""))}]
        [:button {:on-click (fn []
                              (state/add-contact! @new-contact)
                              (reset! new-contact ""))}
         "Follow"]]
       (if (empty? contacts)
         [:p.hint "Paste a friend's WebID above to see their posts in your timeline."]
         [:ul.contact-list
          (for [webid contacts]
            ^{:key webid}
            [:li
             [:span {:title webid} (display-name webid)]
             [:button.subtle {:on-click #(state/remove-contact! webid)
                              :title "Unfollow"}
              "✕"]])])])))

;; ---------------------------------------------------------------------------
;; Feed

(defn- attachment-name [url]
  (-> url (str/split #"[?#]") first (str/split #"/") last
      (as-> s (try (js/decodeURIComponent s) (catch :default _ s)))))

(defn- broken-attachment [url code]
  [:div.attachment.attachment-broken
   [:p.why
    (case code
      404 "This attachment is missing. The post still points at where it
           used to be — most likely it was moved or renamed."
      (401 403) "You don't have access to this attachment."
      nil "This attachment couldn't be fetched."
      (str "This attachment couldn't be loaded (" code ")."))]
   [:a {:href url :target "_blank" :rel "noopener" :title url}
    (attachment-name url)]])

(defn- authed-media
  "Renders one piece of pod media. The bytes are fetched with the
   session's credentials (a bare src= would be an unauthenticated
   request, and 401 on anything that isn't public), then `render` is
   called with a local blob: URL to point the element at.

   Fetching is deferred until the placeholder nears the viewport. That
   deliberately reproduces what the browser used to do for us via
   loading=\"lazy\": authenticated media can't be a plain src, so nothing
   else will hold these requests back, and a timeline full of photos would
   otherwise download all of them at once, ahead of the posts.

   The blob: URL is released when the component unmounts."
  [url render]
  (r/with-let [media (r/atom {:status :idle})
               observer (atom nil)
               unwatch! (fn []
                          (when-let [^js o @observer]
                            (.disconnect o)
                            (reset! observer nil)))
               fetch! (fn []
                        (unwatch!)
                        (swap! media assoc :status :loading)
                        (-> (pod/media-url+ url)
                            (p/then (fn [blob-url]
                                      ;; unmounted mid-flight: nothing will
                                      ;; ever release this, so do it now
                                      (if (= :released (:status @media))
                                        (pod/release-media-url! blob-url)
                                        (reset! media {:status :ready :src blob-url}))))
                            (p/catch (fn [e]
                                       (js/console.warn "Couldn't load attachment" url e)
                                       (reset! media
                                               {:status :error
                                                :code (some-> ^js e .-statusCode)
                                                :message (some-> ^js e .-message)})))))
               ;; ref callback: React hands us the placeholder node on
               ;; mount and nil on unmount
               watch! (fn [el]
                        (if (nil? el)
                          (unwatch!)
                          (let [o (js/IntersectionObserver.
                                   (fn [entries]
                                     (when (some #(.-isIntersecting ^js %)
                                                 (array-seq entries))
                                       (fetch!)))
                                   ;; start slightly before it scrolls in
                                   #js {:rootMargin "400px"})]
                            (reset! observer o)
                            (.observe o el))))]
    (let [{:keys [status src code]} @media]
      (case status
        (:idle :loading) [:div.attachment.attachment-loading {:ref watch!}]
        :ready (render src)
        ;; A failure must not look like a deliberate link. Say what
        ;; happened: a missing attachment usually means the file moved
        ;; and the post still names where it used to be, which no amount
        ;; of retrying will fix.
        :error [broken-attachment url code]
        nil))
    (finally
      (unwatch!)
      (when-let [src (:src @media)]
        (pod/release-media-url! src))
      (reset! media {:status :released}))))

(defn- attachment-view [url]
  (let [ext (url-ext url)]
    (cond
      (image-exts ext)
      [authed-media url (fn [src] [:img.attachment {:src src
                                                   :alt (attachment-name url)}])]

      (video-exts ext)
      [authed-media url (fn [src] [:video.attachment {:src src :controls true}])]

      :else
      [:a.attachment-link {:href url :target "_blank" :rel "noopener"} url])))

(defn- content-line
  "One paragraph of a post, with any mentions in it linked.

   The mention's stored text is matched against the content rather than
   an offset, so an app that rewrites or reflows the text can't leave a
   link pointing at the wrong words — at worst the mention stops
   matching and shows as the plain text the author typed."
  [line mentions]
  (into [:p]
        (for [[i part] (map-indexed vector (mentions/scan line mentions))]
          (if (string? part)
            part
            ^{:key i}
            [:a.mention {:href (:webid part)
                         :target "_blank"
                         :rel "noopener"
                         ;; the display name may have moved on since the
                         ;; post was written, so name who it resolves to
                         :title (str (display-name (:webid part))
                                     " — " (:webid part))}
             (:label part)]))))

(defn- post-card [{:keys [author content published attachments source
                          generator mentions]}]
  (let [avatar (get-in @state/db [:profiles author :avatar])]
    [:article.post
     [:header
      (if avatar
        [:img.avatar {:src avatar :alt ""}]
        [:div.avatar.avatar-fallback (avatar-initial author)])
      [:div.byline
       [:a.author {:href author :target "_blank" :rel "noopener"
                   :title author}
        (display-name author)]
       [:time (format-date published)]]
      [:div.tags
       (when generator
         [:span.via {:title generator} "via " (app-label generator)])
       (when source
         [:span.origin {:title source} (source-label source)])]]
     (when (seq content)
       (into [:div.content]
             (for [para (str/split-lines content)]
               [content-line para mentions])))
     (when (seq attachments)
       (into [:div.attachments]
             (for [url attachments]
               ^{:key url} [attachment-view url])))]))

(defn- unreadable-notice []
  (let [unreadable (:unreadable @state/db)]
    (when (seq unreadable)
      [:div.unreadable
       [:p "Couldn't read " (if (= 1 (count unreadable)) "a pod" "some pods") ":"]
       [:ul
        (for [[webid reason] (sort unreadable)]
          ^{:key webid}
          [:li [:span.who {:title webid} (display-name webid)] " — " reason])]
       [:p.hint
        "Their posts are missing from the timeline rather than absent — try
         refreshing."]])))

(defn timeline []
  (let [{:keys [posts loading-timeline? unreadable]} @state/db]
    [:div.timeline
     [unreadable-notice]
     (cond
       (and loading-timeline? (empty? posts))
       [:p.hint "Loading your timeline…"]

       ;; only claim emptiness when nothing failed — otherwise "no posts"
       ;; would be a guess dressed up as a fact
       (and (empty? posts) (empty? unreadable))
       [:p.hint "No posts yet. Write one above, or follow someone."]

       :else
       (for [post posts]
         ^{:key (:id post)} [post-card post]))]))

;; ---------------------------------------------------------------------------
;; Shell

(defn- header []
  (let [{:keys [webid loading-timeline?]} @state/db]
    [:header.app-header
     [:h1 "Comms"]
     [:div.session
      [:a {:href webid :target "_blank" :rel "noopener" :title webid}
       (display-name webid)]
      [:button.subtle {:on-click state/refresh-timeline!
                       :disabled loading-timeline?
                       :title "Refresh"}
       (if loading-timeline? "⟳ …" "⟳")]
      [:button.subtle {:on-click state/logout!} "Log out"]]]))

(defn- tabs
  "Two views: posting and reading, or deciding who sees what. They were
   one screen and it was cluttered — the sharing panels are things you
   set up occasionally, not while writing a post.

   The waiting count rides on the tab, because moving the followers
   panel off the default view would otherwise hide the one thing in it
   that's time-sensitive: somebody waiting on you."
  []
  (let [{:keys [tab requests]} @state/db
        waiting (count requests)]
    [:nav.tabs
     (for [[id label] [[:timeline "Timeline"] [:sharing "Sharing"]]]
       ^{:key id}
       [:button {:class (when (= tab id) "active")
                 :aria-current (when (= tab id) "page")
                 :on-click #(state/show-tab! id)}
        label
        (when (and (= :sharing id) (pos? waiting))
          [:span.badge waiting])])]))

(defn app []
  (let [{:keys [checking-session? webid error tab]} @state/db]
    (cond
      checking-session?
      [:div.centered [:p.hint "Connecting…"]]

      (nil? webid)
      [login-view]

      :else
      [:div.shell
       [header]
       [tabs]
       ;; errors belong above whichever view raised them
       (when error [:div.error error])
       (if (= :sharing tab)
         [:<>
          [audiences-panel]
          [request-notice]
          [followers-panel]
          [contacts-panel]
          ;; last: it's a standing condition, not something that just
          ;; happened, so it shouldn't push the panels down
          [no-inbox-panel]]
         [:<>
          [composer]
          [timeline]])])))
