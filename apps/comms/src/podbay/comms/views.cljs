(ns podbay.comms.views
  "Reagent components. Everything renders from the state/db atom;
   user actions call functions in podbay.comms.state."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [reagent.core :as r]
            [podbay.comms.pod :as pod]
            [podbay.comms.state :as state]))

;; ---------------------------------------------------------------------------
;; Helpers

(def ^:private image-exts #{"jpg" "jpeg" "png" "gif" "webp" "avif" "svg"})
(def ^:private video-exts #{"mp4" "webm" "ogv" "mov" "m4v"})

(defn- url-ext [url]
  (-> url (str/split #"[?#]") first (str/split #"\.") last str/lower-case))

(defn- short-webid
  "Compact display form of a WebID, e.g. alice.solidcommunity.net."
  [webid]
  (try
    (.-host (js/URL. webid))
    (catch :default _ webid)))

(defn- display-name [webid]
  (or (get-in @state/db [:profiles webid :name])
      (short-webid webid)))

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
      "A feed of the people you choose, from data they own. "
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

(defn composer []
  (r/with-let [text (r/atom "")
               files (r/atom [])
               ;; bumping this key remounts the file input, which is the
               ;; only reliable way to clear it after posting
               input-key (r/atom 0)
               clear! (fn []
                        (reset! text "")
                        (reset! files [])
                        (swap! input-key inc))]
    (let [{:keys [posting?]} @state/db
          can-post? (and (not posting?)
                         (or (seq (str/trim @text)) (seq @files)))]
      [:div.composer
       [:textarea
        {:placeholder "What's happening?"
         :value @text
         :rows 3
         :on-change #(reset! text (.. % -target -value))}]
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
         (if posting? "Publishing…" "Post")]]])))

;; ---------------------------------------------------------------------------
;; Contacts

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
         [:p.hint "Paste a friend's WebID above to see their posts in your feed."]
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
   else will hold these requests back, and a feed full of photos would
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

(defn- post-card [{:keys [id author content published attachments]}]
  (let [avatar (get-in @state/db [:profiles author :avatar])]
    [:article.post
     [:header
      (if avatar
        [:img.avatar {:src avatar :alt ""}]
        [:div.avatar.avatar-fallback
         (-> (display-name author) first str str/upper-case)])
      [:div.byline
       [:a.author {:href author :target "_blank" :rel "noopener"
                   :title author}
        (display-name author)]
       [:time (format-date published)]]]
     (when (seq content)
       (into [:div.content]
             (for [para (str/split-lines content)]
               [:p para])))
     (when (seq attachments)
       (into [:div.attachments]
             (for [url attachments]
               ^{:key url} [attachment-view url])))
     [:footer
      [:a.subtle {:href id :target "_blank" :rel "noopener"} "source"]]]))

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
        "Their posts are missing from the feed rather than absent — try
         refreshing."]])))

(defn feed []
  (let [{:keys [posts loading-feed? unreadable]} @state/db]
    [:div.feed
     [unreadable-notice]
     (cond
       (and loading-feed? (empty? posts))
       [:p.hint "Loading feed…"]

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
  (let [{:keys [webid loading-feed?]} @state/db]
    [:header.app-header
     [:h1 "Comms"]
     [:div.session
      [:a {:href webid :target "_blank" :rel "noopener" :title webid}
       (display-name webid)]
      [:button.subtle {:on-click state/refresh-feed!
                       :disabled loading-feed?
                       :title "Refresh feed"}
       (if loading-feed? "⟳ …" "⟳")]
      [:button.subtle {:on-click state/logout!} "Log out"]]]))

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
       (when error [:div.error error])
       [composer]
       [contacts-panel]
       [feed]])))
