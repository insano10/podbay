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
- A JVM (Java **21+**) — shadow-cljs 3.x runs on it and requires Java 21.
  If you're stuck on an older JVM, pin `"shadow-cljs": "^2.28.20"` in
  `package.json` instead; it works with Java 11+ (its `npm audit` noise
  is dev-time only and never reaches the browser bundle).

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
- **Media in `<img>`/`<video>` tags is fetched without authentication**,
  so attachments only render if they're readable without a login. Fixing
  this means fetching media with the authenticated session and using
  object URLs.
- **No pagination.** Every post from every contact is loaded on refresh.
  Fine for personal-network scale; container-level date filtering would
  be needed beyond that.
- **Posts container discovery is by convention** (`solid-social/posts/`
  under the storage root). A more robust approach is registering the
  container in each user's [Type Index](https://solid.github.io/type-indexes/).
