(ns podbay.shared.auth
  "Solid-OIDC authentication. All of this runs in the browser — the app
   never sees credentials, it just gets a session whose `fetch` attaches
   the right tokens to requests against pods.

   Each app gets its **own** session with its own storage, rather than
   the library's default one. That matters because localStorage is
   scoped to an origin, not a path: two apps deployed under one origin
   would otherwise share a single session, and whichever loaded second
   would silently re-authenticate using the other's redirect URL and
   land the user in the wrong app. Set storage-prefix per build."
  (:require ["@inrupt/solid-client-authn-browser" :as authn]
            [clojure.string :as str]
            [promesa.core :as p]))

(goog-define storage-prefix "podbay:")

(defn- prefixed-storage
  "The tiny storage interface the library asks for — get/set/delete,
   each returning a promise — backed by localStorage under our prefix."
  []
  #js {:get (fn [k] (p/resolved
                     (or (.getItem js/localStorage (str storage-prefix k))
                         js/undefined)))
       :set (fn [k v] (p/resolved (.setItem js/localStorage (str storage-prefix k) v)))
       :delete (fn [k] (p/resolved (.removeItem js/localStorage (str storage-prefix k))))})

(defonce ^:private session
  (authn/Session. #js {:secureStorage (prefixed-storage)
                       :insecureStorage (prefixed-storage)}))

(def ^:private library-prefix
  "Where solid-client-authn-browser keeps its own session state, outside
   our namespaced storage."
  "solidClientAuthn:")

(def ^:private library-pointer
  "The one key the library reads with raw localStorage to decide which
   session to restore. It is not routed through the storage we supply,
   so on a shared origin both apps would otherwise fight over it."
  (str library-prefix "currentSession"))

(defn- our-pointer [] (str storage-prefix "currentSession"))

;; ---------------------------------------------------------------------------
;; Talking to pods
;;
;; Pod servers are not always reliable. solidcommunity.net in particular
;; intermittently answers 502 through its CDN, including on CORS
;; preflights — and a failed preflight surfaces in the browser as a bare
;; "TypeError: Failed to fetch" plus a console message blaming CORS,
;; which looks like a configuration error rather than the blip it is.
;; Retrying absorbs those, and is very likely why the third-party pod
;; browsers feel flaky too: they report the failure instead of retrying.

