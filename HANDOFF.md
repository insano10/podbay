# Solid Social — project handoff notes

Context document for an AI assistant (or human) picking up this project.
Written 2026-08-02 on branch `claude/solid-pod-feed-app-egcmtj`.

## Project vision

A lightweight social media feed app over [Solid](https://solidproject.org/)
pods, owned by Jenny, a Clojure developer with limited frontend
experience (some Angular/React exposure, not confident).

Core requirements, all deliberate constraints:

- **Static site, no backend.** The built site is plain HTML/JS/CSS,
  hostable on GitHub Pages/Netlify. Auth and all data access happen
  client-side against Solid pods.
- **Posts live in the author's own pod** as Turtle (RDF), with photos
  and videos stored alongside as binary resources.
- **Follow people by WebID**; their posts merge into your feed,
  sorted chronologically in a scrollable view.
- **No social-media antipatterns**: no ads, sponsored content,
  engagement mechanics, or data gathering. The app is purely a view
  over data the user chooses to link.

## Tech stack (decided and implemented)

- **ClojureScript + shadow-cljs + Reagent** (user chose Reagent over
  Replicant, knowing some React). No re-frame — a single Reagent atom
  is enough at this size.
- **promesa** for JS promise interop.
- **npm libraries**: `@inrupt/solid-client` (pod reads/writes),
  `@inrupt/solid-client-authn-browser` (Solid-OIDC login in browser).
- **deps.edn + shadow-cljs `:deps true`**: JVM classpath (including
  the shadow-cljs compiler version) comes from `deps.edn`; the npm
  `shadow-cljs` package is only the launcher. Added so IntelliJ/Cursive
  resolves the project. **Keep the shadow-cljs version in deps.edn and
  package.json in step.**

## Data model (implemented)

Everything lives under `solid-social/` at the pod storage root:

```
solid-social/
├── contacts.ttl    # WebIDs the user follows (as:following on <#me>)
├── posts/          # one Turtle resource per post, named by ISO timestamp
└── media/          # uploaded photos/videos, referenced by posts
```

Posts are ActivityStreams 2.0 `as:Note` things with `as:attributedTo`
(author WebID), `as:published` (xsd:dateTime), `as:content` (text) and
`as:attachment` (media URLs). Standard vocabulary chosen deliberately
for interoperability with other Solid apps.

Feed assembly: for each WebID (own + contacts), resolve the pod storage
root via `getPodUrlAll` (`pim:storage` in the profile, falling back to
the WebID's URL origin), list the `posts/` container, fetch every post
resource, merge all posts, sort by `as:published` descending. Failures
for one contact resolve to `[]` so they never break the whole feed.

## Code layout

| File | Role |
|---|---|
| `src/solid_social/core.cljs` | Entry point; mounts UI, restores session |
| `src/solid_social/views.cljs` | Reagent components: login (with provider quick-picks), composer (text + file input), contacts panel, feed |
| `src/solid_social/state.cljs` | Single `r/atom` app-db + all mutation functions (`init!`, `refresh-feed!`, `submit-post!`, `add-contact!` …) |
| `src/solid_social/pod.cljs` | **The only JS-interop namespace.** Wraps `@inrupt/solid-client`; all functions suffixed `+` return promises resolving to plain cljs data |
| `src/solid_social/auth.cljs` | Solid-OIDC wrapper: `login!`, `logout!`, `handle-redirect!`, `auth-fetch` |
| `src/solid_social/vocab.cljs` | RDF predicate/class IRI constants (AS 2.0, FOAF, vCard) |
| `public/index.html`, `public/css/style.css` | Static shell; CSS has automatic dark mode |
| `shadow-cljs.edn` | Build config (`:deps true`, `:dev-http {8080 "public"}`, browser target → `public/js`) |
| `deps.edn` | JVM deps: shadow-cljs 3.4.11, reagent 1.2.0, promesa 11.0.678 |
| `.clj-kondo/config.edn` | Lints `p/let` and `r/with-let` as `let` |

Interop conventions: `pod.cljs` uses solid-client's **pure-function API**
(`sc/addUrl` on things) rather than its `buildThing` builder objects —
safer under Closure advanced compilation. JS objects in lambda params
carry `^js` hints.

## Build-tooling saga (important history — don't regress this)

Three build failures were diagnosed and fixed in sequence; all were
versions of one root cause: **the Closure compiler inside shadow-cljs
parses all npm JS, and it lags years behind modern JS syntax.**

1. `jose@5` (via authn-browser 2.x) uses `export * as ns from '...'` —
   **no Closure release parses this, even the newest** (verified
   empirically against Closure v20240317 and v20260730). Upgrading
   shadow-cljs alone can never fix that error. Fixed by upgrading
   `@inrupt/solid-client-authn-browser` to **v5** → pulls `jose@6`,
   whose rebuilt output has no `export * as`.
2. Same upgrade round: `@inrupt/solid-client` → **v3**. Together these
   cleared **all npm audit vulnerabilities and deprecation warnings**
   (the old versions depended on deprecated `@inrupt/oidc-client` and
   old `uuid`). Verified: the public APIs used by this app are
   unchanged across those majors. `package-lock.json` is now committed.
3. `jose@6` uses **private class fields** (`#parent;`), which the
   Closure in shadow-cljs **2.x** rejects (`'}' expected`) but the
   Closure in shadow-cljs **3.x** parses fine (verified empirically).
   User's new `deps.edn` had pinned shadow-cljs 2.28.20 while npm had
   3.4.11 — with `:deps true`, **deps.edn wins**. Fixed by bumping
   deps.edn to 3.4.11.

Rules of thumb going forward:
- shadow-cljs must stay ≥ 3.x (and **requires Java 21+**).
- If "Failed to inspect file … Parse error" appears again, it's Closure
  choking on modern syntax in some npm dep — check what syntax, then
  either upgrade the dep or (last resort) `:js-options {:resolve …}` to
  a parseable build of it.
- The full npm runtime tree was batch-parsed with Closure v20260730:
  clean (only false positive: `which/bin/which.js`, a Node CLI script
  never reached by browser code).

## Current status

- All code is committed and pushed on branch
  `claude/solid-pod-feed-app-egcmtj` (base: `main`). No PR opened.
- clj-kondo lints clean.
- **The app has never yet been compiled or run end-to-end.** The
  remote assistant environment could not reach `repo.clojars.org`
  (network policy), so `npm run dev` on the user's machine is the
  verification path. The last fix (deps.edn → shadow-cljs 3.4.11) is
  pushed but not yet confirmed by the user.
- Expect the next issues (if any) to be small runtime interop details,
  since the code was written without a compile/run loop.

## Known limitations / natural next steps (documented in README too)

1. **Verify the build**: `git pull && npm install && npm run dev`
   (Java 21+, Clojure CLI required; dev server on port 8080 or next
   free). Fix any remaining compile/runtime interop issues.
2. **Access control**: new pod resources default to private; friends
   can't read posts until `solid-social/` is made readable via the pod
   provider's UI. In-app ACL management (WAC/ACP, e.g. via
   `@inrupt/solid-client`'s access APIs) is the top feature gap.
3. **Media auth**: attachments render via plain `<img>`/`<video>` src,
   i.e. unauthenticated fetches — private media won't display. Fix:
   fetch blobs with the session's `auth-fetch` and use object URLs.
4. **No pagination** — all posts from all contacts load on refresh.
5. **Container discovery is by convention** (`solid-social/posts/`
   under storage root). More robust: Solid Type Indexes.
6. Possible polish: relative timestamps, optimistic post insertion,
   loading skeletons, deploy workflow (GitHub Pages action building
   `npm run release` and publishing `public/`).

## User preferences worth honoring

- Keep it lightweight and simple; resist feature creep and heavy
  frameworks.
- User is strong in Clojure, weak in JS/frontend — keep JS interop
  quarantined in `pod.cljs`/`auth.cljs`, explain frontend-ecosystem
  quirks when they matter.
- No engagement mechanics of any kind, ever.
