(ns solid-social.auth
  "Solid-OIDC authentication. All of this runs in the browser — the app
   never sees credentials, it just gets a session whose `fetch` attaches
   the right tokens to requests against pods."
  (:require ["@inrupt/solid-client-authn-browser" :as authn]))

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
   Returns a promise of the session info."
  []
  (authn/handleIncomingRedirect #js {:restorePreviousSession true}))
