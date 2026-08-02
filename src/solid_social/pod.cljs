(ns solid-social.pod
  "All reading and writing of pod data. This is the JS-interop layer:
   it wraps @inrupt/solid-client and returns plain Clojure data, so the
   rest of the app never touches JS objects.

   Data layout inside a pod (relative to the storage root):

     solid-social/
       contacts.ttl      ; WebIDs this user follows (as:following)
       posts/            ; one .ttl resource per post (an as:Note)
       media/            ; uploaded photos/videos, referenced by posts

   Functions suffixed with + return promises."
  (:require ["@inrupt/solid-client" :as sc]
            [clojure.string :as str]
            [promesa.core :as p]
            [solid-social.auth :as auth]
            [solid-social.vocab :as v]))

(def ^:private app-path "solid-social/")

(defn- opts [] #js {:fetch auth/auth-fetch})

;; ---------------------------------------------------------------------------
;; Discovery

;; Resolving a storage root means fetching the WebID profile document, and
;; we need the root of every author several times per refresh (posts,
;; contacts, media). Cache the promise, not just the result, so concurrent
;; callers share one in-flight request instead of racing to repeat it.
(defonce ^:private pod-root-cache (atom {}))

(defn pod-root+
  "Resolve the storage root for a WebID via pim:storage in the profile,
   falling back to the WebID's origin (correct for most pod providers)."
  [webid]
  (or (get @pod-root-cache webid)
      (let [root+ (-> (p/let [urls (sc/getPodUrlAll webid (opts))]
                        (first urls))
                      (p/catch (fn [_] nil))
                      (p/then (fn [root]
                                (or root
                                    (str (.-origin (js/URL. webid)) "/")))))]
        (swap! pod-root-cache assoc webid root+)
        root+)))

(defn forget-caches!
  "Drop cached pod lookups — call on logout, since the next session may
   be a different person."
  []
  (reset! pod-root-cache {}))

(defn- posts-container+ [webid]
  (p/let [root (pod-root+ webid)]
    (str root app-path "posts/")))

(defn- ensure-container+
  "Create a container if it doesn't exist; ignore 'already exists' errors."
  [url]
  (-> (sc/createContainerAt url (opts))
      (p/catch (fn [_] nil))))

;; ---------------------------------------------------------------------------
;; Profiles

(defn load-profile+
  "Fetch display name and avatar from a WebID profile document.
   Resolves to {:name ... :avatar ...} (both possibly nil)."
  [webid]
  (-> (p/let [ds (sc/getSolidDataset webid (opts))
              thing (sc/getThing ds webid)]
        (when thing
          {:name (or (sc/getStringNoLocale thing v/foaf-name)
                     (sc/getStringNoLocale thing v/vcard-fn))
           :avatar (or (sc/getUrl thing v/vcard-hasPhoto)
                       (sc/getUrl thing v/foaf-img))}))
      (p/catch (fn [_] nil))))

;; ---------------------------------------------------------------------------
;; Posts

(defn- thing->post [thing]
  (when (some #(= % v/as-Note) (array-seq (sc/getUrlAll thing v/rdf-type)))
    {:id (sc/asUrl thing)
     :author (sc/getUrl thing v/as-attributedTo)
     :content (sc/getStringNoLocale thing v/as-content)
     :published (sc/getDatetime thing v/as-published)
     :attachments (vec (array-seq (sc/getUrlAll thing v/as-attachment)))}))

(defn load-posts+
  "Load all posts from one person's pod. Resolves to a vector of post
   maps; an unreachable or empty pod resolves to [] rather than failing,
   so one bad contact never breaks the whole feed."
  [webid]
  (-> (p/let [container (posts-container+ webid)
              ds (sc/getSolidDataset container (opts))
              urls (->> (array-seq (sc/getContainedResourceUrlAll ds))
                        (remove #(str/ends-with? % "/")))
              datasets (p/all (map #(-> (sc/getSolidDataset % (opts))
                                        (p/catch (fn [_] nil)))
                                   urls))]
        (->> datasets
             (remove nil?)
             (mapcat #(array-seq (sc/getThingAll %)))
             (keep thing->post)
             vec))
      (p/catch (fn [_] []))))

;; ---------------------------------------------------------------------------
;; Media
;;
;; Pod resources are private by default, and the browser won't attach the
;; session's tokens to a plain <img src="https://pod/…"> — that request goes
;; out unauthenticated and comes back 401. So media has to be fetched here,
;; with the session's fetch, and handed to the element as a blob: URL.

(defn media-url+
  "Fetch a media resource with the session's credentials and wrap the
   bytes in a local blob: URL suitable for an <img>/<video> src.
   The caller owns the URL and must release-media-url! it when done."
  [url]
  (p/let [blob (sc/getFile url (opts))]
    (js/URL.createObjectURL blob)))

(defn release-media-url!
  "Free a blob: URL returned by media-url+."
  [blob-url]
  (js/URL.revokeObjectURL blob-url))

(defn- upload-file+ [container ^js file]
  (p/let [saved (sc/saveFileInContainer container file
                                        #js {:slug (.-name file)
                                             :contentType (.-type file)
                                             :fetch auth/auth-fetch})]
    (sc/getSourceUrl saved)))

(defn- post-thing [webid content published media-urls]
  (reduce (fn [thing url] (sc/addUrl thing v/as-attachment url))
          (-> (sc/createThing #js {:name "post"})
              (sc/addUrl v/rdf-type v/as-Note)
              (sc/addStringNoLocale v/as-content content)
              (sc/addDatetime v/as-published published)
              (sc/addUrl v/as-attributedTo webid))
          media-urls))

(defn save-post+
  "Upload any media files, then save the post as a new Turtle resource
   named by its timestamp. Resolves when the post is stored."
  [webid content files]
  (p/let [root (pod-root+ webid)
          posts-url (str root app-path "posts/")
          media-url (str root app-path "media/")
          _ (ensure-container+ posts-url)
          _ (when (seq files) (ensure-container+ media-url))
          media-urls (p/all (map #(upload-file+ media-url %) files))
          now (js/Date.)
          slug (str/replace (.toISOString now) #"[:.]" "-")
          ds (sc/setThing (sc/createSolidDataset)
                          (post-thing webid content now media-urls))]
    (sc/saveSolidDatasetAt (str posts-url slug ".ttl") ds (opts))))

;; ---------------------------------------------------------------------------
;; Contacts

(defn- contacts-url+ [webid]
  (p/let [root (pod-root+ webid)]
    (str root app-path "contacts.ttl")))

(defn load-contacts+
  "Resolves to a vector of followed WebIDs (empty if none saved yet)."
  [webid]
  (-> (p/let [url (contacts-url+ webid)
              ds (sc/getSolidDataset url (opts))
              thing (sc/getThing ds (str url "#me"))]
        (if thing
          (vec (array-seq (sc/getUrlAll thing v/as-following)))
          []))
      (p/catch (fn [_] []))))

(defn save-contacts+
  "Overwrite the contacts resource with the given list of WebIDs."
  [webid contact-webids]
  (p/let [url (contacts-url+ webid)
          thing (reduce (fn [t c] (sc/addUrl t v/as-following c))
                        (sc/createThing #js {:name "me"})
                        contact-webids)
          ds (sc/setThing (sc/createSolidDataset) thing)]
    (sc/saveSolidDatasetAt url ds (opts))))
