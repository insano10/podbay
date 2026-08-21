(ns podbay.comms.state
  "A single Reagent atom holds all app state; the functions below are
   the only things that change it. No re-frame — for an app this size a
   plain atom keeps the moving parts visible."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [reagent.core :as r]
            [podbay.shared.auth :as auth]
            [podbay.comms.mentions :as mentions]
            [podbay.comms.pod :as pod]))

(defonce db
  (r/atom {:tab :timeline             ; :timeline | :sharing
           :checking-session? true
           :webid nil
           :contacts []
           :posts []             ; every author's posts, newest first
           :posts-by-author {}   ; webid -> that author's posts
           :profiles {}          ; webid -> {:name :avatar}
           :unreadable {}        ; webid -> why their pod couldn't be read
           :destinations []      ; containers you could post into
           :destination nil      ; the one chosen for the next post
           :destination-access nil ; who can read it
           :destinations-status nil ; :loading | :ready | :failed
           :audiences []         ; {:id :label :container}, this app's own
           :registered []        ; containers the pod registers for as:Note
           :audience-busy? false
           :followers []         ; {:webid :containers} — who you've granted
           :followers-status nil ; :loading | :ready | :failed
           :follower-busy? false
           :requests []          ; as:Follow entries sitting in your inbox
           :request-notice nil   ; what happened when you asked to follow
           :own-inbox nil        ; {:status :checking|:present|:absent}
           :apps {}              ; client id url -> the app's own name
           :loading-timeline? false
           :posting? false
           :error nil}))

(defn show-tab! [tab]
  (swap! db assoc :tab tab))

(defn- set-error! [msg]
  (swap! db assoc :error msg :posting? false :loading-timeline? false))

(defn short-webid
  "Compact display form of a WebID, e.g. alice.solidcommunity.net."
  [webid]
  (try
    (.-host (js/URL. webid))
    (catch :default _ webid)))

(defn display-name
  "What to call someone: the name from their profile, falling back to
   the whole WebID.

   The host alone isn't enough — an ESS WebID is
   id.inrupt.com/<username>, so the host is identical for every Inrupt
   user and the path is the only part that identifies anyone. The full
   URL is unlovely, but it appears only when a profile genuinely
   couldn't be read, where being unmistakable beats being tidy."
  [webid]
  (or (get-in @db [:profiles webid :name])
      webid))

(defn mention-candidates
  "Who an @mention in the composer can refer to — the people you follow,
   under the names currently shown for them.

   Only contacts, because a mention has to resolve to a WebID and there
   is no directory to look a stranger up in. A profile that hasn't
   loaded yet falls back to the host, which is still typeable and still
   resolves; it just reads less well."
  []
  (mentions/candidates (:contacts @db) display-name))

