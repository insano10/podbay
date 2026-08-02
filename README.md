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

## Release build

```sh
npm run release
```

This produces an optimized bundle in `public/js/`. The whole `public/`
directory is the deployable site — host it on GitHub Pages, Netlify, or
any static file server. The app uses no routing, so no rewrite rules are
needed; the page's own URL is used as the OIDC redirect URL.

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
breaking the feed. Storage-root lookups cost a profile fetch and are
needed repeatedly, so `pod.cljs` caches them per WebID (cleared on
logout).

### Authenticated media

Pod resources are private by default, and the browser won't attach the
session's tokens to a plain `<img src="https://…pod/media/photo.jpg">` —
that request goes out anonymous and comes back **401**. So attachments
aren't pointed at their pod URL directly. `pod/media-url+` fetches the
bytes with the session's `fetch` and wraps them in a local `blob:` URL,
and the `authed-media` component in `views.cljs` renders from that,
releasing the URL when it unmounts. Media the viewer genuinely can't
read degrades to a plain link rather than a broken image.

Avatars are still plain `src` attributes, on the assumption that profile
photos are public. If you hit a 401 on one, route it through
`authed-media` too.

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