(def ^:private retry-statuses
  "Transient by nature: a gateway that couldn't reach the origin, an
   overloaded server, or a rate limit. Never 4xx — those are answers."
  #{429 500 502 503 504})

(def ^:private retry-methods
  "Only methods that can be repeated without changing the outcome.
   POST creates something new on each attempt, so it is never retried.

   PATCH is retried, having originally been excluded on the reasoning
   that a patch may not survive being applied twice. That is true of
   SPARQL in general and not of anything sent from here: solid-client
   emits `DELETE DATA` and `INSERT DATA` and nothing else, and both are
   idempotent — deleting an absent triple is a no-op, inserting a
   present one likewise. So a retry after a response we never saw is
   safe even if the first attempt actually landed.

   The exclusion was costly, because PATCH is what updates an *existing*
   document: the contacts list, the audience manifest, a follower's
   record. Every other operation absorbed a flaky server's 502 and those
   silently did not, which on solidcommunity.net meant a lost write.
   Revisit this if solid-client ever emits a conditional patch form,
   where reapplying would not be a no-op."
  #{"GET" "HEAD" "OPTIONS" "PUT" "PATCH" "DELETE"})

(def ^:private read-methods
  "Methods for which falling back to an anonymous request makes sense.
   A write the server refused our credentials for will not succeed
   without them either."
  #{"GET" "HEAD" "OPTIONS"})

(def ^:private max-attempts
  "Four, not three. solidcommunity.net has been observed needing several
   goes before answering, and three attempts spans only 1.2s of backoff
   — enough for a blip, not for the sustained flakiness actually seen.
   Only failing requests pay the extra wait."
  4)

(defn- method-of [init]
  (str/upper-case (or (some-> ^js init .-method) "GET")))

(defn- without-credentials
  "The same request with no session attached. `init` never carries the
   Authorization header — the session's fetch adds that itself — so the
   plain fetch is unauthenticated by construction."
  [init]
  (js/Object.assign #js {} (or init #js {}) #js {:credentials "omit"}))

(defn- backoff-ms [attempt]
  ;; 300ms then 900ms, plus jitter so a fan-out of parallel requests
  ;; doesn't retry in lockstep and re-create the spike
  (+ (* 300 (js/Math.pow 3 (dec attempt)))
     (rand-int 200)))

(defn- pause+ [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(declare logged-in?)

(defn auth-fetch
  "A fetch bound to the current session, retrying transient failures and
   falling back to an anonymous request when our credentials are refused.

   Pass this to @inrupt/solid-client calls so requests are
   authenticated. Retries are silent and capped; a request that keeps
   failing still rejects, and any 4xx other than 401 comes straight back
   untouched.

   **The 401 fallback.** Solid means reading pods on servers you have no
   account with, and a token is only meaningful to the provider that
   issued it. Inrupt's servers reject an Authorization header they can't
   validate with a 401 — even for resources that are world-readable
   anonymously — so a session on solidcommunity.net can otherwise read
   nothing at all on an ESS pod: not the WebID document, not the public
   profile, not a public type index. Once credentials have been refused
   the only access left is public, so this asks again without them.

   Safe in the cases it doesn't help: a private resource answers 401
   again, and the caller sees the same failure one round trip later. It
   can't mask an expired session either, since that too still 401s on
   anything actually private. Reads only — see read-methods."
  ([url] (auth-fetch url nil))
  ([url init]
   (let [method (method-of init)
         repeatable? (contains? retry-methods method)]
     (letfn [(retry-or [n fallback]
               (if (and repeatable? (< n max-attempts))
                 (p/let [_ (pause+ (backoff-ms n))]
                   (attempt (inc n)))
                 (fallback)))
             (attempt [n]
               (-> (.fetch ^js session url init)
                   (p/then (fn [^js resp]
                             (if (contains? retry-statuses (.-status resp))
                               (retry-or n (constantly resp))
                               resp)))
                   (p/catch (fn [err]
                              ;; a rejection means the request never
                              ;; reached the server at all — a dropped
                              ;; connection or a preflight that 502'd
                              (retry-or n #(p/rejected err))))))
             (anonymously [^js resp]
               (if (and (= 401 (.-status resp))
                        (contains? read-methods method)
                        ;; logged out, the session fetch was already
                        ;; anonymous and this would just repeat it
                        (logged-in?))
                 (-> (js/fetch url (without-credentials init))
                     ;; the unauthenticated attempt failing outright
                     ;; tells the caller nothing the 401 didn't
                     (p/catch (constantly resp)))
                 resp))]
       (p/then (attempt 1) anonymously)))))

(defn opts
  "Options for @inrupt/solid-client calls: our retrying, session-bound
   fetch. Lives here rather than in each app so there is one definition
   of what talking to a pod means."
  []
  #js {:fetch auth-fetch})

;; Pod responses carry no Cache-Control but do carry Last-Modified,
;; which makes them *heuristically* cacheable: the browser may reuse one
;; for a while without asking. That's wrong for anything we might have
;; just changed — a listing fetched right after a delete would still
;; contain the deleted file, and an access control resource read straight
;; after a grant would report the rules as they were.
;;
;; "no-cache" rather than "no-store": the response is still cached, but
;; every read revalidates against the server, which answers 304 Not
;; Modified when nothing has changed. Correctness without throwing the
;; cache away — pods supply both ETag and Last-Modified, so revalidation
;; is cheap.
(defn revalidate-init
  "Add no-cache to a request init, for a one-off fetch that must not be
   served from cache."
  [init]
  (js/Object.assign #js {} (or init #js {}) #js {:cache "no-cache"}))

(defn revalidating-fetch [url init]
  (auth-fetch url (revalidate-init init)))

(defn fresh-opts
  "Options for a read that must not come from cache — anything we may
   have just written, or are about to."
  []
  #js {:fetch revalidating-fetch})

(defn- session-info []
  (.-info ^js session))

(defn logged-in? []
  (boolean (.-isLoggedIn ^js (session-info))))

(defn web-id []
  (.-webId ^js (session-info)))

(goog-define client-id "")

;; Only used when registering dynamically — with a client identifier
;; document the provider takes the name from there instead. Shared code
;; can't know which app it is, so each build says.
(goog-define client-name "Podbay")

(defn- redirect-url
  "Where the identity provider sends the browser back to. Deliberately
   built from origin + path rather than the current href: with a client
   identifier this has to match an entry in the client document exactly,
   and a stray query string or fragment would break that match."
  []
  (str (.-origin js/location) (.-pathname js/location)))

(defn login!
  "Redirects the browser to the user's Solid identity provider.
   Control returns to this page afterwards via the OIDC redirect.

   With `client-id` set to the URL of a client identifier document, the
   app has a stable published identity. Left empty (the default), the
   library registers a throwaway client with the provider on every
   login instead — fine for local work, but those registrations expire
   and a stale one locks you out (see recover-from-url! below)."
  [oidc-issuer]
  (.login ^js session
   (clj->js (cond-> {:oidcIssuer oidc-issuer
                     :redirectUrl (redirect-url)
                     :clientName client-name}
              (seq client-id) (assoc :clientId client-id)))))

(defn logout! []
  (-> (.logout ^js session)
      (p/then (fn [r] (.removeItem js/localStorage (str storage-prefix "currentSession")) r))))

(defn handle-redirect!
  "Completes the OIDC flow if we just came back from the identity
   provider, and silently restores a previous session on plain reloads.
   Returns a promise of the session info.

   Note that the silent restore navigates the whole page to the identity
   provider (with prompt=none) and back. While that is in flight this
   promise never settles — the library returns one that deliberately
   never resolves, because the page is about to be replaced."
  []
  ;; Point the library at *our* session before it looks, and record ours
  ;; again afterwards. Without this the two apps overwrite one shared
  ;; key and the loser silently stops restoring.
  (if-let [ours (.getItem js/localStorage (our-pointer))]
    (.setItem js/localStorage library-pointer ours)
    (.removeItem js/localStorage library-pointer))
  (-> (.handleIncomingRedirect ^js session #js {:restorePreviousSession true})
      (p/then (fn [info]
                (when (logged-in?)
                  (.setItem js/localStorage (our-pointer)
                            (.-sessionId ^js (session-info))))
                info))))

;; ---------------------------------------------------------------------------
;; Recovery
;;
;; The silent restore above is a full page navigation, which makes a broken
;; session self-perpetuating: if the provider rejects the request (an
;; expired dynamic client registration returns a bare 400 rather than
;; redirecting back), the browser is left on the provider's error page,
;; and the next load attempts the very same request. The app never renders
;; long enough to offer a way out, so the way out has to be in the URL.

(defn forget-session!
  "Delete the locally stored Solid session, so the next load starts from
   a clean login instead of retrying a session the provider won't honour."
  []
  (let [store (.-localStorage js/window)]
    ;; walk backwards: removing a key shifts the ones after it
    (doseq [i (reverse (range (.-length store)))
            :let [k (.key store i)]
            :when (and k (or (str/starts-with? k library-prefix)
                             (str/starts-with? k storage-prefix)))]
      (.removeItem store k))))

(defn recover-from-url!
  "Escape hatch: loading the app with ?reset throws the stored session
   away before any restore is attempted. Returns true if it fired."
  []
  (when (str/includes? (.-search js/location) "reset")
    (forget-session!)
    ;; drop the flag again so it can't leak into a later OIDC redirect URL
    (.replaceState js/history nil "" (.-pathname js/location))
    true))
