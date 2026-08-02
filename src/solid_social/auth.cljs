(ns solid-social.auth
  "Solid-OIDC authentication. All of this runs in the browser — the app
   never sees credentials, it just gets a session whose `fetch` attaches
   the right tokens to requests against pods."
  (:require ["@inrupt/solid-client-authn-browser" :as authn]
            [clojure.string :as str]))

(def auth-fetch
  "A fetch function bound to the current session. Pass this to
   @inrupt/solid-client calls so requests are authenticated."
  authn/fetch)

(defn- session-info []
  (.-info ^js (authn/getDefaultSession)))

(defn logged-in? []
  (boolean (.-isLoggedIn ^js (session-info))))

(defn web-id []
  (.-webId ^js (session-info)))

(defn login!
  "Redirects the browser to the user's Solid identity provider.
   Control returns to this page afterwards via the OIDC redirect."
  [oidc-issuer]
  (authn/login #js {:oidcIssuer oidc-issuer
                    :redirectUrl (.. js/window -location -href)
                    :clientName "Solid Social"}))

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
