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

This watches **both** apps in this repo:

| URL | App |
|---|---|
| <http://localhost:8080> | Solid Social — the feed |
| <http://localhost:8081> | [Pod Browser](#pod-browser) — a file browser for your pod |

They're on **separate ports deliberately**, and it matters: see
[Two apps, one session](#two-apps-one-session).

If that port is taken, shadow-cljs picks the next free one — check its
startup output. shadow-cljs hot-reloads code on save.
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

## Pod Browser

A second, separate app in this repo, served at `/browser/`: a plain
file browser for any Solid pod — pick your identity provider, sign in,
and walk the containers like folders. It exists because the alternatives
are thin on the ground. Inrupt sunset PodBrowser, Penny is explicitly a
developer inspector rather than a file manager, Solid File Manager
hasn't been touched since March 2025, and SolidOS is something a
*server* deploys rather than an app you can point at an arbitrary pod —
which is why an ESS pod, serving no HTML at all, simply won't open in
any of them.

```
src/pod_browser/
├── core.cljs     # entry point
├── state.cljs    # one atom, plus the actions that change it
├── views.cljs    # Reagent components
└── pod.cljs      # the only JS-interop namespace here
```

It shares exactly one namespace with the feed app, `solid-social.auth`,
rather than reimplementing the OIDC dance. Everything else is its own.

What it does today:

- **Listing** costs one request per folder, not one per file. A
  container's own representation already describes its children — type,
  size, modification time — so `list-container+` reads all of it from
  the response it already has. Servers aren't obliged to publish those,
  so every field is optional and simply renders blank.
- **Opening a file** splits content and metadata into two panes, so the
  detail stays visible beside what you're reading. Text and RDF render
  inline, images preview, anything else offers a download. The metadata
  pane shows the response headers the server exposes — including
  `WAC-Allow`, which is your actual access to that resource.
- **A failed read is shown, not swallowed.** A 403 renders as a 403 with
  the server's message, because "you may not read this" is information.
- **Right-click** any row for open, copy URL, open raw, and delete.
  Delete asks for confirmation naming the resource, and containers use a
  different call from files — most servers refuse to delete a container
  that still has children.

### Two apps, one session

`solid-client-authn-browser` keeps its session in `localStorage`, which
is scoped to an **origin** — not to a path. Two apps served from the
same origin therefore share one session, and the failure mode is not
subtle: whichever app loads second finds the other's stored session,
silently re-authenticates with `restorePreviousSession`, and — because
`silentlyAuthenticate` reuses the *stored* session's redirect URL — the
identity provider sends the user back to the **other app**.

Hence two dev ports: `8080` for the feed, `8081` for the browser. Two
origins, two `localStorage` scopes, no interference.

This is unresolved for the **deployed** site, where both live under
`insano10.github.io/solid-social/` and so share an origin. The fix is
either a separate host for the browser, or constructing an explicit
`Session` with namespaced storage instead of the default one — the
library supports it (`new Session({secureStorage, insecureStorage})`),
though the keys tracking *which* session to restore are still written
to raw `localStorage` and would stay shared.

### Its own identity

The browser has separate client identifier documents
(`clientid-browser.jsonld`, `clientid-browser-dev.jsonld`) from the feed
app's, so the consent screen names what is actually asking for access —
"Pod Browser", not "Solid Social". Same split between published and
localhost redirect URIs, for the same reason. See
[Client identity](#client-identity).

## How data is stored

By default everything lives in the pod under `solid-social/` at the
storage root — though the posts container is discovered rather than
assumed, so it and its media can be anywhere (see below):

```
solid-social/
├── contacts.ttl        # WebIDs you follow
└── posts/              # one Turtle resource per post
    ├── 2026-08-01T12-00-00-000Z.ttl
    └── media/          # uploaded photos/videos, referenced by posts
```

`media/` sits inside the posts container rather than beside it, so it
follows wherever the type index points posts, and so one access grant on
the posts container covers the attachments those posts reference —
access control in Solid is per-container. Posts link attachments by
absolute URL, so media written under an older layout keeps resolving.

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

The feed is assembled client-side: for each followed WebID the app finds
that person's posts container, lists it, fetches each post, and merges
everything sorted by `as:published`.

### Finding where posts live

Solid mandates no folder layout, so `solid-social/` is an
implementation detail and not something another app could ever guess.
Portability comes from the vocabulary (ActivityStreams, above) plus
*discovery*: a pod advertises where each kind of data lives in its
**[type index](https://solid.github.io/type-indexes/)**, linked from the
WebID profile as `solid:publicTypeIndex`.

So the question the app asks is "where do this person's `as:Note`
resources live?", never "what's in their `solid-social/` folder":

```turtle
<#solid-social-posts>
    a solid:TypeRegistration ;
    solid:forClass as:Note ;
    solid:instanceContainer </solid-social/posts/> .
```

`pod/posts-container+` resolves that registration and falls back to
`solid-social/posts/` under the storage root when a pod publishes no
index or no registration for the class. **Reads and writes both go
through it**, so the app never holds two different ideas of where
someone's posts are.

Publishing a post registers the container, but only when nothing has
claimed `as:Note` yet. If another app got there first, the lookup has
already resolved to *their* container and posts are written there —
which is the point of the exercise. Registration is best-effort: a pod
with no type index, or one the app can't write to, silently stays on the
convention path.

The cost is one extra round trip per author, to read the index. That's
real on a slow pod (see below), and it's why the index is cached per
WebID alongside the profile.

Each person's pod answers at its own pace, so posts are merged into the
feed as they arrive rather than waiting for the slowest contact —
`state/db` holds `:posts-by-author` and `:posts` is the flattened sort
of it. A contact whose pod is unreachable contributes `[]` instead of
breaking the feed.

### Flaky servers

Pod servers are not always reliable. solidcommunity.net intermittently
answers **502** through its CDN — including on CORS preflights, which is
the nastiest version: a failed preflight surfaces as a bare
`TypeError: Failed to fetch` plus a console message blaming CORS, so a
transient outage is indistinguishable from a configuration error at a
glance. (The tell is that a real CORS misconfiguration fails *every*
time, identically; a blip fails once and then works.)

`auth/auth-fetch` therefore retries: up to three attempts with a
300ms/900ms backoff plus jitter, on a rejected request or a
429/500/502/503/504 response. Only repeatable methods are retried —
`GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE` — since `POST` creates
something new on each attempt and `PATCH` may not survive being applied
twice. Any 4xx is an answer, not a failure, and comes straight back.

Every pod request in both apps goes through it, so this is also the most
likely reason the third-party pod browsers feel unreliable: they surface
the blip rather than absorbing it.

### Caching

Pod responses carry no `Cache-Control`, but they do carry
`Last-Modified` and `ETag`. With no explicit directive the browser
applies *heuristic freshness* and may reuse a response without asking —
so a container listing fetched right after a write can still describe
the old contents. That's what made a deleted file stay on screen.

There's no way to purge a single entry from the browser's HTTP cache
from JavaScript, so the only lever is a per-request cache mode. Reads of
things that change are therefore marked `no-cache` — **still cached, but
always revalidated**, so an unchanged resource costs a 304 rather than a
full body. Not `no-store`, which would throw the cache away entirely.

| Always revalidated | Cached normally |
|---|---|
| container listings (both apps) | individual post documents |
| `contacts.ttl` | WebID profiles |
| any file opened in the browser | type indexes, media |

The right-hand column is either immutable once written or already
memoised in-process for the session (`pod/profile+`, `pod/type-index+`),
with those in-memory caches dropped explicitly — on logout, and after
registering a type so the next read sees the registration.

### Round trips

Pods can be slow: requests to solidcommunity.net have been observed
taking around ten seconds, essentially all of it waiting on the server
rather than queued in the browser. Nothing client-side fixes per-request
latency, so what the code optimises is **the number of requests on the
critical path**:

- A WebID profile is fetched **once** per person, with the promise
  cached and shared (`pod/profile+`, cleared on logout). It's needed for
  the storage root, the type index link, and the display name and
  avatar, and those used to fetch it separately. That's also why
  `pod-root+` reads `pim:storage` itself instead of calling
  solid-client's `getPodUrlAll`, which would fetch the profile again
  internally. Type indexes are cached the same way.
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

Naming: a **`+` suffix means the function returns a promise**
(`load-posts+`, `media-url+`). That's a project convention rather than
anything standard — Clojure reserves `?` for predicates and `!` for
side effects, but has settled on nothing for async — so it's worth
keeping applied consistently.

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
- **The contacts list is still found by convention**
  (`solid-social/contacts.ttl`), unlike posts. Registering it would mean
  choosing an RDF class for "a list of people I follow", and there isn't
  an established one — ActivityStreams models `as:following` as a
  property of an actor pointing at a collection, not as a class you can
  register. It's also private app state rather than part of the
  interoperable surface, so the convention costs nothing today.
