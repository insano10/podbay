# Solid Social

A lightweight social feed over [Solid](https://solidproject.org/) pods.
You write posts (text plus photos/videos) into **your own pod**, follow
friends by their WebIDs, and see everyone's posts merged into one
chronological feed.

There is deliberately no backend: this is a static site. Authentication
(Solid-OIDC) and all data access happen in the browser, directly against
each person's pod. No ads, no algorithm, no tracking — the app is just a
view over data you and your friends chose to link.

Built with [ClojureScript](https://clojurescript.org/),
[shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) and
[Reagent](https://reagent-project.github.io/).

## Prerequisites

- Node.js (20+) and npm
- A JVM (Java **21+**) — shadow-cljs 3.x requires it, and 3.x is not
  optional here (see [Build tooling](#build-tooling) below).
- The [Clojure CLI](https://clojure.org/guides/install_clojure) — the
  JVM classpath comes from `deps.edn`.

## Development

```sh
npm install
npm run dev
```

Then open <http://localhost:8080>. If that port is taken, shadow-cljs
picks the next free one — check its startup output. shadow-cljs
hot-reloads code on save.
For a REPL into the running app: `npm run repl` (or connect your editor
to the nREPL port shadow-cljs prints on startup).

To try it out you need a Solid pod — free accounts at
[solidcommunity.net](https://solidcommunity.net) or
[Inrupt PodSpaces](https://start.inrupt.com/), among others.

### If the app redirects to your pod provider and never comes back

Load **<http://localhost:8080/?reset>**. That clears the stored session
before anything else runs, and you get a clean login screen.

Sessions survive a page reload because `handle-redirect!` passes
`restorePreviousSession: true`. That restore is not a background
request: `solid-client-authn-browser` navigates the **whole page** to
the identity provider with `prompt=none` and relies on being redirected
straight back. (While that's in flight, `handleIncomingRedirect` returns
a promise that deliberately never resolves, since the page is about to
be replaced — so nothing downstream of it can run, and startup costs a
full redirect round trip.)

If the provider rejects that request the browser is simply left on the
provider's error page. The usual cause is a **dynamic client
registration the provider has since expired**: node-solid-server answers
with a bare `400` instead of redirecting back with an error. The stored
session ID still looks valid locally, so every reload re-attempts the
same doomed request — you can't reach the app, and you can't reach the
Log out button that would have cleared it.

Hence `?reset`, handled by `auth/recover-from-url!`, which deletes the
`solidClientAuthn:` keys from `localStorage`. Clearing site data for
`localhost:8080` in DevTools does the same thing, if you can get the
page to sit still long enough.

This was a dynamic-registration problem. Dev builds now use a published
client identifier, which doesn't expire, so it shouldn't recur — but the
hatch stays, since any wedged session is otherwise unreachable.
See [Client identity](#client-identity).

## Release build

```sh
npm run release
```

This produces an optimized bundle in `public/js/`. The whole `public/`
directory is the deployable site — host it on GitHub Pages, Netlify, or
any static file server. The app uses no routing, so no rewrite rules are
needed; the page's own URL is used as the OIDC redirect URL.

`.github/workflows/deploy.yml` does this on every push to `main`: builds
the release bundle and publishes `public/` to GitHub Pages at
<https://insano10.github.io/solid-social/>. `public/js/` is gitignored —
the site is compiled in CI, never committed.

One-time setup, in the repo's **Settings → Pages**, set *Source* to
**GitHub Actions**. Without that the deploy step fails; the workflow
can't enable Pages itself.

GitHub Pages serves `.jsonld` as `application/ld+json`, so the client
identifier documents are dereferenceable as-is — verified against the
live site, and confirmed by solidcommunity.net accepting the published
identity at login. Worth re-checking if the hosting ever changes, since
a document served as `text/plain` may be rejected:

```sh
curl -sI https://insano10.github.io/solid-social/clientid.jsonld | grep -i content-type
```

## How data is stored

Everything lives in the pod under `solid-social/` at the storage root:

```
solid-social/
├── contacts.ttl    # WebIDs you follow
├── posts/          # one Turtle resource per post
│   └── 2026-08-01T12-00-00-000Z.ttl
└── media/          # uploaded photos/videos, referenced by posts
```

Posts are [ActivityStreams 2.0](https://www.w3.org/TR/activitystreams-vocabulary/)
`Note`s, so the data stays legible to other Solid apps:

```turtle
@prefix as: <https://www.w3.org/ns/activitystreams#>.

<#post>
    a as:Note ;
    as:attributedTo <https://alice.solidcommunity.net/profile/card#me> ;
    as:published "2026-08-01T12:00:00Z"^^xsd:dateTime ;
    as:content "Hello from my pod!" ;
    as:attachment <../media/sunset.jpg> .
```

The feed is assembled client-side: for each followed WebID the app
resolves the pod's storage root (via `pim:storage` in the profile),
lists `solid-social/posts/`, fetches each post, and merges everything
sorted by `as:published`.

Each person's pod answers at its own pace, so posts are merged into the
feed as they arrive rather than waiting for the slowest contact —
`state/db` holds `:posts-by-author` and `:posts` is the flattened sort
of it. A contact whose pod is unreachable contributes `[]` instead of
breaking the feed.

### Round trips

Pods can be slow: requests to solidcommunity.net have been observed
taking around ten seconds, essentially all of it waiting on the server
rather than queued in the browser. Nothing client-side fixes per-request
latency, so what the code optimises is **the number of requests on the
critical path**:

- A WebID profile is fetched **once** per person, with the promise
  cached and shared (`pod/profile+`, cleared on logout). It's needed
  both for the storage root and for the display name and avatar, and
  those used to fetch it separately. That's also why `pod-root+` reads
  `pim:storage` itself instead of calling solid-client's
  `getPodUrlAll`, which would fetch the profile again internally.
- On startup your own posts and your contact list are requested **in
  parallel**. They're independent, but serialising them meant the feed
  issued no request at all until the contacts round trip had finished.
- Media is fetched only as it nears the viewport — see below.

### Authenticated media

Pod resources are private by default, and the browser won't attach the
session's tokens to a plain `<img src="https://…pod/media/photo.jpg">` —
that request goes out anonymous and comes back **401**. So attachments
aren't pointed at their pod URL directly. `pod/media-url+` fetches the
bytes with the session's `fetch` and wraps them in a local `blob:` URL,
and the `authed-media` component in `views.cljs` renders from that,
releasing the URL when it unmounts. Media the viewer genuinely can't
read degrades to a plain link rather than a broken image.

Because nothing else can defer these requests — an authenticated fetch
can't use `loading="lazy"` — `authed-media` defers them itself, starting
the fetch only when an `IntersectionObserver` says the placeholder is
near the viewport. Without that, a feed full of photos downloads every
attachment at once, competing with the post fetches.

Avatars are still plain `src` attributes, on the assumption that profile
photos are public. If you hit a 401 on one, route it through
`authed-media` too.

## Client identity

An identity provider needs to know *which app* is asking for access. By
default `solid-client-authn-browser` registers a throwaway client on
every interactive login — it clears the stored registration unless the
login is a silent one — so the app shows up as a brand new stranger each
time, and old registrations pile up server-side until the provider
prunes them. When that happens mid-session you get the lockout described
above.

Instead, this app publishes a **client identifier document**: a JSON-LD
file whose URL *is* the client ID. The provider fetches it during login
and takes the app's name and its permitted redirect URIs from it. There
is no client secret — a browser app can't keep one — so `redirect_uris`
is the whole security boundary: it's what guarantees an authorization
code issued for this app can only ever be delivered back to this app.

There are two documents, deliberately:

| File | Identity | Redirect URIs |
|---|---|---|
| `public/clientid.jsonld` | `Solid Social` — the published app | the GitHub Pages URL |
| `public/clientid-dev.jsonld` | `Solid Social (local development)` | `http://localhost:8080/` |

They're split because anyone may use a published client ID in their own
authorization request. If the real app's document permitted localhost
redirects, someone could compose a request that shows the victim a
consent screen reading "Solid Social" and have the code delivered to
`localhost` on the victim's own machine — harmless unless something
hostile is listening on that port, but developers do run things on 8080,
and on node-solid-server trust is granted per *origin*, so a single
`http://localhost:8080` entry covers every app ever run there. Keeping
localhost on a throwaway identity that carries no reputation removes the
incentive, and that document can be rotated or deleted at will.

The ID used at build time comes from a `goog-define` in `auth.cljs`, set
per build in `shadow-cljs.edn`:

- `npm run release` → the published identity.
- `npm run dev` → the development identity.

Both documents are fetched from the published site by the *identity
provider*, never by the browser — which is why even the localhost-only
client needs a public URL, and why neither works until the site has been
deployed at least once. Leaving `client-id` empty (the default in
`auth.cljs`) falls back to dynamic registration.

A published identity also fixes session persistence, which is the same
root cause wearing a different hat. Dynamic registrations come back from
the provider with a `clientExpiresAt`, and the library's
`validateCurrentSession` refuses to restore a session whose registration
has expired — so you land on the login screen after every refresh. A
client identifier document has no expiry, so restore keeps working.

Note that `:closure-defines` is build configuration: changing which
identity a build uses needs a `npm run dev` restart, not just a save.

Two things to verify the first time the site is published, before
trusting any of this:

1. That the document is served as `application/ld+json`. GitHub Pages
   may not map the `.jsonld` extension and could serve it as plain
   text, which some providers reject:
   `curl -sI https://insano10.github.io/solid-social/clientid.jsonld | grep -i content-type`
2. That `redirect_uris` matches the deployed URL **exactly** — the
   provider does a literal string comparison. `login!` builds its
   redirect from `origin + pathname` for this reason, so a stray query
   string can't break the match.

Note that changing a document's URL changes the app's identity: users
would see a fresh consent screen and, on node-solid-server, need to
trust it again. Worth getting the filename right before publishing.

## Build tooling

`shadow-cljs.edn` sets `:deps true`, so the JVM classpath — **including
which shadow-cljs compiler actually runs** — comes from `deps.edn`. The
npm `shadow-cljs` package is only the launcher. Keep the two versions in
step; if they disagree, `deps.edn` wins and the npm version is a red
herring when debugging.

The version floor is not arbitrary. shadow-cljs runs every npm
dependency through the Google Closure compiler, which lags years behind
modern JavaScript syntax, and this project's dependency tree pushes on
that in two places:

- `jose@5` (pulled in by `@inrupt/solid-client-authn-browser` 2.x) uses
  `export * as ns from '…'`, which **no** Closure release parses.
  Upgrading shadow-cljs cannot fix it; the fix was moving to
  authn-browser **v5**, which pulls `jose@6`.
- `jose@6` uses private class fields (`#parent;`). Closure as shipped in
  shadow-cljs **2.x** rejects these (`'}' expected`); the one in **3.x**
  parses them fine. Hence shadow-cljs ≥ 3, hence Java 21+.

`@inrupt/solid-client` v3 and authn-browser v5 also clear all `npm
audit` findings and deprecation warnings that the older majors carried.
`package-lock.json` is committed.

If a `Failed to inspect file … Parse error` ever appears again, it is
Closure choking on modern syntax in some npm dependency — identify the
syntax, then either upgrade that dependency to a build Closure can read
or, as a last resort, point `:js-options {:resolve …}` at one.

### IntelliJ / Cursive

`(:require ["@inrupt/solid-client" :as sc])` is a *string require* of an
npm module. Cursive resolves aliases against Clojure namespaces on the
JVM classpath, and npm packages are neither — so it flags `sc`, `authn`
and every use of them as unresolved. It's an IDE analysis gap only;
shadow-cljs resolves string requires itself at build time. Suppress the
inspection in `pod.cljs`/`auth.cljs` (the only two namespaces affected)
or ignore it.

## Code layout

| Namespace             | Role                                                       |
|-----------------------|------------------------------------------------------------|
| `solid-social.core`   | Entry point: mounts the UI, restores the session           |
| `solid-social.views`  | Reagent components (login, composer, contacts, feed)       |
| `solid-social.state`  | Single app-state atom and the actions that mutate it       |
| `solid-social.pod`    | All pod I/O — wraps `@inrupt/solid-client`, returns cljs data |
| `solid-social.auth`   | Solid-OIDC login/logout/session via `solid-client-authn-browser` |
| `solid-social.vocab`  | RDF vocabulary constants (ActivityStreams, FOAF, vCard)    |

`pod.cljs` is the only namespace that touches JS objects; everything
above it works with plain Clojure maps.

## Current limitations / next steps

- **Access control is manual.** New resources inherit your pod's default
  permissions, which usually means private. For friends to see your
  posts you currently need to make `solid-social/` readable (by them, or
  publicly) via your pod provider's UI. Managing ACLs from the app is
  the natural next feature.
- **No pagination.** Every post from every contact is loaded on refresh,
  and since each post is its own resource, that's one request per post
  (they run in parallel, but the round trips add up). Fine for
  personal-network scale; beyond that you'd want container-level date
  filtering or a summary index resource.
- **Posts container discovery is by convention** (`solid-social/posts/`
  under the storage root). A more robust approach is registering the
  container in each user's [Type Index](https://solid.github.io/type-indexes/).
