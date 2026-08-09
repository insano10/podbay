(ns solid-social.auth
  "Solid-OIDC authentication. All of this runs in the browser — the app
   never sees credentials, it just gets a session whose `fetch` attaches
   the right tokens to requests against pods."
  (:require ["@inrupt/solid-client-authn-browser" :as authn]
            [clojure.string :as str]
            [promesa.core :as p]))

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
   POST creates something new each time, and PATCH may not survive being
   applied twice, so neither is retried."
  #{"GET" "HEAD" "OPTIONS" "PUT" "DELETE"})

(def ^:private max-attempts 3)

(defn- method-of [init]
  (str/upper-case (or (some-> ^js init .-method) "GET")))

(defn- backoff-ms [attempt]
  ;; 300ms then 900ms, plus jitter so a fan-out of parallel requests
  ;; doesn't retry in lockstep and re-create the spike
  (+ (* 300 (js/Math.pow 3 (dec attempt)))
     (rand-int 200)))

(defn- pause+ [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn auth-fetch
  "A fetch bound to the current session, retrying transient failures.

   Pass this to @inrupt/solid-client calls so requests are
   authenticated. Retries are silent and capped; a request that keeps
   failing still rejects, and any 4xx comes straight back untouched."
  ([url] (auth-fetch url nil))
  ([url init]
   (let [repeatable? (contains? retry-methods (method-of init))]
     (letfn [(retry-or [n fallback]
               (if (and repeatable? (< n max-attempts))
                 (p/let [_ (pause+ (backoff-ms n))]
                   (attempt (inc n)))
                 (fallback)))
             (attempt [n]
               (-> (authn/fetch url init)
                   (p/then (fn [^js resp]
                             (if (contains? retry-statuses (.-status resp))
                               (retry-or n (constantly resp))
                               resp)))
                   (p/catch (fn [err]
                              ;; a rejection means the request never
                              ;; reached the server at all — a dropped
                              ;; connection or a preflight that 502'd
                              (retry-or n #(p/rejected err))))))]
       (attempt 1)))))

(defn- session-info []
  (.-info ^js (authn/getDefaultSession)))

(defn logged-in? []
  (boolean (.-isLoggedIn ^js (session-info))))

(defn web-id []
  (.-webId ^js (session-info)))

(goog-define client-id "")

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
  (authn/login
   (clj->js (cond-> {:oidcIssuer oidc-issuer
                     :redirectUrl (redirect-url)
                     :clientName "Solid Social"}
              (seq client-id) (assoc :clientId client-id)))))

(defn logout! []
  (authn/logout))

(defn handle-redirect!
  "Completes the OIDC flow if we just came back from the identity
   provider, and silently restores a previous session on plain reloads.
   Returns a promise of the session info.

   Note that the silent restore navigates the whole page to the identity
   provider (with prompt=none) and back. While that is in flight this
   promise never settles — the library returns one that deliberately
   never resolves, because the page is about to be replaced."
  []
  (authn/handleIncomingRedirect #js {:restorePreviousSession true}))

;; ---------------------------------------------------------------------------
;; Recovery
;;
;; The silent restore above is a full page navigation, which makes a broken
;; session self-perpetuating: if the provider rejects the request (an
;; expired dynamic client registration returns a bare 400 rather than
;; redirecting back), the browser is left on the provider's error page,
;; and the next load attempts the very same request. The app never renders
;; long enough to offer a way out, so the way out has to be in the URL.

(def ^:private storage-prefix
  "Where solid-client-authn-browser keeps its session state."
  "solidClientAuthn:")

(defn forget-session!
  "Delete the locally stored Solid session, so the next load starts from
   a clean login instead of retrying a session the provider won't honour."
  []
  (let [store (.-localStorage js/window)]
    ;; walk backwards: removing a key shifts the ones after it
    (doseq [i (reverse (range (.-length store)))
            :let [k (.key store i)]
            :when (and k (str/starts-with? k storage-prefix))]
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
