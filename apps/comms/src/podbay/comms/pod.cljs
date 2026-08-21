(ns podbay.comms.pod
  "All reading and writing of pod data. This is the JS-interop layer:
   it wraps @inrupt/solid-client and returns plain Clojure data, so the
   rest of the app never touches JS objects.

   Data layout inside a pod (relative to the storage root):

     podbay/comms/
       contacts.ttl      ; WebIDs this user follows (as:following)
       posts/            ; one .ttl resource per post (an as:Note)
       media/            ; uploaded photos/videos, referenced by posts

   Functions suffixed with + return promises."
  (:require ["@inrupt/solid-client" :as sc]
            [clojure.string :as str]
            [promesa.core :as p]
            [podbay.shared.access :as access]
            [podbay.shared.auth :as auth]
            [podbay.shared.vocab :as sv]
            [podbay.comms.vocab :as v]))

(def ^:private app-path
  "Where this app keeps its data in a pod when nothing else says
   otherwise. Namespaced under the suite so a later Podbay app can claim
   its own container without collisions. Only a fallback: a pod that
   publishes a type index decides for itself where posts live."
  "podbay/comms/")


;; Fetch options — including the no-cache reasoning — live in
;; podbay.shared.auth, so both apps mean the same thing by them.

;; ---------------------------------------------------------------------------
;; Discovery
;;
;; Everything here distinguishes two things that are easy to conflate:
;; "the server told us there is nothing" and "we couldn't ask". The first
;; is an answer worth acting on — a pod with no type index really has
;; none, so fall back to the convention. The second is ignorance, and
;; guessing past it is how a transient 502 turns into a timeline that quietly
;; shows the wrong container, or none at all.

(defn- missing?
  "True when the server answered, and its answer was that there is no
   such document. Anything else — 401, 403, 502, a dropped connection —
   is a failure to find out."
  [e]
  (= 404 (some-> ^js e .-statusCode)))

;; A WebID profile is needed twice over — once for the storage root, once
;; for the display name and avatar — and the storage root is needed again
;; for every container we touch. On a slow pod that document is the single
;; most expensive thing we fetch, so fetch it once per WebID and share the
;; promise: concurrent callers then join one in-flight request instead of
;; racing to repeat it.
(defonce ^:private profile-cache (atom {}))

(defn- profile+
  "The WebID profile document as a dataset. Rejects if it can't be read."
  [webid]
  (or (get @profile-cache webid)
      (let [ds+ (-> (sc/getSolidDataset webid (auth/opts))
                    (p/catch (fn [e]
                               ;; a cached failure would poison the whole
                               ;; session, so forget it and let the next
                               ;; caller try again
                               (swap! profile-cache dissoc webid)
                               (p/rejected e))))]
        (swap! profile-cache assoc webid ds+)
        ds+)))

(defn- profile-thing+
  "The subject of a WebID's profile, or nil if the document genuinely
   isn't there. Rejects if we simply couldn't read it."
  [webid]
  (-> (p/let [ds (profile+ webid)]
        (sc/getThing ds webid))
      (p/catch (fn [e]
                 (if (missing? e) nil (p/rejected e))))))

(defonce ^:private alt-profiles-cache (atom {}))

(defn- alt-profiles+
  "The extended profile documents a WebID links with rdfs:seeAlso.

   ESS keeps the WebID document itself to the bare plumbing — issuer,
   storage — and puts everything else here, in a document inside the pod
   whose access the owner controls. CSS-style pods usually put it all in
   the card, in which case this is empty and costs one lookup, cached."
  [webid]
  (or (get @alt-profiles-cache webid)
      (let [ds+ (-> (p/let [profiles (sc/getProfileAll webid (auth/opts))]
                      (vec (array-seq (.-altProfileAll ^js profiles))))
                    (p/catch (fn [e]
                               ;; frequently unreadable by design — that's
                               ;; what "private extended profile" means
                               (js/console.warn "Couldn't read extended profile of" webid e)
                               [])))]
        (swap! alt-profiles-cache assoc webid ds+)
        ds+)))

(defn- profile-url+
  "Look for `predicate` on the WebID, first in its own document and then
   in any extended profile it links to. Without the second step an ESS
   pod can't publish anything discoverable, since the only document it
   lets you write is the linked one."
  [webid predicate]
  (p/let [thing (profile-thing+ webid)
          direct (when thing (sc/getUrl thing predicate))]
    (if direct
      direct
      (p/let [alts (alt-profiles+ webid)]
        (some (fn [ds]
                (when-let [t (sc/getThing ds webid)]
                  (sc/getUrl t predicate)))
              alts)))))

(defn pod-root+
  "Resolve the storage root for a WebID via pim:storage in the profile,
   falling back to the WebID's origin (correct for most pod providers).

   That fallback applies when the profile is readable and simply doesn't
   say — not when it couldn't be read. Guessing an origin for a profile
   we failed to fetch would point every later request at a container
   that may not be the right one."
  [webid]
  (p/let [thing (profile-thing+ webid)
          storage (when thing (sc/getUrl thing sv/pim-storage))]
    (or storage
        (str (.-origin (js/URL. webid)) "/"))))

;; Nothing in Solid fixes where data lives — container names are an
;; implementation detail, and two apps agreeing on one would be a
;; coincidence, not interoperability. What a pod publishes instead is a
;; *type index*: a document mapping an RDF class to the container holding
;; instances of it. So posts are found by asking "where do this person's
;; as:Note resources live?" rather than by knowing about podbay/comms/.
;;
;; Cached like the profile, since a refresh looks it up for every author.
(defonce ^:private type-index-cache (atom {}))

