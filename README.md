# Podbay

A suite of apps built on [Solid](https://solidproject.org/) pods — your
data, in storage you control, read and written straight from the browser.

| App | | |
|---|---|---|
| **Comms** | `/comms/` | A social feed assembled from the pods of the people you follow |
| **Airlock** | `/airlock/` | A file browser for any Solid pod — the way in and out |

There is deliberately no backend: these are static sites. Authentication
(Solid-OIDC) and all data access happen in the browser, directly against
each person's pod. No ads, no algorithm, no tracking — nothing here is a
view over anything but data you and your friends chose to link.

Built with [ClojureScript](https://clojurescript.org/),
[shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) and
[Reagent](https://reagent-project.github.io/).

## Comms

Write posts (text plus photos and videos) into **your own pod**, follow
friends by their WebIDs, and see everyone's posts merged into one
chronological feed. How it finds and stores that data is described under
[How data is stored](#how-data-is-stored) below; Airlock has its own
section further down.

## Repository layout

Two independent single-page apps and the code they share. Neither app
imports the other; the only common ground is `podbay.shared.*`.

```
apps/
  comms/       Comms — the feed              (podbay.comms.*)
    src/  public/
  airlock/     Airlock — files in a pod      (podbay.airlock.*)
    src/  public/
shared/
  src/         auth + generic RDF vocabulary (podbay.shared.*)
site/
  index.html   landing page linking the two
```

Each app's `public/` holds its own `index.html`, `css/`, `icon.svg` and
client identifier documents; its `js/` is build output and gitignored.
All three source roots sit on one classpath (`deps.edn`), and shadow-cljs
compiles only what each build's `:init-fn` reaches — so neither bundle
contains the other app's code.

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
| <http://localhost:8080> | Comms — the feed |
| <http://localhost:8081> | [Airlock](#airlock) — a file browser for your pod |

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

This builds both apps, each into its own `apps/*/public/js/`. Neither
app uses routing, so no rewrite rules are needed anywhere; each page's
own URL is its OIDC redirect URL.

`.github/workflows/deploy.yml` does this on every push to `main`: builds
both release bundles, assembles them into a `dist/` tree, and publishes
it to GitHub Pages. `apps/*/public/js/` is gitignored — the site is
compiled in CI, never committed.

```
https://insano10.github.io/podbay/           landing page
                                  /comms/    Comms
                                  /airlock/  Airlock
```

One-time setup, in the repo's **Settings → Pages**, set *Source* to
**GitHub Actions**. Without that the deploy step fails; the workflow
can't enable Pages itself.

GitHub Pages serves `.jsonld` as `application/ld+json`, so the client
identifier documents are dereferenceable as-is — verified against the
live site, and confirmed by solidcommunity.net accepting the published
identity at login. Worth re-checking if the hosting ever changes, since
a document served as `text/plain` may be rejected:

```sh
curl -sI https://insano10.github.io/podbay/comms/clientid.jsonld | grep -i content-type
```

## Airlock

A second, separate app in this repo, served at `/airlock/`: a plain
file browser for any Solid pod — pick your identity provider, sign in,
and walk the containers like folders. It exists because the alternatives
are thin on the ground. Inrupt sunset PodBrowser, Penny is explicitly a
developer inspector rather than a file manager, Solid File Manager
hasn't been touched since March 2025, and SolidOS is something a
*server* deploys rather than an app you can point at an arbitrary pod —
which is why an ESS pod, serving no HTML at all, simply won't open in
any of them.

```
apps/airlock/
├── src/podbay/airlock/
│   ├── core.cljs     # entry point
│   ├── state.cljs    # one atom, plus the actions that change it
│   ├── views.cljs    # Reagent components
│   └── pod.cljs      # the only JS-interop namespace here
└── public/           # index.html, css/, icon.svg, client documents
```

It shares only the `podbay.shared.*` namespaces with Comms —
authentication and the generic RDF vocabulary — rather than
reimplementing the OIDC dance. Everything else is its own.

What it does today:

- **Listing** costs one request per container, not one per file. A
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
- **Sharing** is shown for the open resource: who has read, append,
  write and control, both publicly and per agent. The two servers this
  is used against don't share an access-control system — Community Solid
  Server implements WAC (an `.acl` beside each resource), Inrupt ESS
  implements ACP (composed policies) — so it goes through
  solid-client's `universalAccess`, which speaks both. Reading an access
  control resource itself needs *control* access, so being refused on
  someone else's data is the normal answer, and is shown as such rather
  than as an error — which also means sharing can only be *changed*
  while signed in as that pod's owner.
- **Changing sharing**: grant a WebID read/append/write, revoke, or make
  something readable by anyone. Widening access to everyone asks for
  confirmation, because it can't be undone — revoking later doesn't
  retrieve a copy someone already took.
- **You cannot lock yourself out.** Revoking asks first, and revoking
  your *own* WebID removes read, append and write but **keeps
  `controlRead`/`controlWrite`**. Control is what lets you read and
  rewrite a resource's access control resource, so giving it up is the
  one change the app can't undo: you'd be locked out of the rules
  governing your own file with no way back through the UI. Recovering
  needs hand-editing the `.acl`, or deleting it so the resource
  re-inherits from its container — possible, but not something a click
  should be able to require.
- The permission chips are a **readout, not controls**. Each explains
  itself on hover; "See sharing" is `controlRead` and "Change sharing"
  is `controlWrite`. ACP separates them, WAC has a single `acl:Control`
  covering both, and the universal access API exposes ACP's split.

  Reading keeps them apart, since on ACP they genuinely can differ and
  collapsing them would misreport the pod. **Writing uses one
  `:control` flag**, expanded to both on the way out, so the asymmetric
  combination can't be constructed — solid-client *throws* on a WAC pod
  if the two differ, which would otherwise be a change that works
  against ESS and fails against CSS.
- **A grant on a container also covers its contents**, which takes two
  writes and two different APIs. `universalAccess` is explicit that it
  won't do this: *"if the Resource is a Container, the configured Access
  will not apply to contained Resources."* On its own that makes sharing
  a container of posts useless — the reader is admitted to the container
  and refused every file in it. So `set-inherited-access+` follows up
  with the server's own inheritance mechanism, picked by asking the pod
  (`isAcpControlled`) rather than from configuration:

  | | Mechanism | How it's written |
  |---|---|---|
  | CSS (WAC) | `acl:default` in the container's `.acl` | `setAgentDefaultAccess` + `saveAclFor` |
  | ESS (ACP) | a policy linked as `acp:memberAccessControl` | `addMemberPolicyUrl` + `saveAcrFor` |

  Both are evaluated against what's in the container *when a request is
  made*, so this covers files already there as well as ones added later
  — no walk over the contents, and one write however many posts there
  are. Per-file rules are left alone.

  Two details worth keeping. On WAC, a container with no `.acl` of its
  own is governed by an ancestor's, so a new one is seeded with
  `createAclFromFallbackAcl` — creating a blank ACL would silently drop
  the inherited rules, quite possibly including your own control access.
  On ACP the app writes one matcher and one policy per mode at
  predictable names inside the ACR, so a later change edits what an
  earlier one wrote instead of piling up policies; revoking empties the
  matcher rather than unlinking the policy, which would take everyone
  else's access with it.

  **The ACP trap, which cost an evening.** solid-client has two sets of
  near-identically named functions, and only one writes to the access
  control resource:

  ```js
  setPolicy(policyResource, policy)     → setThing(policyResource, policy)
  setResourcePolicy(resourceWithAcr, p) → …writes into internal_acp.acr
  ```

  Pass a resource-with-ACR to `setPolicy` and the policy lands in the
  *container's own dataset* instead. `saveAcrFor` then writes an ACR
  containing a member policy URL with **no policy behind it** — a rule
  that grants nothing, saved without error, and visible in the ACR only
  as a bare `{"id": "…#podbay-member-read-policy", "type": "Policy"}`
  with no `allow` and no `anyOf`. Use the `Resource*` variants
  throughout; they also name Things relative to the ACR, so no URL has
  to be assembled by hand.

  One asymmetry to know: `getResourcePolicy` returns `null` for a
  *member* policy however plainly it is in the ACR, because it requires
  the policy to appear in `getPolicyUrlAll`, which lists only the
  resource's own. So the policy is rebuilt from scratch on each change
  rather than read and amended — its whole content is one mode and one
  matcher, `setResourcePolicy` replaces any previous instance, and
  stating the desired state outright is idempotent anyway.

  **Control is never inherited.** Being able to rewrite the access rules
  of every file in a container is not something to hand over as a side
  effect of sharing it.

  **The second write is optional**, via a checkbox on the grant form —
  *and everything inside it*, ticked by default, shown only for
  containers. Default on because that's what "share this folder" means,
  and because a grant that stops at the container is the exact failure
  this section exists to fix. Off, it expresses the case the coupling
  gets wrong: an **inbox**, where you want `Append` on the container so
  people can drop files in, but *not* on the files already there.
  Unticking also skips the propagation check — reporting that the grant
  didn't reach the contents would be scolding you for what you asked
  for. Revoking always clears both regardless; leaving inherited access
  behind after taking access away is the direction that hurts.

  **Public access inherits the same way.** Ticking "readable by anyone"
  on a container reaches its contents too, through the same two
  mechanisms — `setPublicDefaultAccess` on WAC, a member policy on ACP.
  The subject of a grant is a WebID or the keyword `:public`, and the
  code paths are otherwise identical.

  ACP has no separate predicate for "everyone": `setPublic` writes
  `acp:agent` with the sentinel IRI `acp:PublicAgent`. Two consequences
  worth knowing. Reading an agent list means **sieving the sentinels
  out** — `PublicAgent`, `AuthenticatedAgent`, `CreatorAgent` — or they
  render as though they were somebody's WebID. And people and the
  public get **separate matchers** here, linked from the one policy by
  `anyOf`: they could share one, but then revoking a person and making
  a container private would edit the same list, and the emptiness test
  below would have to tell a sentinel from a WebID.

  When the last subject for a mode goes away, the policy is written
  granting nothing and unlinked from the member access control. It
  can't simply be deleted — `removeResourcePolicy` goes through
  `getResourcePolicy`, which refuses a member policy and returns the
  resource untouched — so the choice is between leaving an `allow Read`
  behind and leaving an inert husk. The husk is the one that can't be
  misread.
- **The pane reports rules set on a resource, never its effective
  access.** That distinction matters most on WAC, where
  `getAgentAccessAll` folds in whatever a resource inherits and gives
  no way to tell the two apart afterwards. A file in a shared container
  therefore listed everyone who could read it as though each had been
  granted access *to that file*, next to a Revoke button — and revoking
  there doesn't undo the container's rule. It writes the file its own
  ACL, which as solid-client warns means "changes to the ACL of a
  parent Container can no longer affect access people have to this
  Resource". A button labelled Revoke would have silently detached the
  file from its container for good.

  So the WAC path reads the resource's own ACL directly
  (`getAgentResourceAccessAll`), which is what ACP reports anyway, and
  the two servers now say the same kind of thing. Inherited rules come
  from the fallback ACL and are shown in their own section. WAC needs
  no walk for this, unlike ACP: `acl:default` resolves to a single
  nearest-ancestor ACL, which solid-client returns alongside the
  resource, and it carries the URL of the container it governs.
- **A container's rules for its contents are shown separately**,
  because on ACP they are a different part of the access control
  resource and the access API doesn't report them at all.
  `getAgentAccessAll` enumerates agents from Things in *that resource's
  own* ACR and evaluates only its own policies — so a file inheriting
  read from its container reads back as shared with nobody. Left alone,
  the pane states that as fact for every file in a shared container.

  There's no library call for this: `getResourcePolicy` refuses to
  return a member policy, and the ACR isn't exposed as a dataset. But it
  is an ordinary RDF document at a known URL, so `member-access+`
  fetches and walks it — `acp:memberAccessControl` → `acp:apply` →
  `acp:allow` and `acp:anyOf` → `acp:agent`.

  A file gets the same treatment in reverse, under **Inherited from**:
  `access-context+` walks the containers above it — nearest first, up
  to the storage root and never past it — and reads each one's member
  rules. Levels are fetched concurrently, since they're independent and
  a deep file would otherwise open slowly, and only levels that grant
  something (or couldn't be read) are listed. Saying "nothing is set
  here" and stopping was accurate and still misleading: anyone reads it
  as "nobody can see this", which for a post in a shared container is
  the opposite of the truth.

  A container that grants nothing isn't listed. Worth noting because it
  took a fix: every ACR links its parent's access controls by URL, so
  "this ACR mentions something" is true at every level and made the
  emptiness test useless. Those cross-document links are now ignored
  outright — the walk visits those ancestors anyway, and resolving the
  links would read the same rules twice.

  Two limits remain, stated in the interface rather than papered over.
  A container whose control access we're refused is listed with the
  reason rather than silently skipped. And a policy carrying
  `acp:allOf` (every matcher must match) can't be reduced to a list of
  people, so it's skipped rather than guessed at.
- **A grant on a container is verified on WAC, and deliberately not on
  ACP.** After granting, the app reads back a resource *inside* the
  container and reports whether the grant reached it. On WAC that's a
  real check with a real answer: a file carrying its own `.acl` does not
  inherit the container's, and this is what notices.

  On ACP the same check cannot work, so it isn't run. solid-client
  computes ACP access **client-side**, from `getPolicyUrlAll` and
  `getAcrPolicyUrlAll` — the policies attached to that resource — and
  never from `getMemberPolicyUrlAll` or any ancestor's access control
  resource. Its own source is frank about this (`TODO: add support for
  external resources`, and a matcher reducer labelled `TODO: Proper
  solution`). An inherited member policy is exactly what it can't see,
  so it reported a correctly-shared container as unshared — which was
  confirmed a false negative when the posts duly appeared in Comms on
  the other pod. A check that can't answer is worth less than the
  request it costs, so on ACP the app says what it wrote and leaves it
  there.
- **Editing** any text resource in place — Turtle, JSON, plain text.
  It's a raw `PUT` of exactly what you typed rather than a parse and
  reserialise through solid-client, so hand-written comments, prefixes
  and layout survive. Cmd/Ctrl-Enter saves, Escape abandons, and a
  failed save keeps your draft rather than discarding it.
- **Saves are guarded against overwriting someone else's change.** The
  `PUT` carries `If-Match` with the ETag from the read. That needs care,
  because servers commonly issue *weak* ETags and `If-Match` compares
  strongly — so a 412 may mean "someone else changed this" or merely
  "this server won't honour a weak validator". The app re-reads the
  validator to tell those apart: unchanged means the precondition was
  refused on principle and the write is repeated; changed means a real
  conflict, reported and never overwritten.

  A save also **carries the new validator forward**, taken from the
  `PUT` response, or a `HEAD` if the server doesn't return one. Without
  that, editing the same file twice without closing it would send the
  superseded ETag the second time and report a conflict — with your own
  previous save.
- **Attached resources are reachable.** Every resource has an access
  control resource and a description hanging off it, advertised only in
  its `Link` header and deliberately excluded from `ldp:contains` — so
  they never appear in a listing. The metadata pane links to the open
  resource's (free: the headers are already in hand), and *Show attached
  resources* reveals them per row. That toggle is off by default because
  it costs a `HEAD` per entry.

  Their URLs are **always** taken from the `Link` header, never
  constructed. `<name>.acl` and `<name>.meta` are one server's
  convention; ESS names its access control resources differently, so
  guessing would work on one pod and quietly fail on another.
- **Upload**, from the same toolbar — the browser's own content type is
  used where it has one, since that beats guessing from an extension,
  falling back to the extension and then to `application/octet-stream`.
  A pod serves whatever type you declare, forever, so an honest "unknown
  bytes" beats a confident wrong answer. Names already present are
  skipped and reported rather than replaced.
- **New file and container**, from the toolbar above the listing. A new
  file is created empty and opened straight into the editor, with its
  content type guessed from the extension — a pod stores whatever you
  declare and gets it wrong quietly, and Turtle served as `text/plain`
  won't parse as RDF for anyone. Creating over an existing name is
  refused rather than silently replacing it, since `PUT` would.
- **Rename and move**, from the context menu — edit the last segment to
  rename, or the path to relocate. Containers move everything inside them,
  depth-first.

  There is no `MOVE` in Solid, so it's a copy followed by a delete, in
  that order deliberately: a failure part way leaves items in both
  places rather than losing any. Two things don't come along, and the
  dialog says so before you commit — **sharing doesn't follow** (access
  belongs to a URL, so the copy inherits the destination's), and
  **nothing referencing the old URL is rewritten**, including type index
  registrations and posts that link media absolutely.

  Moving a container into **itself or any descendant is refused**. The
  recursive move would make each child a new child of the source, which
  the recursion then finds and moves again, nesting until something
  gives out — and the result can't be deleted by ordinary means, since a
  server won't remove a container that still has children.
- **Right-click** any row for open, copy URL, open raw, and delete.
  Delete asks for confirmation naming the resource, and containers use a
  different call from files.
- **Deleting a container's contents too** is a checkbox in that dialog,
  off by default and reset every time — escalating has to be a deliberate
  act, never a setting that persists. It works depth-first, because a
  server won't remove a container that still has children.

  There is no bulk delete in Solid: this is **one request per resource**,
  all the way down, which on a deep tree over a slow pod takes a while.
  A failure part way leaves whatever it already removed removed, so the
  listing reloads on failure rather than describing a pod that no longer
  exists. The dialog's hint changes with the checkbox — unticked it warns
  the delete will fail unless the container is already empty; ticked it
  warns there is no undo and no partial rollback.

### Two apps, one session

`solid-client-authn-browser` keeps its session in `localStorage`, which
is scoped to an **origin** — not to a path. Two apps served from the
same origin therefore share one session, and the failure mode is not
subtle: whichever app loads second finds the other's stored session,
silently re-authenticates with `restorePreviousSession`, and — because
`silentlyAuthenticate` reuses the *stored* session's redirect URL — the
identity provider sends the user back to the **other app**.

Separate dev ports (`8080`, `8081`) side-step it locally, but the
deployed site can't: both apps live under `insano10.github.io`, and
origin is the host, not the path.

So neither app uses the library's default session. `podbay.shared.auth`
builds its **own** `Session` over storage that prefixes every key:

```clojure
(goog-define storage-prefix "solidApp:")     ; set per build
(authn/Session. #js {:secureStorage (prefixed-storage)
                     :insecureStorage (prefixed-storage)})
```

`shadow-cljs.edn` sets it to `solidSocialFeed:` and `podBrowser:`, so
tokens, issuer, client registration and redirect URL are per app.

One wrinkle the library forces: it decides *which* session to restore
from a single key it reads with raw `localStorage`
(`solidClientAuthn:currentSession`), bypassing whatever storage you give
it. Left alone, both apps would fight over that one key and whichever
logged in last would be the only one still able to restore. So
`handle-redirect!` points it at our own copy before the library looks,
and writes ours back afterwards. That depends on an internal key name —
as `?reset` already does — and would need revisiting on a major library
upgrade.

Note that changing the prefix moves where sessions live, so an upgrade
past this point logs everyone out once.

### Its own identity

The browser has separate client identifier documents
(`apps/airlock/public/clientid*.jsonld`) from the feed app's, so the consent screen names what is actually asking for access —
"Airlock", not "Comms". Same split between published and
localhost redirect URIs, for the same reason. See
[Client identity](#client-identity).

## How data is stored

By default everything lives in the pod under `podbay/comms/` at the
storage root — though the posts container is discovered rather than
assumed, so it and its media can be anywhere (see below):

```
podbay/comms/
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

Solid mandates no container layout, so `podbay/comms/` is an
implementation detail and not something another app could ever guess.
Portability comes from the vocabulary (ActivityStreams, above) plus
*discovery*: a pod advertises where each kind of data lives in its
**[type index](https://solid.github.io/type-indexes/)**, linked from the
WebID profile as `solid:publicTypeIndex`.

So the question the app asks is "where do this person's `as:Note`
resources live?", never "what's in their `podbay/comms/` container":

```turtle
<#podbay-comms-posts>
    a solid:TypeRegistration ;
    solid:forClass as:Note ;
    solid:instanceContainer </podbay/comms/posts/> .
```

`pod/post-sources+` resolves **every** registration for the class —
each `solid:instanceContainer` and each `solid:instance`, across all
matching registrations — falling back to `podbay/comms/posts/` under the
storage root only when a pod registers nothing at all.

Reading all of them matters, because a type index is a *set of hints,
not an exhaustive statement*. Several registrations may name the same
class, typically one per app; a single registration may list several
containers; and `solid:instance` names an individual document rather
than a container. Merging them is what lets this feed pick up posts a
**different** Solid app wrote into its own container, which is the whole
point of discovery. Posts registered in more than one place are
deduplicated by their URL.

A write has to choose one. When a pod registers several containers the
composer offers them, since a container is the unit access control
inherits through — so separate containers are how posts for different
audiences are kept apart. Left alone, `pod/write-container+` takes the
first registered container, else the convention: the same source a
reader tries first, so a post always lands somewhere its author's own
feed will find it.

**The composer says who can read the chosen container** before anything
is written — "Only you", "Shared with 2 people", or a warning that
anyone on the web can. Putting a post in a container called `private`
doesn't make it private; its access control does, and that gap is
exactly where a destination picker would otherwise mislead. Reading
that needs control access, so on a pod that isn't yours it says it
can't tell rather than guessing.

Worth knowing that a container registered in the **public** type index
advertises its existence and location to anyone, even when its contents
are protected. Audience-segregated containers may belong in
`solid:privateTypeIndex` instead — though then only people you share
that index with can discover them, and Solid has no settled pattern for
that.

`load-posts+` rejects only when **every** source fails. One unreadable
source among several shouldn't blank the rest, since another app's
container may simply be private; but a pod where nothing can be read is
reported rather than rendered as empty.

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

### Absent vs unreadable

Every read distinguishes two things that are easy to conflate: **"the
server told us there is nothing"** and **"we couldn't ask"**. A 404 is
an answer worth acting on — a pod that publishes no type index really
has none, so fall back to the convention. Anything else (401, 403, 502,
a dropped connection) is ignorance, and guessing past it is how a
transient blip becomes a feed that silently shows nothing, or reads the
wrong container.

Concretely, a failure now propagates rather than becoming a plausible
empty value:

| Read | 404 means | other failures |
|---|---|---|
| WebID profile | no profile document | rejects (not cached) |
| storage root | fall back to WebID origin | rejects |
| type index | no index published | rejects |
| `contacts.ttl` | you follow nobody yet | rejects |
| someone's posts | — | rejects, recorded per author |

`state/fetch-authors!` catches per author, so one unreachable contact
still can't break the whole feed — but it records *why* in `:unreadable`
and the feed shows it, rather than rendering as though that person had
posted nothing. The "No posts yet" message only appears when nothing
failed; otherwise it would be a guess presented as a fact.

Deliberately still forgiving, because the fallback is genuinely
harmless: display names and avatars (you get the WebID host instead),
registering the posts container (a courtesy to other apps, and must
never fail the post), and individual unreadable post resources — though
all three now log to the console rather than vanishing.

Failure caches are evicted rather than kept, so one bad moment can't
poison a whole session.

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

### Reading pods you have no account with

Solid's whole premise is that you read other people's pods, on servers
you've never authenticated to. But an access token only means anything
to the provider that issued it, and servers disagree about what to do
with one they can't validate. Some ignore it and serve whatever is
public. **Inrupt's reject the request outright with a 401** — including
for resources that are world-readable to a browser sending no
credentials at all:

| Request | Anonymous | Token from another issuer |
|---|---|---|
| `id.inrupt.com/<user>` (a WebID document) | 200 | 401 |
| `<storage>/profile` (public extended profile) | 200 | 401 |
| `<storage>/publicTypeIndex.ttl` | 200 | 401 |

So a session on solidcommunity.net could read *nothing* on an ESS pod:
not the WebID document, not a public profile, not a public type index.
Following someone on the other provider failed at the first step and
showed only their host, because every stage of
[discovery](#finding-where-posts-live) was a 401.

`auth-fetch` therefore falls back: when a **read** comes back 401 and we
are logged in, it asks once more with no session attached. Once
credentials have been refused, public access is the only access left, so
there is nothing else to try.

It's safe in the cases it doesn't help. A genuinely private resource
answers 401 anonymously too, and the caller sees the same failure one
round trip later. It can't disguise an expired session for the same
reason. And it's reads only — a write the server refused our credentials
for will not succeed without them.

Worth knowing that this makes a 401 cost two round trips whenever it's
real. That's the right trade here: the alternative is a whole class of
pod being unreadable.

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
| access control resources | |

Access control belongs in the left column for a sharper reason than the
rest. Every read of it happens immediately before or after a write to
the very same resource — the sharing pane reloads after a grant, and
both inheritance paths are read-modify-write. A cached copy there
doesn't just show stale information, it gets **written back over
whatever the server actually holds**. It also makes a change that
worked look like a change that did nothing, which is a bad way to spend
an afternoon.

The right-hand column is either immutable once written or already
memoised in-process for the session (`pod/profile+`, `pod/type-index+`),
with those in-memory caches dropped explicitly — on logout, and after
registering a type so the next read sees the registration.

### Names and avatars

A WebID document has to be publicly readable: an app must fetch it to
discover your `solid:oidcIssuer` *before* you've authenticated to that
app, so it can't be access-controlled without a chicken-and-egg problem.
Anything in it is therefore permanently public.

Providers respond to that differently. solidcommunity.net puts your
name, photo and contact details straight in the card. Inrupt's pods keep
the WebID document to the plumbing — `oidcIssuer`, `pim:storage` — and
link the personal details with `rdfs:seeAlso`, in a document whose
access you control.

`load-profile+` handles both: it reads the WebID document first, and
only follows the linked profiles (via solid-client's `getProfileAll`)
when that document carries no name. Those documents are often *not*
readable by you, which is the intended design rather than a failure —
so it falls back quietly to the WebID's host as a display name.

**The type index lookup follows the same path**, and there it isn't a
nicety. Inrupt's docs are explicit that a new pod's extended profile
[is private by default](https://docs.inrupt.com/ess/2.4/services/service-pod-management/service-pod-provision),
and the WebID document itself is served by their identity service rather
than the pod — so the linked profile is the *only* document its owner
can write. Without following `rdfs:seeAlso`, an ESS pod simply cannot
publish anything discoverable.

### Which app wrote a post

Posts carry `as:generator` pointing at the client identifier document of
the app that wrote them, and each post shows a **via** chip naming that
app. The point is aggregation: a feed assembled from several pods is
also a feed assembled by several apps, and the chip is what makes that
visible. Anything with a published identity gets named, not just ours.

The name comes from the `client_name` in the generator's own document —
apps name themselves, and there's nothing to maintain here as new ones
appear. Each distinct generator is fetched once per session
(`state/load-app-names!` claims the entry before the request, so a feed
of fifty posts from one app still makes one call).

That fetch is a **plain `js/fetch` with `credentials: "omit"`**, not
`auth/auth-fetch`, and the distinction matters. These documents are
public by definition — an identity provider dereferences one, unattended
and unauthenticated, before it will log anyone in. Sending the session's
`Authorization` and `DPoP` headers would gain nothing and cost the
request its *simple* status, obliging the browser to preflight it; the
host serving the document is under no obligation to answer `OPTIONS`,
and GitHub Pages, where ours live, returns 405. Credential-free, the GET
needs no preflight and the `access-control-allow-origin: *` on the
response is enough.

When the document can't be read, the chip falls back to a name built
from the URL's **path**, not its host: one origin can serve any number
of apps, so `insano10.github.io` identifies nothing while
`podbay/comms` identifies the app. The trailing segment is the
document's own filename and is dropped. The full URL is always in the
chip's tooltip.

### Mentions

Typing `@` in the composer offers the people you follow; picking one
splices their name into the text. Mentions are stored as `as:tag`
pointing at an `as:Mention`, which is a subtype of `as:Link` and so
carries both halves:

```turtle
<#post> a as:Note ;
    as:content "morning @Alice, did the pod sync?" ;
    as:tag <#mention-0> .

<#mention-0> a as:Mention ;
    as:href <https://alice.example/profile/card#me> ;
    as:name "@Alice" .
```

The post and its mentions are separate subjects in one document, so a
post still costs one request however many people it names.

The tag is a **relative** reference, and that's deliberate. `addUrl`
accepts a Thing, and while both are still local nodes the reference
stays local, so it serialises as `<#mention-0>` rather than repeating
the document's own address. A document that names its own location
breaks when it moves: Airlock can move a post, or the container holding
it, and an absolute tag would go on pointing at where the post used to
be — `mentions-of` would find nothing there and the mention would
quietly render as plain text. A relative reference resolves against
wherever the document now is.

**Not `as:to`.** That's ActivityPub's delivery list, and it doubles as
the visibility model there: `as:Public` in `to` means public, and a post
naming only Alice means a direct message to Alice. Comms has no delivery
— readers pull from containers — so the routing job is vacuous, and
visibility is the container's access control. Writing `as:to` would
therefore label ordinary bulletin-board posts as DMs to anything that
speaks the convention, in files we can't recall. An absent property is
honest silence; a wrong one is a claim other apps will act on. If a real
visibility feature turns up later, `as:to` is unspent and can be used
properly.

**Both halves of a mention are needed.** The `href` is the durable
identifier — display names change, and are anyway just what the author's
app happened to call someone — while the `name` is what lets a reader
find the mention in the content and highlight it in place. Storing the
literal text rather than a character offset is what makes this robust:
an app that reflows or rewrites the content can't leave a link pointing
at the wrong words, and one that ignores tags entirely still shows
`@Alice` as ordinary text.

**The text is the source of truth.** Mentions are re-derived from the
finished post at save time rather than tracked while typing, so editing
a sentence can't leave the RDF describing a mention the text no longer
contains — deleting half of `@Alice` simply means she isn't mentioned.
The autocomplete is only a typing aid. Matching is longest-name-first
against your contacts, which is what makes `@Alice Smith` win over
`@Alice` despite the space, and it's bounded so `@Alice` isn't found
inside an email address or in `@Alices`. An `@name` that isn't one of
your contacts stays plain text: a mention has to resolve to a WebID, and
Solid has no directory to look a stranger up in.

Mentioned people get their profiles fetched like anyone else, since a
post can mention someone you don't follow and nothing else in the feed
would look them up.

The scanner is pure and lives in `podbay.comms.mentions`, separately
from both the pod I/O and the views, because it's the one piece of this
that's worth testing without a browser or a pod.

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

An attachment that can't be fetched says so, with the reason, rather
than degrading to a bare link — a failure and a deliberate link used to
render identically. A **404** in particular is called out as "the post
still points at where it used to be", because that's what it almost
always means: media was moved or renamed and the post, which references
it by absolute URL, wasn't updated. Nothing rewrites those references —
see the move caveats under [Airlock](#airlock).

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
| `public/clientid.jsonld` | `Comms` — the published app | the GitHub Pages URL |
| `public/clientid-dev.jsonld` | `Comms (local development)` | `http://localhost:8080/` |

They're split because anyone may use a published client ID in their own
authorization request. If the real app's document permitted localhost
redirects, someone could compose a request that shows the victim a
consent screen reading "Comms" and have the code delivered to
`localhost` on the victim's own machine — harmless unless something
hostile is listening on that port, but developers do run things on 8080,
and on node-solid-server trust is granted per *origin*, so a single
`http://localhost:8080` entry covers every app ever run there. Keeping
localhost on a throwaway identity that carries no reputation removes the
incentive, and that document can be rotated or deleted at will.

The ID used at build time comes from a `goog-define` in `auth.cljs`, set
per build in `shadow-cljs.edn`:

- `npm run release` → the published identity.
- `npm run dev` → the development identity, whose redirect URIs are
  localhost only.

**A client document must be live before any build points at it.** A
`client_id` the provider can't dereference doesn't degrade — it fails
the whole login. solidcommunity.net answers **500** at its authorization
endpoint and leaves the browser on its own error page, which reads like
a server fault rather than a dangling URL. So when these URLs change,
deploy first and repoint the `:dev` defines after; `#_` on the
`client-id` pair falls back to dynamic registration meanwhile.

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
   `curl -sI https://insano10.github.io/podbay/comms/clientid.jsonld | grep -i content-type`
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
| `podbay.comms.core`   | Entry point: mounts the UI, restores the session           |
| `podbay.comms.views`  | Reagent components (login, composer, contacts, feed)       |
| `podbay.comms.state`  | Single app-state atom and the actions that mutate it       |
| `podbay.comms.pod`    | All pod I/O — wraps `@inrupt/solid-client`, returns cljs data |
| `podbay.comms.vocab`  | ActivityStreams, FOAF, vCard and type-index terms          |
| `podbay.shared.auth`   | Solid-OIDC login/logout/session, and the retrying pod `fetch` |
| `podbay.shared.vocab`  | Generic RDF/LDP terms both apps need                       |

`pod.cljs` is the only namespace that touches JS objects; everything
above it works with plain Clojure maps.

Naming: a **`+` suffix means the function returns a promise**
(`load-posts+`, `media-url+`). That's a project convention rather than
anything standard — Clojure reserves `?` for predicates and `!` for
side effects, but has settled on nothing for async — so it's worth
keeping applied consistently.

## Current limitations / next steps

- **Comms doesn't manage access; Airlock does.** New resources inherit
  your pod's defaults, which usually means private, so friends can't see
  your posts until the posts container is shared with them. Airlock does
  that — open the container, ⓘ, grant their WebID read. Comms now at
  least *says* who will be able to read what you're writing (see below),
  but publishing and sharing remain two separate acts.
- **Mentions don't notify, and there's no "mentions of me" view.** The
  data supports both — `as:tag` is queryable and the WebID is exact —
  but a mention only reaches someone if they already follow you and
  refresh, since there are no inboxes here. Filtering the feed to posts
  mentioning you is the obvious next thing and needs no new storage.
- **No per-post visibility.** A post's audience is whatever the
  container's access control says; nothing addresses a post to one
  person. Doing that properly means writing each post an ACL
  (`setAgentAccess` abstracts WAC and ACP, and the read path already
  skips posts it gets a 403 on) — but attachments are separate resources
  needing the same treatment or the restricted post leaks its own
  images, listing a container needs read access on the container itself
  so recipients would still see the *filenames* of posts they can't
  open, and each post grows an extra round trip on a path that's already
  latency-bound. `as:bto`/`as:bcc` can't be done honestly at all: they're
  meant to be stripped during delivery, and there is no delivery step
  here, only a document people read.
- **No pagination.** Every post from every contact is loaded on refresh,
  and since each post is its own resource, that's one request per post
  (they run in parallel, but the round trips add up). Fine for
  personal-network scale; beyond that you'd want container-level date
  filtering or a summary index resource.
- **The contacts list is still found by convention**
  (`podbay/comms/contacts.ttl`), unlike posts. Registering it would mean
  choosing an RDF class for "a list of people I follow", and there isn't
  an established one — ActivityStreams models `as:following` as a
  property of an actor pointing at a collection, not as a class you can
  register. It's also private app state rather than part of the
  interoperable surface, so the convention costs nothing today.