(defn- load-profiles! [webids]
  (doseq [webid webids
          :when (not (contains? (:profiles @db) webid))]
    (p/then (pod/load-profile+ webid)
            #(swap! db assoc-in [:profiles webid] %))))

(defn- published-at [post]
  (if-let [d (:published post)] (.getTime d) 0))

(declare load-app-names!)

(defn- rebuild-timeline! [by-author]
  (swap! db assoc
         :posts-by-author by-author
         :posts (vec (sort-by published-at > (mapcat val by-author))))
  ;; a post can mention someone you don't follow, whose profile nothing
  ;; else would fetch — without this the link has no name behind it
  (load-profiles! (distinct (map :webid (mapcat :mentions (:posts @db)))))
  (load-app-names!))

(defn- load-app-names!
  "Resolve each distinct generator to the name its client identifier
   document gives. One fetch per app, ever — the answer doesn't change."
  []
  (doseq [generator (->> (:posts @db) (keep :generator) distinct)
          :when (not (contains? (:apps @db) generator))]
    ;; claim it before the fetch so a second post by the same app
    ;; doesn't start a duplicate request
    (swap! db assoc-in [:apps generator] nil)
    (p/then (pod/app-name+ generator)
            #(swap! db assoc-in [:apps generator] (or % :unnamed)))))

(defn- merge-author-posts!
  "Replace one author's posts and rebuild the merged timeline. Each pod
   answers at its own pace, so results are shown as they arrive rather
   than making the whole timeline wait on the slowest contact."
  [author posts]
  (rebuild-timeline! (assoc (:posts-by-author @db) author posts)))

;; A refresh that is still in flight when another starts must not write
;; its results over the newer one.
(defonce ^:private timeline-run (atom 0))

(defn- fetch-authors!
  "Load each author's posts, merging every one into the timeline as it
   arrives. Results from a superseded refresh are dropped.

   A pod that can't be read is recorded against that author rather than
   breaking the whole timeline — but it is recorded, not silently treated as
   an empty pod, so a transient failure can't masquerade as 'no posts'."
  [authors]
  (let [run @timeline-run
        current? #(= run @timeline-run)]
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

(declare load-destinations!)
(declare load-followers!)
(declare load-requests!)
(declare check-own-inbox!)

(defn refresh-timeline!
  "Reload posts from the user's own pod and every contact's pod,
   merged and sorted newest-first."
  []
  (let [{:keys [webid contacts]} @db
        authors (into [webid] contacts)]
    (swap! timeline-run inc)
    (swap! db assoc :loading-timeline? true :error nil)
    ;; drop anyone no longer followed before fetching
    (rebuild-timeline! (select-keys (:posts-by-author @db) authors))
    ;; where you can post, and who can read it, are as prone to a
    ;; transient failure as the timeline itself — so refresh re-checks them
    (when-let [webid (:webid @db)]
      (load-destinations! webid)
      (load-followers!)
      (load-requests!))
    (-> (fetch-authors! authors)
        (p/then #(swap! db assoc :loading-timeline? false))
        (p/catch #(set-error! (str "Couldn't load your timeline: " (.-message %)))))))

(defn- load-destination-access!
  "Who can read the container a post is about to go into. Choosing where
   to post doesn't make it private — the container's access control does
   — so say which it is before anything is written."
  [url]
  (swap! db assoc :destination-access {:url url :status :loading})
  (let [current? #(= url (:url (:destination-access @db)))]
    (-> (pod/readers+ url (:webid @db))
        (p/then (fn [summary]
                  (when (current?)
                    (swap! db assoc :destination-access
                           (merge {:url url :status :ready} summary)))))
        (p/catch (fn [e]
                   (js/console.warn "Couldn't read access for" url e)
                   (when (current?)
                     ;; keep the status: "couldn't ask" and "you may not
                     ;; ask" are different answers, and naming the wrong
                     ;; one is worse than admitting we don't know
                     (swap! db assoc :destination-access
                            {:url url
                             :status :unknown
                             :code (some-> ^js e .-statusCode)
                             :message (some-> ^js e .-message)})))))))

(defn choose-destination! [url]
  (swap! db assoc :destination url)
  (load-destination-access! url))

(defn audience-label
  "What to call a container in the composer. An audience the user named,
   or failing that the tail of its path — which is all we can say about
   a container this app didn't create."
  [container]
  (or (some #(when (= container (:container %)) (:label %)) (:audiences @db))
      (pod/short-container-name container)))

(defn load-destinations!
  "Where this person can post.

   Audiences come first when there are any: they're containers this app
   created and can describe, so the composer can offer 'Friends' rather
   than a path. Otherwise it falls back to whatever the pod registers
   for as:Note, which is what happens before the first audience exists.

   A failure here used to render nothing at all, which reads as 'no
   destination exists' rather than 'we couldn't find out'."
  [webid]
  (swap! db assoc :destinations-status :loading)
  (-> (p/let [audiences (pod/load-audiences+ webid)
                  registered (pod/post-containers+ webid)]
            ;; both are kept: audiences are where posts go, but the
            ;; registered containers stay visible so the ones this app
            ;; doesn't manage can still be offered for adoption
            (swap! db assoc :audiences audiences :registered registered)
            (if (seq audiences) (mapv :container audiences) registered))
      (p/then (fn [containers]
                (swap! db assoc :destinations containers :destinations-status :ready)
                ;; keep whatever was chosen if it's still on offer, so a
                ;; refresh doesn't silently move where a post will go
                (when-let [chosen (or (some #{(:destination @db)} containers)
                                      (first containers))]
                  (choose-destination! chosen))))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't work out where to post" e)
                 (swap! db assoc :destinations-status :failed)))))

(defn recheck-destination!
  "Ask again — the usual reason for a failure here is a pod that
   answered badly a moment ago."
  []
  (if-let [url (:destination @db)]
    (load-destination-access! url)
    (when-let [webid (:webid @db)] (load-destinations! webid))))

(defn submit-post!
  "Publish a post (text plus optional media files) to the user's own
   pod, then refresh the timeline. Calls on-done once the post is saved.

   Mentions are derived from the finished text here rather than tracked
   as it's typed, so what gets written always describes the text as it
   was actually posted. Only people you follow are candidates: an
   unrecognised @name stays plain text instead of being guessed at."
  [content files on-done]
  (swap! db assoc :posting? true :error nil)
  (-> (pod/save-post+ (:webid @db) content files (:destination @db)
                      (mentions/extract content (mention-candidates)))
      (p/then (fn [_]
                (swap! db assoc :posting? false)
                (on-done)
                (refresh-timeline!)))
      (p/catch #(set-error! (str "Couldn't publish post: " (.-message %))))))

(defn- save-contacts!
  "Write the follow list, showing it immediately and putting it back if
   the write fails.

   Optimistic because a pod can take seconds and the list should feel
   instant — but a failed write used to leave the app showing you
   following someone the pod has no record of, so the timeline had them and
   a reload didn't. Rolling back keeps what's on screen equal to what
   was stored."
  [contacts]
  (let [previous (:contacts @db)]
    (swap! db assoc :contacts contacts)
    (-> (pod/save-contacts+ (:webid @db) contacts)
        (p/then (fn [_] (refresh-timeline!)))
        (p/catch (fn [e]
                   (swap! db assoc :contacts previous)
                   (set-error! (str "Couldn't save who you follow: "
                                    (.-message e)
                                    " — nothing was changed.")))))))

(defn dismiss-notice! []
  (swap! db assoc :request-notice nil))

(defn add-contact!
  "Follow someone, and ask them for access while you're at it.

   Following is unilateral and takes effect immediately — you'll see
   whatever they've made public. The request is a courtesy on top: it
   saves you telling them yourself, and it can't fail in a way that
   undoes the follow. Whitespace is trimmed; the WebID is otherwise
   used exactly as given."
  [webid]
  (let [webid (.trim webid)
        contacts (:contacts @db)]
    (when (and (seq webid) (not (some #{webid} contacts)))
      (save-contacts! (conj contacts webid))
      (-> (pod/request-follow+ (:webid @db) webid)
          (p/then (fn [outcome]
                    ;; the outcome only; the name is resolved when this is
                    ;; rendered, since the profile is very likely still in
                    ;; flight right now
                    (swap! db assoc :request-notice
                           {:webid webid :outcome outcome})))))))

(defn remove-contact! [webid]
  (save-contacts! (vec (remove #{webid} (:contacts @db)))))

(defn adoptable
  "Registered containers this app doesn't yet manage.

   Computed from the *registered* list rather than from the destinations
   on offer, because once any audience exists the destinations are the
   audiences — so deriving it from those would hide adoption exactly
   when it becomes useful.

   The audiences' own parent is excluded. Where a pod registers nothing,
   post-containers+ falls back to that very container, and adopting it
   would put every future audience inside an adopted one — the nesting
   this design exists to avoid."
  []
  (let [{:keys [audiences registered]} @db
        managed (set (map :container audiences))
        parents (set (map pod/audience-parent (map :container audiences)))]
    (->> registered
         (remove managed)
         (remove parents)
         vec)))

(defn- reload-audiences! [webid]
  (-> (pod/load-audiences+ webid)
      (p/then (fn [audiences]
                (swap! db assoc :audiences audiences :audience-busy? false)
                (load-destinations! webid)))
      (p/catch (fn [e]
                 (swap! db assoc :audience-busy? false)
                 (set-error! (str "Couldn't read your audiences: " (.-message e)))))))

(defn create-audience!
  "Make a new audience to post into. The container is created and named
   opaquely; the label you give it is recorded in this app's own
   manifest, not in the container's name — a follower is told the URL
   of what they've been granted, and shouldn't learn what you filed
   them under."
  [label]
  (let [label (str/trim label)
        webid (:webid @db)]
    (when (and webid (seq label))
      (swap! db assoc :audience-busy? true :error nil)
      (-> (pod/create-audience+ webid label)
          (p/then (fn [_] (reload-audiences! webid)))
          (p/catch (fn [e]
                     (swap! db assoc :audience-busy? false)
                     (set-error! (str "Couldn't create that audience: "
                                      (.-message e)))))))))

(defn adopt-audience!
  "Bring a container that already exists under this app's management,
   so posts can be filed there and access granted on it. Deliberately a
   separate act from creating one: it may hold data another app wrote."
  [label container]
  (let [label (str/trim label)
        webid (:webid @db)]
    (when (and webid (seq label) (seq container))
      (swap! db assoc :audience-busy? true :error nil)
      (-> (pod/adopt-audience+ webid label container)
          (p/then (fn [_] (reload-audiences! webid)))
          (p/catch (fn [e]
                     (swap! db assoc :audience-busy? false)
                     (set-error! (str "Couldn't adopt that container: "
                                      (.-message e)))))))))

(defn forget-audience!
  "Stop managing an audience. Leaves the container and everything in it
   untouched — deleting posts is a file-browser act, where what is being
   destroyed is visible."
  [container]
  (when-let [webid (:webid @db)]
    (swap! db assoc :audience-busy? true :error nil)
    (-> (pod/forget-audience+ webid container)
        (p/then (fn [_] (reload-audiences! webid)))
        (p/catch (fn [e]
                   (swap! db assoc :audience-busy? false)
                   (set-error! (str "Couldn't update your audiences: "
                                    (.-message e))))))))

(defn load-followers!
  "Who you've granted an audience to. Read from the shared-with
   container, which is the record rather than a copy of it."
  []
  (when-let [webid (:webid @db)]
    (swap! db assoc :followers-status :loading)
    (-> (pod/followers+ webid)
        (p/then (fn [followers]
                  (swap! db assoc :followers followers
                                  :followers-status :ready
                                  :follower-busy? false)
                  ;; likewise: you can give someone access without
                  ;; following them back
                  (load-profiles! (distinct (keep :webid followers)))))
        (p/catch (fn [e]
                   (js/console.warn "Couldn't read your followers" e)
                   (swap! db assoc :followers-status :failed
                                   :follower-busy? false))))))

(defn check-own-inbox!
  "Whether this pod advertises an inbox, so follow requests can reach
   you at all. Read once per session; it only changes if you go and set
   one up."
  []
  (when-let [webid (:webid @db)]
    (swap! db assoc :own-inbox {:status :checking})
    (-> (pod/inbox-url+ webid)
        (p/then (fn [url]
                  (swap! db assoc :own-inbox
                         (if url
                           {:status :present :url url}
                           {:status :absent}))))
        ;; not knowing is different from not having one, and neither is
        ;; worth an error banner over
        (p/catch (fn [_] (swap! db assoc :own-inbox nil))))))

(defn load-requests!
  "Follow requests waiting in your inbox. Silent when you have no
   inbox — plenty of pods don't, and it isn't a fault."
  []
  (when-let [webid (:webid @db)]
    (-> (pod/follow-requests+ webid)
        (p/then (fn [requests]
                  (swap! db assoc :requests requests)
                  ;; someone asking for access is, by definition, someone
                  ;; you may not follow — so nothing else would have
                  ;; fetched their profile, and they'd show as a raw WebID
                  (load-profiles! (distinct (keep :actor requests)))))
        (p/catch (fn [e]
                   (js/console.warn "Couldn't read follow requests" e))))))

(defn dismiss-request!
  "Ignore a request. Removes it from the inbox without granting
   anything, and without telling the sender — there is nothing to say
   that they couldn't infer from silence."
  [url]
  (swap! db update :requests #(vec (remove (fn [r] (= url (:url r))) %)))
  (-> (pod/dismiss-request+ url)
      (p/then (fn [_] (load-requests!)))))

(defn grant-follower!
  "Let someone read one of your audiences.

   The WebID is used exactly as given — see the exact-match principle in
   docs/following.md. Whitespace is trimmed, which removes a paste
   accident rather than reinterpreting the identifier."
  [follower container]
  (let [follower (str/trim follower)
        webid (:webid @db)]
    (when (and webid (seq follower) (seq container))
      (swap! db assoc :follower-busy? true :error nil)
      (-> (pod/grant-follower+ webid follower container)
          (p/then (fn [_]
                    ;; the request has been answered, and the sender is
                    ;; told so their UI can stop saying "requested" —
                    ;; both best-effort, since the grant already stands
                    (doseq [{:keys [url actor]} (:requests @db)
                            :when (= actor follower)]
                      (pod/dismiss-request+ url))
                    (pod/accept-follow+ webid follower)
                    (load-requests!)
                    (load-followers!)))
          (p/catch (fn [e]
                     (swap! db assoc :follower-busy? false)
                     (set-error! (str "Couldn't give " follower " access: "
                                      (.-message e)))))))))

(defn revoke-follower!
  "Take one audience back from one person."
  [follower container]
  (when-let [webid (:webid @db)]
    (swap! db assoc :follower-busy? true :error nil)
    (-> (pod/revoke-follower+ webid follower container)
        (p/then (fn [_] (load-followers!)))
        (p/catch (fn [e]
                   (swap! db assoc :follower-busy? false)
                   (set-error! (str "Couldn't remove access for " follower ": "
                                    (.-message e))))))))

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
   session if there is one, then load contacts and the timeline."
  []
  (auth/recover-from-url!)
  (-> (auth/handle-redirect!)
      (p/then
       (fn [_]
         (swap! db assoc :checking-session? false)
         (when (auth/logged-in?)
           (let [webid (auth/web-id)]
             (swap! db assoc :webid webid :loading-timeline? true :error nil)
             (swap! timeline-run inc)
             (load-destinations! webid)
             ;; and who you've granted access to, and who's asked. Easy
             ;; to miss: this is the *initial* load, which doesn't go
             ;; through refresh-timeline! — so anything that only appears
             ;; there is absent until something else happens to refresh.
             (load-followers!)
             (load-requests!)
             (check-own-inbox!)
             ;; Your own posts and your contact list are independent, and
             ;; on a slow pod every round trip is seconds — so start both
             ;; at once rather than making the timeline wait on the contacts
             ;; fetch before it asks for anything.
             (-> (p/all [(fetch-authors! [webid])
                         ;; a contact list we couldn't read is reported,
                         ;; never treated as "you follow nobody" — that
                         ;; would quietly reduce the timeline to your own
                         ;; posts and look perfectly normal
                         (-> (p/let [contacts (pod/load-contacts+ webid)]
                               (swap! db assoc :contacts contacts)
                               (fetch-authors! contacts))
                             (p/catch
                              (fn [e]
                                (js/console.error "Couldn't load contacts" e)
                                (set-error!
                                 (str "Couldn't load who you follow: "
                                      (.-message e)
                                      " — your own posts are still shown.")))))])
                 (p/then (fn [_] (swap! db assoc :loading-timeline? false)))
                 (p/catch #(set-error! (str "Couldn't load your timeline: "
                                            (.-message %)))))))))
      (p/catch (fn [e]
                 (swap! db assoc :checking-session? false)
                 (set-error! (str "Session error: " (.-message e)))))))