(defn- type-index+
  "The WebID's public type index as a dataset — nil when the profile
   links none, or links one that isn't there. Rejects if it exists but
   couldn't be read, since falling back to our own convention on a
   transient failure would read posts from the wrong container."
  [webid]
  (or (get @type-index-cache webid)
      (let [ds+ (-> (p/let [url (profile-url+ webid v/solid-publicTypeIndex)]
                      (when url
                        (-> (sc/getSolidDataset url (auth/opts))
                            (p/catch (fn [e]
                                       (if (missing? e) nil (p/rejected e)))))))
                    (p/catch (fn [e]
                               (swap! type-index-cache dissoc webid)
                               (p/rejected e))))]
        (swap! type-index-cache assoc webid ds+)
        ds+)))

(defn- as-container
  "A container URL must end in a slash. A server treats the two forms as
   different resources, and joining a filename onto the slashless form
   quietly produces a *sibling* with a run-together name rather than a
   child — so normalise whatever a type index happens to say."
  [url]
  (cond-> url (not (str/ends-with? url "/")) (str "/")))

(defn- registrations+
  "Every place a WebID's public type index says instances of `class-iri`
   live: containers and individual documents.

   A type index is a set of hints, not an exhaustive statement. Several
   registrations may name the same class — typically one per app — a
   single registration may list several containers, and solid:instance
   points at an individual document rather than a container. Reading all
   of them is what lets this timeline pick up posts some *other* Solid app
   wrote into its own container, which is the point of discovery."
  [webid class-iri]
  (p/let [ds (type-index+ webid)]
    (if-not ds
      {:containers [] :instances []}
      (let [matching (->> (array-seq (sc/getThingAll ds))
                          (filter #(some #{class-iri}
                                         (array-seq (sc/getUrlAll % v/solid-forClass)))))
            urls-of (fn [predicate]
                      (into [] (distinct)
                            (mapcat #(array-seq (sc/getUrlAll % predicate)) matching)))]
        {:containers (mapv as-container (urls-of v/solid-instanceContainer))
         ;; an instance names a document, not a container — no slash
         :instances (urls-of v/solid-instance)}))))

(defn forget-caches!
  "Drop cached pod lookups — call on logout, since the next session may
   be a different person."
  []
  (reset! profile-cache {})
  (reset! alt-profiles-cache {})
  (reset! type-index-cache {}))

;; The manifest is defined further down, next to the rest of the audience
;; handling; reading posts needs it here.
(declare load-audiences+)

(defn- own-audience-containers+
  "Your own audiences, as sources to read posts from.

   A private audience is deliberately absent from the public type index
   — that's what stops the world learning it exists. But discovery has
   to work for *you* too, and nothing else would find it: your own timeline
   would silently omit everything you posted there.

   Only for yourself. Someone else's manifest is private to them, so
   asking for it would cost a request per contact and be refused every
   time; their private audiences reach you through the grant instead.

   Best-effort — a manifest that can't be read must not take the whole
   timeline with it, since the registered containers still have posts in."
  [webid]
  (if (= webid (auth/web-id))
    (-> (p/let [audiences (load-audiences+ webid)]
          (mapv :container audiences))
        (p/catch (fn [e]
                   (js/console.warn "Couldn't read your audiences" e)
                   [])))
    []))

(declare granted-containers+)

(defn- granted-to-me+
  "Containers this person has granted *me* read on, from the shared-with
   document they wrote for me. Nothing for myself — my own audiences
   come from my own manifest.

   This is how a private audience is discovered: I derive the location
   of my own document from my own WebID, so nobody has to tell me it
   exists. Refused or missing both mean 'nothing for you', and both come
   back as an empty list."
  [webid]
  (let [me (auth/web-id)]
    (if (or (nil? me) (= webid me))
      []
      (granted-containers+ webid me))))

(defn- post-sources+
  "Everywhere to read this person's posts from — every registered
   container and document, my own audiences if this is me, and anything
   they have granted me otherwise. Falls back to our own convention only
   when nothing at all turns up."
  [webid]
  (p/let [{:keys [containers instances]} (registrations+ webid v/as-Note)
          mine (own-audience-containers+ webid)
          granted (granted-to-me+ webid)
          ;; an adopted container is both registered and an audience, and
          ;; a public one may also be named in a shared-with document
          containers (vec (distinct (concat containers mine granted)))]
    (if (or (seq containers) (seq instances))
      {:containers containers :instances instances}
      (p/let [root (pod-root+ webid)]
        {:containers [(str root app-path "posts/")] :instances []}))))

(defn post-containers+
  "Every container this person could post into: each one registered for
   as:Note, or our convention if their pod registers none.

   More than one is the interesting case — a pod can keep posts for
   different audiences in different containers, since a container is the
   unit access control inherits through."
  [webid]
  (p/let [{:keys [containers]} (registrations+ webid v/as-Note)]
    (if (seq containers)
      (vec containers)
      (p/let [root (pod-root+ webid)]
        [(str root app-path "posts/")]))))

(defn- write-container+
  "Where a new post goes when the author hasn't chosen. Reading merges
   every registered source, but a write has to pick one: the first
   registered container, else the convention. That's the same
   resolution a reader tries first, so a post always lands somewhere
   its author's own timeline will find it."
  [webid]
  (p/let [containers (post-containers+ webid)]
    (first containers)))

(def ^:private universal-access sc/universalAccess)

(defn readers+
  "Who, other than you, can read a container — enough to tell whether
   what you're about to write is as private as you think.

   Rejects when the access control resource can't be read, which needs
   control access and so is normal on a pod that isn't yours."
  [url self-webid]
  (p/let [public (.getPublicAccess ^js universal-access url (auth/opts))
          agents (.getAgentAccessAll ^js universal-access url (auth/opts))
          others (when agents
                   (->> (array-seq (js/Object.keys agents))
                        (remove #(= % self-webid))
                        (filter #(some-> ^js (aget agents %) .-read))))]
    {:public? (boolean (some-> ^js public .-read))
     :agents (count others)}))

(defn- ensure-container+
  "Create a container if it isn't already there.

   A conflict means it exists, which is the desired state — anything
   else (no permission, say) is left to surface from the write that
   follows, but is logged here so the cause isn't lost behind a
   confusing downstream error."
  [url]
  (-> (sc/createContainerAt url (auth/opts))
      (p/catch (fn [e]
                 (when-not (#{409 412} (some-> ^js e .-statusCode))
                   (js/console.warn "Couldn't create container" url e))
                 nil))))

;; ---------------------------------------------------------------------------
;; Profiles

(defn- details-of
  "Display name and avatar, as far as one document knows them."
  [thing]
  (when thing
    {:name (or (sc/getStringNoLocale thing v/foaf-name)
               (sc/getStringNoLocale thing v/vcard-fn))
     :avatar (or (sc/getUrl thing v/vcard-hasPhoto)
                 (sc/getUrl thing v/foaf-img))}))

(defn- extended-details+
  "A WebID document has to be public — an app must read it to discover
   where to log you in, before you've authenticated to anything. So the
   convention is to keep it to the plumbing and link the personal
   details with rdfs:seeAlso, in a document whose access you control.
   Inrupt's pods are provisioned that way; solidcommunity.net's put
   everything in the card.

   Only consulted when the WebID document has no name of its own, since
   it costs another round trip. Returns nil if those documents aren't
   readable by us, which is a perfectly ordinary outcome — they're the
   private half by design."
  [webid]
  (-> (p/let [alternates (alt-profiles+ webid)]
        (->> alternates
             (keep #(details-of (sc/getThing % webid)))
             (filter :name)
             first))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read extended profile of" webid e)
                 nil))))

(defn load-profile+
  "Fetch display name and avatar for a WebID.
   Resolves to {:name ... :avatar ...} (both possibly nil). Shares the
   WebID document with pod-root+ rather than requesting it a second
   time, and only reaches for linked profiles when it must."
  [webid]
  (-> (p/let [thing (profile-thing+ webid)
              card (details-of thing)]
        (if (:name card)
          card
          (p/let [extended (extended-details+ webid)]
            {:name (or (:name card) (:name extended))
             :avatar (or (:avatar card) (:avatar extended))})))
      ;; a name and a picture are decoration: falling back to the WebID
      ;; host is a fine outcome, so this one stays forgiving
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read profile of" webid e)
                 nil))))

;; ---------------------------------------------------------------------------
;; Posts

(defn- mentions-of
  "The as:Mention things a post tags, as {:webid :label}.

   Each tag is a separate subject in the same document, so resolving one
   is a local lookup rather than a fetch. A tag we can't resolve, or one
   missing either half, is dropped: a mention with no WebID can't be
   linked and one with no text can't be found in the content, so neither
   is renderable."
  [ds thing]
  (->> (array-seq (sc/getUrlAll thing v/as-tag))
       (keep (fn [url]
               (when-let [tag (sc/getThing ds url)]
                 (let [webid (sc/getUrl tag v/as-href)
                       label (sc/getStringNoLocale tag v/as-name)]
                   (when (and webid (seq label))
                     {:webid webid :label label})))))
       vec))

(defn- thing->post [ds thing]
  (when (some #(= % v/as-Note) (array-seq (sc/getUrlAll thing sv/rdf-type)))
    {:id (sc/asUrl thing)
     :author (sc/getUrl thing v/as-attributedTo)
     :content (sc/getStringNoLocale thing v/as-content)
     :published (sc/getDatetime thing v/as-published)
     :generator (sc/getUrl thing v/as-generator)
     :mentions (mentions-of ds thing)
     :attachments (vec (array-seq (sc/getUrlAll thing v/as-attachment)))}))

(defn- posts-in-dataset
  "Every post in one already-fetched document. Takes the dataset rather
   than mapping over its things, because a post's mentions are other
   subjects in the same document and can only be resolved against it."
  [ds]
  (keep #(thing->post ds %) (array-seq (sc/getThingAll ds))))

(defn- posts-in-container+
  "Every post in one container. One unreadable post is skipped — it
   shouldn't hide the rest of someone's timeline — but it is logged."
  [container]
  (p/let [;; the listing must be current: a post published a moment ago
          ;; has to appear. The posts themselves may be cached.
          ds (sc/getSolidDataset container (auth/fresh-opts))
          urls (->> (array-seq (sc/getContainedResourceUrlAll ds))
                    (remove #(str/ends-with? % "/")))
          datasets (p/all (map #(-> (sc/getSolidDataset % (auth/opts))
                                    (p/catch (fn [e]
                                               (js/console.warn
                                                "Skipping unreadable post" % e)
                                               nil)))
                               urls))]
    (->> datasets
         (remove nil?)
         (mapcat posts-in-dataset)
         ;; remember where it came from: with several registered sources
         ;; merged into one timeline, "which container is this from?" stops
         ;; being obvious from the post itself
         (map #(assoc % :source container)))))

(defn- posts-in-document+
  "Posts held directly in one document, for a solid:instance
   registration — which names a document rather than a container."
  [url]
  (p/let [ds (sc/getSolidDataset url (auth/fresh-opts))]
    (->> (posts-in-dataset ds)
         (map #(assoc % :source url)))))

(defn load-posts+
  "Load all posts from one person's pod, as a vector of post maps,
   merged across every source their type index registers.

   Rejects only if **every** source failed. An unreadable pod and an
   empty one are different facts, and conflating them is how a transient
   502 becomes a silently empty timeline. But one unreadable source among
   several shouldn't blank the rest — another app's container may simply
   be private — so that is logged and the readable ones still count.
   Keeping one bad contact from breaking the whole timeline is the caller's
   job; see state/fetch-authors!."
  [webid]
  (p/let [{:keys [containers instances]} (post-sources+ webid)
          attempts (concat (map (fn [c] [c (posts-in-container+ c)]) containers)
                           (map (fn [i] [i (posts-in-document+ i)]) instances))
          results (p/all (map (fn [[source p]]
                                (-> p
                                    (p/then (fn [posts] {:posts posts}))
                                    (p/catch (fn [e] {:error e :source source}))))
                              attempts))]
    (let [failures (filter :error results)
          succeeded (remove :error results)]
      (doseq [{:keys [error source]} failures]
        (js/console.warn "Couldn't read posts from" source error))
      (if (and (seq failures) (empty? succeeded))
        (p/rejected (:error (first failures)))
        ;; the same post can be registered in more than one place
        (->> (mapcat :posts succeeded)
             (reduce (fn [acc post] (assoc acc (:id post) post)) {})
             vals
             vec)))))

(defn app-name+
  "What an app calls itself, read from the client identifier document a
   post names as its generator.

   Those documents have to be public — an identity provider dereferences
   one before it will log anyone in — so this resolves for *any* app that
   uses a published identity, not just ours. Nil when it can't be read or
   doesn't name itself; the caller falls back to a name from the URL.

   Deliberately a plain fetch rather than auth/auth-fetch. Sending the
   session's Authorization and DPoP headers would make this a request the
   browser has to preflight, and the host serving the document need not
   answer OPTIONS at all — GitHub Pages, where ours live, returns 405. A
   credential-free GET is a simple request, needs no preflight, and is
   all these documents ever expect."
  [client-id-url]
  (-> (p/let [resp (js/fetch client-id-url #js {:credentials "omit"})]
        (when (.-ok resp)
          (p/let [doc (.json resp)]
            (aget doc "client_name"))))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read app identity" client-id-url e)
                 nil))))

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
  (p/let [blob (sc/getFile url (auth/opts))]
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

(defn- post-thing
  "The post itself. as:generator records which app wrote it, pointing at
   this build's client identifier document — an identity the identity
   provider already dereferences, so it costs nothing to reuse.

   That matters once a pod's posts come from several apps: containers
   belong to the person, not to an app, so provenance has to travel with
   the post rather than being inferred from where it sits. Omitted when
   the build has no published identity, since a dynamically registered
   client is a different throwaway on every login and naming one would
   be worse than saying nothing.

   `mentions` are the as:Mention Things themselves, not their URLs.
   addUrl accepts a Thing, and while both are still local nodes the
   reference stays local too — so on save it serialises as <#mention-0>
   rather than the document's own address. That keeps the document
   location-independent: move it, or the container it sits in, and the
   tags still resolve. An absolute reference would go on naming where
   the post used to be, and mentions-of would quietly find nothing."
  [webid content published media-urls mentions]
  (as-> (-> (sc/createThing #js {:name "post"})
            (sc/addUrl sv/rdf-type v/as-Note)
            (sc/addStringNoLocale v/as-content content)
            (sc/addDatetime v/as-published published)
            (sc/addUrl v/as-attributedTo webid)) thing
    (cond-> thing
      (seq auth/client-id) (sc/addUrl v/as-generator auth/client-id))
    (reduce #(sc/addUrl %1 v/as-attachment %2) thing media-urls)
    (reduce #(sc/addUrl %1 v/as-tag %2) thing mentions)))

(defn- mention-thing
  "One as:Mention: who was mentioned, and the text that mentions them.

   Both halves are needed. The href is the only durable identifier — a
   display name can change, and is anyway just what the author's app
   happened to call them — while the name is what lets a reader find the
   mention in the content and highlight it in place. An app that ignores
   tags entirely still shows '@Alice' as ordinary text, which is the
   point of storing the literal rather than an offset."
  [i {:keys [webid label]}]
  (-> (sc/createThing #js {:name (str "mention-" i)})
      (sc/addUrl sv/rdf-type v/as-Mention)
      (sc/addUrl v/as-href webid)
      (sc/addStringNoLocale v/as-name label)))

(defn- register-posts-container!+
  "Advertise `container` as this person's home for as:Note in their
   public type index, so other apps can find these posts without knowing
   anything about our container names.

   Only ever adds a registration when the class is unclaimed: if some
   other app already registered a posts container, write-container+ has
   already resolved to theirs and we're writing there, so there is
   nothing to add. Best-effort — a pod with no type index, or one we
   can't write to, keeps working on the convention path."
  [webid container]
  (-> (p/let [ds (type-index+ webid)
              {:keys [containers]} (registrations+ webid v/as-Note)]
        (when (and ds (empty? containers))
          (p/let [reg (-> (sc/createThing #js {:name "podbay-comms-posts"})
                          (sc/addUrl sv/rdf-type v/solid-TypeRegistration)
                          (sc/addUrl v/solid-forClass v/as-Note)
                          (sc/addUrl v/solid-instanceContainer container))
                  index-url (sc/getSourceUrl ds)
                  _ (sc/saveSolidDatasetAt index-url (sc/setThing ds reg) (auth/opts))]
            ;; the cached copy is now a version behind
            (swap! type-index-cache dissoc webid))))
      ;; genuinely best-effort: publishing where posts live is a courtesy
      ;; to other apps, and must never fail the post itself
      (p/catch (fn [e]
                 (js/console.warn "Couldn't register the posts container" e)
                 nil))))

(defn- media-container
  "Attachments live in a media/ container *inside* the posts container,
   so they follow it wherever the type index points. Nesting rather than
   siblings is deliberate: access control in Solid is per-container, so
   this way a single grant on the posts container also covers the media
   those posts reference, instead of needing two."
  [posts-container]
  (str posts-container
       (when-not (str/ends-with? posts-container "/") "/")
       "media/"))

(defn save-post+
  "Upload any media files, then save the post as a new Turtle resource
   named by its timestamp. Resolves when the post is stored.

   `container` names where it goes; nil falls back to write-container+.
   `mentions` is {:webid :label} for each person named in the text, and
   may be empty.

   The post and its mentions are separate subjects in one document, so
   this is still a single request no matter how many people are named."
  [webid content files container mentions]
  ;; resolved in its own binding: p/let awaits a binding's *value*, so
  ;; as-container applied to write-container+ directly would receive the
  ;; promise itself and stringify it into the URL
  (p/let [chosen (or container (write-container+ webid))
          posts-url (as-container chosen)
          media-url (media-container posts-url)
          _ (ensure-container+ posts-url)
          _ (when (seq files) (ensure-container+ media-url))
          media-urls (p/all (map #(upload-file+ media-url %) files))
          now (js/Date.)
          slug (str/replace (.toISOString now) #"[:.]" "-")
          doc-url (str posts-url slug ".ttl")
          ;; built before the post so it can reference them directly;
          ;; they stay local nodes until save, which is what makes the
          ;; tags come out relative
          mention-things (map-indexed mention-thing mentions)
          ds (reduce sc/setThing
                     (sc/setThing (sc/createSolidDataset)
                                  (post-thing webid content now media-urls
                                              mention-things))
                     mention-things)
          saved (sc/saveSolidDatasetAt doc-url ds (auth/opts))]
    ;; publish where these posts live, once the container definitely exists
    (register-posts-container!+ webid posts-url)
    saved))

;; ---------------------------------------------------------------------------
;; Contacts

(defn- contacts-url+ [webid]
  (p/let [root (pod-root+ webid)]
    (str root app-path "contacts.ttl")))

(defn load-contacts+
  "Resolves to a vector of followed WebIDs (empty if none saved yet)."
  [webid]
  (-> (p/let [url (contacts-url+ webid)
              ;; rewritten whenever you follow or unfollow someone, so
              ;; never serve this from cache
              ds (sc/getSolidDataset url (auth/fresh-opts))
              thing (sc/getThing ds (str url "#me"))]
        (if thing
          (vec (array-seq (sc/getUrlAll thing v/as-following)))
          []))
      ;; no contacts document yet is the normal state before you follow
      ;; anyone. Any other failure must not silently unfollow everyone.
      (p/catch (fn [e]
                 (if (missing? e) [] (p/rejected e))))))

;; ---------------------------------------------------------------------------
;; Follow requests
;;
;; Notification only. A follower finds what they may read from their own
;; shared-with document, so nothing here is load-bearing: an inbox that
;; doesn't exist, or a request that never arrives, costs a conversation
;; rather than the feature. That is deliberate — an earlier design had
;; discovery depend on this, which made an inbox a prerequisite for
;; every participant.
;;
;; as:Follow / as:Accept is ActivityPub's own model, so a request left
;; here is legible to anything else that speaks it.

(defn inbox-url+
  "Where to leave this person a notification, or nil if they don't
   advertise one. Not an error: plenty of pods have no inbox, and ESS
   doesn't create one."
  [webid]
  (-> (p/let [thing (profile-thing+ webid)]
        (when thing (sc/getUrl thing sv/ldp-inbox)))
      (p/catch (fn [_] nil))))

(defn request-follow+
  "Leave an as:Follow in someone's inbox.

   Best-effort by design. Resolves to :sent, :no-inbox, or :refused —
   never rejects — because following someone works regardless: this only
   saves you having to tell them yourself. POSTing creates a new
   resource in the container, which is what an inbox is for; we can't
   read it back afterwards, since Append is not Read."
  [me target]
  (-> (p/let [inbox (inbox-url+ target)]
        (if-not inbox
          :no-inbox
          (p/let [thing (-> (sc/createThing #js {:name "it"})
                            (sc/addUrl sv/rdf-type v/as-Follow)
                            (sc/addUrl v/as-actor me)
                            (sc/addUrl v/as-object target)
                            (sc/addDatetime v/as-published (js/Date.)))
                  ds (sc/setThing (sc/createSolidDataset) thing)
                  _ (sc/saveSolidDatasetInContainer inbox ds (auth/opts))]
            :sent)))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't leave a follow request for" target e)
                 :refused))))

(defn- thing->request [url thing]
  (when (some #(= % v/as-Follow) (array-seq (sc/getUrlAll thing sv/rdf-type)))
    {:url url
     :actor (sc/getUrl thing v/as-actor)
     :object (sc/getUrl thing v/as-object)
     :published (sc/getDatetime thing v/as-published)}))

(defn follow-requests+
  "Follow requests sitting in your own inbox, newest first.

   Only entries typed as:Follow and addressed to you — an inbox anyone
   may append to will collect other things, and a request naming someone
   else is not yours to answer. One unreadable entry is skipped rather
   than hiding the rest."
  [webid]
  (-> (p/let [inbox (inbox-url+ webid)]
        (if-not inbox
          []
          (p/let [ds (sc/getSolidDataset inbox (auth/fresh-opts))
                  urls (->> (array-seq (sc/getContainedResourceUrlAll ds))
                            (remove #(str/ends-with? % "/")))
                  entries (p/all
                           (map (fn [url]
                                  (-> (p/let [d (sc/getSolidDataset url (auth/opts))]
                                        (->> (array-seq (sc/getThingAll d))
                                             (keep #(thing->request url %))
                                             first))
                                      (p/catch (fn [e]
                                                 (js/console.warn
                                                  "Skipping unreadable inbox entry" url e)
                                                 nil))))
                                urls))]
            (->> entries
                 (remove nil?)
                 (filter #(= webid (:object %)))
                 (sort-by #(if-let [d (:published %)] (- (.getTime d)) 0))
                 vec))))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't read your inbox" e)
                 []))))

(defn dismiss-request+
  "Delete a request once it has been acted on. Tolerates it being gone
   already, which is the desired state either way."
  [url]
  (-> (sc/deleteSolidDataset url (auth/opts))
      (p/catch (fn [e]
                 (when-not (missing? e)
                   (js/console.warn "Couldn't remove" url e))
                 nil))))

(defn accept-follow+
  "Tell someone their request was granted, so their app can stop saying
   'requested' without waiting for a refresh. A courtesy: they'd find
   the grant anyway through their shared-with document."
  [me follower]
  (-> (p/let [inbox (inbox-url+ follower)]
        (when inbox
          (p/let [thing (-> (sc/createThing #js {:name "it"})
                            (sc/addUrl sv/rdf-type v/as-Accept)
                            (sc/addUrl v/as-actor me)
                            (sc/addUrl v/as-object follower)
                            (sc/addDatetime v/as-published (js/Date.)))
                  ds (sc/setThing (sc/createSolidDataset) thing)]
            (sc/saveSolidDatasetInContainer inbox ds (auth/opts))
            :sent)))
      (p/catch (fn [e]
                 (js/console.warn "Couldn't confirm to" follower e)
                 nil))))

;; ---------------------------------------------------------------------------
;; What each follower may read
;;
;; One small document per follower, granted to them alone, listing the
;; audience containers they can read. A follower derives its location
;; from their own WebID, so nothing has to tell them where it is — which
;; is what keeps notification out of the discovery path. See
;; docs/following.md.
;;
;; Named for its reader rather than hashed: only the owner and that one
;; follower can ever see the name, so legibility wins. Percent-encoded,
;; Airlock decodes it back to the plain WebID when browsing.
;;
;; The container itself must NEVER be granted read — only the files in
;; it. Granting on the container would hand every follower the complete
;; list of everyone else who follows you.

(defn- shared-with-dir+ [owner]
  (p/let [root (pod-root+ owner)]
    (str root app-path "shared-with/")))

(defn follower-filename
  "The name of the document recording what one follower may read.

   The WebID with anything outside RFC 3986's *unreserved* set —
   `A-Za-z0-9-._~` — escaped as `_` plus hex. So `_2F` is a slash and
   `_3A` a colon:

     https://id.inrupt.com/alice
     https_3A_2F_2Fid.inrupt.com_2Falice.ttl

   Cluttered, but you can read whose it is, which matters when browsing
   your own pod.

   **Why not percent-encoding**, which was tried first: Community Solid
   Server decoded `%2F` when creating the resource while the ACL kept
   the name we asked for, leaving the file governed by an authorisation
   naming a URL that didn't exist — unmanageable by anyone, including
   its owner. The unreserved set is the one a conforming server must
   leave alone, because escaping those characters is *defined* as
   equivalent to not escaping them. Emit only those and there is
   nothing to normalise.

   Injective, which is the property that actually matters: two WebIDs
   never collide, so one follower's record can't overwrite another's.
   Not reversible, and it needn't be — this derives a *location*, while
   identity is read from foaf:primaryTopic inside the document, the one
   thing a server can't rename."
  [follower]
  (str (str/replace follower
                    #"[^A-Za-z0-9.~-]"
                    (fn [c]
                      (str "_" (-> (.charCodeAt c 0)
                                   (.toString 16)
                                   (.toUpperCase)
                                   (.padStart 2 "0")))))
       ".ttl"))

(defn- shared-with-url+ [owner follower]
  (p/let [dir (shared-with-dir+ owner)]
    (str dir (follower-filename follower))))

(defn granted-containers+
  "Which of `owner`'s audience containers `follower` may read, according
   to the document the owner wrote for them.

   Empty when there is no such document — which is the normal state for
   someone who hasn't been granted anything, and indistinguishable from
   being refused. Both mean 'nothing for you here', so neither is an
   error."
  [owner follower]
  (-> (p/let [url (shared-with-url+ owner follower)
              ;; rewritten on every grant and revoke, so never cached
              ds (sc/getSolidDataset url (auth/fresh-opts))
              thing (sc/getThing ds url)]
        (if thing
          (vec (array-seq (sc/getUrlAll thing v/solid-instanceContainer)))
          []))
      (p/catch (fn [_] []))))

(defn- write-shared-with+
  "Record what one follower may read, and make sure they can read the
   record. An empty list deletes the document rather than leaving an
   empty one behind: revocation should look the same as never having
   been granted, and to the person refused it does — a missing resource
   and a forbidden one come back identically."
  [owner follower containers]
  (p/let [url (shared-with-url+ owner follower)]
    (if (empty? containers)
      (-> (sc/deleteSolidDataset url (auth/opts))
          (p/catch (fn [e]
                     ;; already gone is the desired state
                     (when-not (missing? e)
                       (js/console.warn "Couldn't remove" url e))
                     nil)))
      (p/let [existing (-> (sc/getSolidDataset url (auth/fresh-opts))
                           (p/catch (fn [e] (if (missing? e) nil (p/rejected e)))))
              base (or existing (sc/createSolidDataset))
              ;; replace rather than amend: this is the whole list
              cleared (cond-> base existing (sc/removeThing url))
              thing (reduce (fn [th c] (sc/addUrl th v/solid-instanceContainer c))
                            ;; the WebID is recorded *in* the document, not
                            ;; left implicit in its name. The name is how a
                            ;; follower finds this, but a server may rewrite
                            ;; percent-escapes in a resource name, so the
                            ;; filename is not a reliable identifier to read
                            ;; back — only to look up.
                            (-> (sc/createThing #js {:url url})
                                (sc/addUrl v/foaf-primaryTopic follower))
                            containers)
              _ (sc/saveSolidDatasetAt url (sc/setThing cleared thing) (auth/opts))]
        ;; the document is no use to them if they can't read it
        (access/set-agent-access+ url follower {:read true})))))

(defn followers+
  "Everyone this person has granted an audience to, as
   [{:webid :containers}].

   Read from the shared-with container itself: one document per
   follower, named after them. That listing *is* the record, so there is
   no separate roster to keep in step with the grants."
  [owner]
  (-> (p/let [dir (shared-with-dir+ owner)
              ds (sc/getSolidDataset dir (auth/fresh-opts))
              urls (->> (array-seq (sc/getContainedResourceUrlAll ds))
                        (remove #(str/ends-with? % "/")))
              entries (p/all
                       (map (fn [url]
                              ;; read the document rather than trusting its
                              ;; name: the WebID inside is what we wrote,
                              ;; the name is whatever the server kept
                              (-> (p/let [ds (sc/getSolidDataset url (auth/fresh-opts))
                                          thing (sc/getThing ds url)]
                                    ;; only the document knows whose it is;
                                    ;; its name is a lookup key, not an
                                    ;; identifier, and a server may rewrite it
                                    {:webid (some-> thing
                                                    (sc/getUrl v/foaf-primaryTopic))
                                     :containers (if thing
                                                   (vec (array-seq
                                                         (sc/getUrlAll
                                                          thing
                                                          v/solid-instanceContainer)))
                                                   [])})
                                  (p/catch (fn [e]
                                             (js/console.warn
                                              "Skipping unreadable follower record"
                                              url e)
                                             nil))))
                            urls))]
        (vec (sort-by :webid (filter :webid (remove nil? entries)))))
      ;; no shared-with container yet means you have granted nobody
      (p/catch (fn [e]
                 (when-not (missing? e)
                   (js/console.warn "Couldn't read your followers" e))
                 []))))

(defn grant-follower+
  "Let one person read one audience: access on the container, access
   inherited by its contents, and a line in their shared-with document
   so they can find it.

   Read only, never control. Three writes, and the third is what makes
   the first two discoverable — a grant nobody can find does nothing."
  [owner follower container]
  (p/let [_ (access/set-agent-access+ container follower {:read true})
          _ (access/set-inherited-access+ container follower {:read true})
          existing (granted-containers+ owner follower)
          containers (vec (distinct (conj (vec existing) container)))
          _ (write-shared-with+ owner follower containers)]
    containers))

(defn revoke-follower+
  "Take one audience back from one person. Their shared-with document
   loses that container, and is deleted entirely if it was the last."
  [owner follower container]
  (p/let [_ (access/set-agent-access+ container follower
                                     {:read false :append false :write false})
          _ (access/set-inherited-access+ container follower
                                          {:read false :append false :write false})
          existing (granted-containers+ owner follower)
          containers (vec (remove #{container} existing))
          _ (write-shared-with+ owner follower containers)]
    containers))

(defn- audiences-url+ [webid]
  (p/let [root (pod-root+ webid)]
    (str root app-path "audiences.ttl")))

;; ---------------------------------------------------------------------------
;; Audiences
;;
;; A container per audience, created by this app, with what the user
;; calls it recorded here rather than in the container's name.
;;
;; The name is deliberately opaque. A follower is told the URL of the
;; container they've been granted, so a container called `acquaintances`
;; tells them what you think of them; and the names together publish the
;; shape of someone's relationships to anyone who can list the parent.
;;
;; This manifest is also what makes "Comms may only grant access on
;; containers it created" checkable rather than guessed. A naming
;; convention would do neither: names are user-visible and can be
;; renamed in a file browser, at which point a convention silently
;; starts pointing at the wrong thing.

(defn short-container-name
  "A container as a short, recognisable path — the last couple of
   segments. All that can be said about a container this app didn't
   create and has no label for."
  [url]
  (try
    (->> (str/split (.-pathname (js/URL. url)) #"/")
         (remove str/blank?)
         (take-last 2)
         (str/join "/"))
    (catch :default _ url)))

(defn audience-parent
  "The container an audience sits in — this app's own posts container.
   Not somewhere to file another audience."
  [container]
  (let [trimmed (cond-> container
                  (str/ends-with? container "/") (subs 0 (dec (count container))))
        cut (str/last-index-of trimmed "/")]
    (when cut (subs trimmed 0 (inc cut)))))

(defn- audience-slug
  "An opaque container name. Short enough to read back in a URL, random
   enough that two audiences created in the same second don't collide."
  []
  (subs (str (random-uuid)) 0 8))

(defn- thing->audience [thing]
  (when-let [container (sc/getUrl thing v/solid-instanceContainer)]
    {:id (last (str/split (sc/asUrl thing) #"#"))
     :label (or (sc/getStringNoLocale thing v/dcterms-title) "Untitled")
     :container container}))

(defn load-audiences+
  "The audiences this person has, in the order they were created.
   Empty when none exist yet, which is the normal state before the
   first one is made."
  [webid]
  (-> (p/let [url (audiences-url+ webid)
              ;; rewritten whenever an audience is added or renamed, so
              ;; never serve this from cache
              ds (sc/getSolidDataset url (auth/fresh-opts))]
        (->> (array-seq (sc/getThingAll ds))
             (keep thing->audience)
             vec))
      ;; no manifest yet is the normal state. Any other failure must not
      ;; masquerade as "you have no audiences", which would invite
      ;; creating duplicates of the ones already there.
      (p/catch (fn [e]
                 (if (missing? e) [] (p/rejected e))))))

(defn- save-audiences+
  "Overwrite the manifest with this list.

   Fetches first, for the reason save-contacts+ does: a dataset built
   from scratch takes saveSolidDatasetAt's *creation* path, which sends
   If-None-Match and so succeeds exactly once. Existing audiences are
   removed rather than amended, since this is a replacement — otherwise
   renaming one would leave both names behind."
  [webid audiences]
  (p/let [url (audiences-url+ webid)
          existing (-> (sc/getSolidDataset url (auth/fresh-opts))
                       (p/catch (fn [e] (if (missing? e) nil (p/rejected e)))))
          base (or existing (sc/createSolidDataset))
          cleared (reduce (fn [ds thing]
                            (if (sc/getUrl thing v/solid-instanceContainer)
                              (sc/removeThing ds thing)
                              ds))
                          base
                          (array-seq (sc/getThingAll base)))
          ds (reduce (fn [ds {:keys [id label container]}]
                       (sc/setThing ds (-> (sc/createThing #js {:name id})
                                           (sc/addStringNoLocale v/dcterms-title label)
                                           (sc/addUrl v/solid-instanceContainer container))))
                     cleared
                     audiences)]
    (sc/saveSolidDatasetAt url ds (auth/opts))
    audiences))

(defn create-audience+
  "Make a new audience: a container under this app's own path, plus its
   entry in the manifest.

   **Not** beneath a registered posts container, which an earlier
   version did. A registered container may already be shared — that is
   the whole point of an audience — and access inherits downwards, so a
   new audience created inside one is born readable by whoever could
   read its parent. A brand-new audience must start private, and the
   only place this app can be sure of that is a container it owns
   beneath the storage root, whose access is the pod's default.

   The type index is what makes an audience discoverable, not where it
   sits, so nothing is lost by not nesting it near the older posts."
  [webid label]
  (p/let [root (pod-root+ webid)
          parent (str root app-path "posts/")
          _ (ensure-container+ parent)
          slug (audience-slug)
          container (str parent slug "/")
          _ (ensure-container+ container)
          existing (load-audiences+ webid)]
    (save-audiences+ webid
                     (conj (vec existing)
                           {:id slug :label label :container container}))))

(defn adopt-audience+
  "Record a container that already exists as an audience.

   For a posts container that predates this app. Deliberately explicit:
   adopting says 'Comms may grant access on this', which is not
   something to assume about a container someone else's app may also be
   writing to."
  [webid label container]
  (p/let [existing (load-audiences+ webid)]
    (if (some #(= container (:container %)) existing)
      existing
      (save-audiences+ webid
                       (conj (vec existing)
                             {:id (audience-slug)
                              :label label
                              :container (as-container container)})))))

(defn forget-audience+
  "Drop an audience from the manifest. Leaves the container and its
   posts alone — this says 'Comms no longer manages this', not 'delete
   my posts'. Removing the container itself is a file-browser job, where
   it is obvious what is being destroyed."
  [webid container]
  (p/let [existing (load-audiences+ webid)]
    (save-audiences+ webid (vec (remove #(= container (:container %)) existing)))))

(defn save-contacts+
  "Overwrite the contacts resource with the given list of WebIDs.

   The existing document is fetched first, and not merely out of
   politeness. saveSolidDatasetAt decides between creating and updating
   by asking whether the dataset it was handed came from the server: a
   freshly built one takes the creation path, which sends
   `If-None-Match: *` — 'only if this doesn't exist yet'. That succeeds
   exactly once. Every write after the first is then refused with a 412,
   so the very first person you unfollowed could never be removed.

   The old #me is dropped rather than amended, because this is an
   overwrite: adding to what's there would make unfollowing impossible,
   since as:following values would only ever accumulate. The
   replacement is built as a local node so it still serialises as
   <#me>."
  [webid contact-webids]
  (p/let [url (contacts-url+ webid)
          existing (-> (sc/getSolidDataset url (auth/fresh-opts))
                       ;; no document yet is the normal state before you
                       ;; follow anyone; anything else is a real failure
                       ;; and must not be papered over with a create
                       (p/catch (fn [e] (if (missing? e) nil (p/rejected e)))))
          thing (reduce (fn [t c] (sc/addUrl t v/as-following c))
                        (sc/createThing #js {:name "me"})
                        contact-webids)
          ds (-> (or existing (sc/createSolidDataset))
                 (cond-> existing (sc/removeThing (str url "#me")))
                 (sc/setThing thing))]
    (sc/saveSolidDatasetAt url ds (auth/opts))))
