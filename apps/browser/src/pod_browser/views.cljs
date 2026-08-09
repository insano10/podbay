(ns pod-browser.views
  "Reagent components. Everything renders from state/db; user actions
   call functions in pod-browser.state."
  (:require [clojure.string :as str]
            [pod-browser.state :as state]
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
      [:h1 "Pod Browser"]
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
     [:td.cell-type (or media-type (when container? "folder") "")]
     [:td.cell-size (or (format-size size) "")]
     [:td.cell-date (or (format-date modified) "")]]))

(defn- listing []
  (let [{:keys [entries loading? path]} @state/db]
    [:div.listing
     (cond
       (and loading? (empty? entries))
       [:p.hint "Loading…"]

       (empty? entries)
       [:p.hint "This folder is empty."]

       :else
       [:table.files
        [:thead
         [:tr
          [:th.cell-icon ""] [:th "Name"] [:th "Type"] [:th "Size"] [:th "Modified"]]]
        (into [:tbody]
              (for [e entries]
                ^{:key (:url e)} [row e]))])
     (when path
       [:p.path-hint path])]))

;; ---------------------------------------------------------------------------
;; Viewer: content in one pane, metadata beside it

(defn- metadata-pane [{:keys [url status ok? content-type headers size modified]}]
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
   [:details.raw-headers
    [:summary "All response headers"]
    [:dl
     (for [[k v] (sort headers)]
       ^{:key k} [:<> [:dt k] [:dd.mono v]])]]])

(defn- content-pane [{:keys [text object-url image? content-type name url]}]
  [:section.content
   (cond
     image? [:img.preview {:src object-url :alt name}]

     (some? text)
     [:pre.code [:code text]]

     object-url
     [:div.no-preview
      [:p "No preview for " [:code (or content-type "this type")] "."]
      [:a.primary {:href object-url :download name} "Download"]]

     :else
     [:p.hint "Nothing to show."])
   (when (and (not image?) (nil? text) (nil? object-url))
     [:a {:href url :target "_blank" :rel "noopener"} "Open raw"])])

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
            (if (:container? entry) "Open folder" "Open")]]
      [:li [:button {:on-click (fn []
                                 (.writeText js/navigator.clipboard (:url entry))
                                 (state/hide-menu!))}
            "Copy URL"]]
      [:li [:button {:on-click (fn []
                                 (js/window.open (:url entry) "_blank" "noopener")
                                 (state/hide-menu!))}
            "Open raw in new tab"]]
      [:li.sep]
      [:li [:button.danger {:on-click #(state/ask-delete! entry)} "Delete…"]]]]))

(defn- confirm-delete []
  (when-let [{:keys [name container? url]} (:confirm @state/db)]
    [:div.modal-backdrop {:on-click state/cancel-delete!}
     [:div.modal {:on-click (fn [^js e] (.stopPropagation e))}
      [:h2 "Delete " (if container? "folder" "file") "?"]
      [:p [:strong name]]
      [:p.url url]
      [:p.warning
       "This permanently removes it from your pod and cannot be undone."
       (when container?
         " A folder must be empty before it can be deleted.")]
      [:div.modal-actions
       [:button {:on-click state/cancel-delete!} "Cancel"]
       [:button.danger {:on-click state/confirm-delete!} "Delete"]]]]))

;; ---------------------------------------------------------------------------
;; Shell

(defn- header []
  (let [{:keys [webid loading?]} @state/db]
    [:header.app-header
     [:div.brand "Pod Browser"]
     [breadcrumbs]
     [:div.session
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
       [confirm-delete]])))
