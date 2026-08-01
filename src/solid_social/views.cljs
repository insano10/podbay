(ns solid-social.views
  "Reagent components. Everything renders from the state/db atom;
   user actions call functions in solid-social.state."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [solid-social.state :as state]))

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
     [:h1 "Solid Social"]
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

(defn- attachment-view [url]
  (let [ext (url-ext url)]
    (cond
      (image-exts ext) [:img.attachment {:src url :alt "" :loading "lazy"}]
      (video-exts ext) [:video.attachment {:src url :controls true :preload "metadata"}]
      :else [:a.attachment-link {:href url :target "_blank" :rel "noopener"} url])))

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

(defn feed []
  (let [{:keys [posts loading-feed?]} @state/db]
    [:div.feed
     (cond
       (and loading-feed? (empty? posts))
       [:p.hint "Loading feed…"]

       (empty? posts)
       [:p.hint "No posts yet. Write one above, or follow someone."]

       :else
       (for [post posts]
         ^{:key (:id post)} [post-card post]))]))

;; ---------------------------------------------------------------------------
;; Shell

(defn- header []
  (let [{:keys [webid loading-feed?]} @state/db]
    [:header.app-header
     [:h1 "Solid Social"]
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
