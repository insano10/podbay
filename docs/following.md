# Following someone, without the fiddling

**Status: step 1 built, the rest is design.** Audience containers and
the manifest exist; the grant panel, `shared-with/` and the inbox
don't. See [Suggested order](#suggested-order) for what that means, and
the README for how the built part behaves.

Recorded so the reasoning survives, not just the conclusions — the
conclusions are the easy part to reconstruct.

## The problem

Following works today, and it's clunky. Alice follows Bob in Comms;
then, for her to see anything, Bob must open **a different app**
(Airlock), find his posts container, and understand a sharing pane.
Nothing tells him Alice asked. In practice you tell the other person
out of band, which is fine when you're both parties and hopeless
otherwise.

The two-party handshake itself is worth keeping. Alice's intent lives
on Alice's pod, Bob's grant lives on Bob's, either can withdraw
unilaterally, and no third party holds both halves — which is a
genuine improvement on a centralised social network rather than a
consolation for lacking one. What needs to go is the *ceremony*, not
the consent.

Two problems hide inside one: **notification** (how does Bob learn?)
and **granting** (how does he act?). They're separable, and granting is
where most of the friction is.

## Principles

- **Privacy first. Nothing public by default.** Every grant is to a
  named person, and widening to the world is never a side effect of
  anything.
- **The access change is the thing the user asked for**, described in
  the words they used. "Let Alice read your posts", never "Alice
  followed you, so we opened your container."
- **Comms may only grant on containers it created**, recorded in a
  manifest rather than inferred from names.
- **Comms may never make anything public.** Widening to everyone stays
  in Airlock, where it's the declared purpose and already confirmed.
- **Never grant control, never remove someone else's access.** Both
  lines already hold in Airlock.
- **Public is exclusive when composing.** Choosing it greys out the
  other audiences: public is a superset of all of them and then some,
  so the combination is never what anyone means, and writing both would
  send two copies to everyone who can read either.
- **A WebID is matched exactly, never normalised.** No trailing-slash
  tolerance, no case folding, no adding or dropping `#me`. Those are
  different IRIs, and `…/card` legitimately denotes the *document*
  while `…/card#me` denotes the person — so treating them as equal
  isn't merely loose, it's wrong. Every normalisation rule is also a
  judgement call that multiplies with the next, and each one you get
  wrong produces a filename or a grant that silently misses.

  Consequence: a WebID given in a different form from the one someone
  authenticates with simply doesn't reach them, and they see nothing.
  The right mitigation is to **verify a WebID when it's entered** — the
  profile is public, so fetch it and say so if it isn't one — rather
  than to guess later. Failing at entry is cheap; failing at
  fetch-time is a mystery.

  Trimming whitespace from a pasted string is not normalisation; it
  removes an input accident rather than reinterpreting the identifier.
  That line is worth keeping straight.

## Is it acceptable for Comms to write access control at all?

Yes, bounded. Sharing is a user action, not system administration, and
requiring a file manager for it is the barrier being removed here.
Solid anticipates apps doing this: they act under a client identifier
the user consented to, and ACP can write rules naming the app itself.

The overreach isn't "an app changed permissions". It's a grant reaching
further than the user pictured. A container registered in the type
index for `as:Note` may hold notes from **several apps** — granting
read there means "everything anything ever filed as a Note", while the
user was thinking "my posts". That's overreach whether or not Comms
created the container.

Hence the container model below: the blast radius of a grant is exactly
the posts Comms filed under one audience.

## Container model

**One container per audience, created by Comms.** `Friends`, `Work`,
whatever the user wants — each is a container Comms made. A grant
applies to one audience container, so it covers exactly the posts filed
there.

**Under the app's own path, not inside a registered posts container.**
An earlier draft said "beneath the registered posts location", which
assumed a single registered parent. A pod may instead register the leaf
containers themselves — and those are *already shared*, since that is
what an audience is for. Access inherits downwards, so an audience
created inside one is born readable by whoever could read its parent.
A new audience must start private, and the only place the app can be
sure of that is a container it owns beneath the storage root, whose
access is the pod's own default. Discoverability comes from the type
index rather than from where the container sits, so nesting buys
nothing.

**Names are opaque.** `social/posts/a7f3c9/`, not
`social/posts/acquaintances/`. Two reasons: the follower is told the
container URL when approved, and nobody should learn they've been filed
under "Acquaintances"; and the names collectively leak the shape of the
user's social life to anyone who can list the parent.

The cost is honest: browsing your own pod in Airlock shows meaningless
folder names, which cuts against "your data, legible without the app".
Putting the label in the container as `rdfs:label` would fix that and
re-open the leak to anyone granted read. The trade taken here is opaque
names plus a private manifest.

**A manifest, not a naming convention.** `podbay/comms/audiences.ttl`
maps label to container URL. It is the operational form of "containers
Comms created": checkable, survives the user renaming things in
Airlock, and gives the composer its audience picker for free. Comms
verifies a container still exists before granting, rather than writing
into a hole.

For a posts container that predates Comms, an explicit **adopt** step —
"manage this container with Comms" — adds it to the manifest. Consciously
saying Comms may grant on it, rather than Comms silently claiming it.

## Posting to more than one audience

A container holds a post; a grant covers a container. So one post
reaches exactly one audience, and "friends and family" doesn't fit.
Three ways round it, and none is free.

**Fan out on write — a copy in each audience container.** Chosen.
Works with everything already built, and it's the only option that
leaks nothing. The costs are real: N writes to post, N to edit, N to
delete, and the attachments duplicate too.

That last part is why it works rather than a flaw: media lives *inside*
the posts container precisely so one grant covers both, so each copy's
attachments are covered by that audience's grant with no extra rules.
You are storing the photo twice.

It needs one addition. Deduplication in `load-posts+` is keyed on
`:id`, which is the resource URL — so two copies are two URLs and would
appear as **two posts in the feed**. Each copy therefore needs a
**stable identifier shared across copies** (a `dcterms:identifier`
carrying a UUID would do), with the URL as the fallback key. An app
that doesn't understand it shows duplicates: annoying, not broken.

Edit and delete become "find every copy". Comms can know where they are
without searching, by recording the audiences on each copy as it writes.

### Why dedupe, when a follower is in only one audience?

The obvious objection, and worth answering because it's right up to a
point. If Alice is in exactly one audience she has one `Accept`, one
source, and sees one copy. She never even *tries* the other container —
private audiences aren't publicly registered, so she has no URL for
one. No duplicate arises.

It stops holding for three reasons.

**Public is discoverable independently.** A public container *is* in
the public type index, so a follower finds it whether or not they were
told. Post to Public and Friends and everyone in Friends gets both
copies. Handled by treating **Public as exclusive in the composer** —
choosing it greys out the other audiences — since public is a superset
of every audience and then some, so the combination is never what
anyone means. That's a UI rule rather than a data rule, which is why
it doesn't remove the need for the identifier on its own.

**People belong in more than one audience.** Take Alice (friend), Carol
(colleague) and Dave (both). With one audience each, Dave goes in one
and systematically misses half of what's meant for him. The only
workaround is to post to Friends *and* Work whenever Dave should see
it — which reaches Alice and Carol too, so "friends plus Dave" can't be
expressed without also reaching every colleague. Letting Dave be in
both fixes it directly and widens nobody else's audience.

The asymmetry that makes this work: **audiences are distribution lists,
and posting selects a set of them.** Membership answers "who is this
person to me"; posting answers "who is this for". Requiring single
membership conflates the two.

**The invariant isn't enforceable anyway.** Comms can only hold that
rule inside Comms. Airlock can grant anyone read on any container —
that is its purpose — so one manual grant puts a follower in two
audiences without anybody doing anything wrong. A feed whose
correctness depends on nobody using the file manager is not correct.

So the identifier stays. It costs one triple per post and one changed
dedupe key, and it buys multi-membership, a safety net for when the
model is bypassed from outside, and the honest statement that two
documents are the same post — which edit and delete need regardless.

**One container, per-post access rules.** Rejected. Keeps a single
copy, but every follower who can list the container sees the
**filenames** of posts they can't open — and names are timestamps, so
that leaks when you post, how often, and that there is something you're
not showing them. It also costs a second write per post, and on WAC
giving a post its own ACL detaches it permanently from the container's
rules.

**Pointers — one canonical copy, audience containers holding
references.** Rejected, and it doesn't survive inspection: the
canonical post must be readable by everyone in *any* audience pointing
at it, which reduces to either per-post rules again or one container
granted to the union of all followers. In that second case anyone with
any grant can read anything whose URL they can guess, and the names are
timestamps. Discovery would be controlled while access wasn't, which is
the wrong way round.

This is the ordinary fan-out-on-write trade, and Solid pushes towards
it: access control is per-container and per-resource, with no notion of
"one document, visible to these three groups" that doesn't reduce to
one of the above. The cost is proportional to how often you post to
several audiences at once, which for most people is occasional.

## Discovery

Three questions, and they have different answers: how does the **owner**
find their own posts, how does a **follower** find what they've been
granted, and what does the **world** get to know.

### Public audiences

Registered in the public type index, like any other posts container. A
follower reads the index, tries each container, and gets what they're
allowed. `load-posts+` already merges per source and skips a refused
one, failing only when *every* source fails.

### Private audiences: not registered anywhere public

The public type index is for data whose *location* you're content to
publish. Solid ships the pair — `publicTypeIndex` and
`privateTypeIndex` — precisely to separate that from data whose
location you aren't. Registering a private audience in the public index
misuses the wrong half of a mechanism designed with this exact split in
mind, and it is surprising in its own right: it advertises a container
to the whole world and then refuses everyone.

Registering with opaque names was considered, on the grounds that it
leaks only a *count* rather than the shape of someone's relationships,
and that it would make the inbox a convenience rather than a
prerequisite. Rejected for the reason above. The count is also
permanent and public, and grows.

### The owner's own feed

Missed on the first pass, and worth stating because it's the sort of
thing that looks obvious only afterwards: type-index discovery is how
Comms finds posts for *everyone*, including you. An unregistered
audience is therefore invisible to its author, and posting there
appears to do nothing at all.

Comms reads its own manifest as a source when loading your own posts.
Only your own — another person's manifest is private to them, so asking
would cost a request per contact and be refused every time.

`solid:privateTypeIndex` is the standard answer here and would make
private audiences discoverable to other Solid apps rather than only to
Comms. It isn't usable as *the* mechanism because it isn't universal:
of the two pods this is developed against, solidcommunity.net
advertises one and ESS does not. Worth revisiting if that changes.

### A follower's own map: `shared-with/`

One small document per follower, listing only the containers **that
person** may read:

```
podbay/comms/shared-with/https%3A%2F%2Falice.example%2Fcard%23me.ttl
```

```turtle
<> solid:instanceContainer <https://bob.example/podbay/comms/posts/3e739645/> .
```

Granted read to that follower alone. Alice fetches hers, then the
containers it names: **two requests per contact, and no 401s**.

**Named for its reader, not hashed.** Only three parties can ever see
that filename: the owner browsing their own pod, the follower it
belongs to — for whom it is their own WebID — and everyone else, who
gets 401 and cannot list the container either, since reading a resource
you've been granted needs no access to its parent. Hashing would
defend only against someone who could list the container but not read
its contents, which is nobody. Percent-encoded, Airlock's `entry-name`
decodes it back to the plain WebID when browsing.

Note this is the opposite rule to audience container names, and the
difference is the point: an audience container's URL is handed to a
*third party*, so `acquaintances/` would tell them what you think of
them. A `shared-with` filename has no third party to protect, so
legibility wins.

**Revoking the last audience deletes the document.** Revoking one of
several rewrites it with what remains; revoking the last removes the
file rather than leaving an empty one. Tidier, and it means revocation
leaves no trace that says "you were once let in" — a deleted document
and one that never existed are indistinguishable to the person who
can't read it, since both come back the same way. The read path needs
no new case either: missing and refused are already skipped alike.

**The `shared-with/` container must never itself be granted read.**
Only individual files inside it. Granting on the container would hand
every follower the complete list of everyone else who follows you —
and since a container grant now propagates to its contents by design,
this is the one place that behaviour would be actively wrong.

Deriving the name rather than announcing it is what keeps the inbox out
of the discovery path. It is not the security: the security is the
grant. Both pods this is developed against return an identical 401 for
"exists but forbidden" and "does not exist", so guessing a filename
reveals nothing — including to a follower guessing another follower's.

### Alternatives considered

| | Requests per contact | Public leak | What followers learn |
|---|---|---|---|
| Register privately-shared containers publicly | N, mostly 401 | audience count, permanently | count |
| One shared `sources.ttl` listing every audience | 1 + N, mostly 401 | none | count |
| **Per-follower `shared-with/`** | **2** | none | nothing |
| `Accept` carrying the URL, via the inbox | 1 per container | none | nothing, but requires an inbox on both sides |

The shared-document version was the first attempt at avoiding the
public leak and is strictly worse than registering: the follower still
loops every audience and collects the same 401s, having spent an extra
request to learn which ones to try.

**Staleness is self-healing.** If an audience is deleted without every
`shared-with` document being updated, the stale entry is refused or
missing and gets skipped — the read path already tolerates exactly
that. So no urgent fan-out on delete.

**Someone in several audiences is free**: their one document lists
both containers, so it stays at one request plus what they can read.

## The inbox

**Notification only.** An earlier draft made the inbox the sole way a
private audience could ever be discovered, which quietly promoted it
from convenience to prerequisite — every follower needed one, and
without it the feature couldn't work at all. `shared-with/` removes
that: the inbox now exists so Bob *learns Alice asked*, and nothing
else depends on it.

`ldp:inbox` is the standard place to receive a notification, and
`as:Follow` / `as:Accept` is ActivityPub's own model, so this is using
the vocabulary as intended rather than inventing a protocol.

**Append is not Read.** A sender can POST a message and cannot read the
inbox or anyone else's messages. So an inbox open to strangers is a
junk risk, not a privacy risk — worth being clear about, because the
instinct is to assume otherwise.

**Default to authenticated-only, not public.** Both servers support the
middle tier — WAC `acl:AuthenticatedAgent`, ACP `acp:AuthenticatedAgent`
(`setAuthenticated` in the API). You need a WebID to post, which costs
something to obtain and gives you something to block.

**Never create one silently.** Opening a new write surface on someone's
pod is security-relevant, and doing it as a side effect of "I want to
follow someone" is overreach. So:

- Already has an inbox → use it; its access is their choice, made.
- Doesn't → ask once, plainly: *"To receive follow requests, Comms
  needs a place people can leave you a message. This creates a
  container anyone signed in with a WebID can write to — they can leave
  messages, not read anything. You can remove it at any time."*
- Declines → following still works, manually.

A Comms-private inbox was considered and rejected: `ldp:inbox` is the
one standard discovery point, so a bespoke pointer buys nothing against
spam and gives up interoperability with other Solid social apps.

## The flow

Alice follows Bob.

**Alice asks**

1. Alice pastes Bob's WebID into Comms.
2. Comms fetches Bob's WebID document — public by necessity — following
   `rdfs:seeAlso` for an ESS-shaped profile. It reads `ldp:inbox` and
   `solid:publicTypeIndex`.
3. No inbox check is needed on Alice's side. An earlier draft required
   one, because the `Accept` was the only way she'd learn the container
   URL; now she derives it, so a missing inbox costs her a prompt
   update rather than the whole grant.
4. Comms adds Bob to Alice's `contacts.ttl`, marked **pending**.
5. Comms POSTs to Bob's inbox:

```turtle
<> a as:Follow ;
   as:actor <https://alice.example/card#me> ;
   as:object <https://bob.example/card#me> ;
   as:published "2026-01-01T00:00:00Z"^^xsd:dateTime .
```

6. Alice's UI shows Bob as *requested*. Her feed already shows any
   **publicly** registered posts of his — partial value immediately, no
   approval needed.

Alice cannot read her own POST back; an append-only inbox means she
knows only the HTTP status.

**Bob approves**

7. Bob opens Comms. It lists his inbox and keeps entries typed
   `as:Follow` whose `as:object` is him.
8. It fetches Alice's public profile for a name and avatar.
9. Followers panel: *"Alice wants to follow you"* — **Allow** (choose
   audience) / **Ignore**.
10. Bob picks **Friends**. Comms resolves that to a container URL via
    the manifest.
11. Comms writes three things, all read only, never control:
    - the audience container's own access, so Alice can list it
    - its inherited access, so she can read the posts inside
    - Alice's `shared-with` document, naming that container, granted to
      her alone
12. Comms deletes the `Follow` from Bob's inbox — acted on.
13. Bob's panel shows Alice under *Friends*, with Revoke.

No `Accept` needs to carry the container URL: Alice's `shared-with`
document is at a name she can derive, so she finds it herself. An
`as:Accept` is still worth posting so her UI can stop saying
*requested* without waiting for a refresh, but it is a courtesy rather
than the mechanism.

**Alice reads**

14. Alice's Comms fetches `shared-with/<her WebID>.ttl` from Bob's pod.
    401 means she hasn't been granted anything, and is skipped exactly
    as an unreadable source is today.
15. It reads the containers named there, alongside Bob's public
    registrations.
16. Posts appear; Bob moves from *requested* to *following*.

Two requests per contact, and no 401s once granted.

## Severing

**Alice unfollows** — removes Bob and his recorded sources from
`contacts.ttl` and stops looking. Optionally POSTs an `as:Undo` so
Bob's panel drops her. Bob's grant lingers harmlessly; she isn't using
it.

**Bob revokes** — Comms clears Alice from the audience container.
Alice's next refresh 401s there and the source drops out. Optionally an
`as:Undo` to her inbox, so her UI can say why rather than silently
losing posts.

**Bob re-files Alice** into a different audience — a new `Accept` with
the new URL plus an `Undo` for the old; Alice's Comms swaps the source.
No new machinery.

## Degrading

The inbox buys one thing: Bob learns that Alice asked, without her
having to tell him some other way. Everything else works without it.

| | Bob has an inbox | Bob doesn't |
|---|---|---|
| **Alice has one** | Full flow: request arrives, Bob allows, Alice's UI updates promptly | Alice tells Bob out of band; he allows; her feed picks it up on the next refresh |
| **Alice doesn't** | Request arrives; Bob allows; Alice's feed picks it up on the next refresh | Fully manual, and still works |

Every cell is usable, which wasn't true before. Discovery no longer
depends on notification: whatever Bob grants, Alice finds through her
`shared-with` document.

That also means **Comms should not create an inbox to make following
work** — the earlier draft's justification for asking to create one. It
can offer to, so requests arrive rather than needing to be passed on by
other means, but declining costs a convenience rather than the feature.

## Failure modes

- **Bob has no inbox** — say so and fall back to manual, rather than
  recording a pending state that can never resolve.
- **Inbox POST refused (403)** — "couldn't ask Bob", not a silent
  pending.
- **Alice has no inbox** — no longer a failure. Bob approves, and her
  next refresh finds the grant through her `shared-with` document. She
  simply isn't told the moment it happens.
- **Bob never responds** — pending indefinitely, no retries.
- **Alice unfollowed before Bob accepted** — an Accept for a contact
  she doesn't have; ignore, or offer as a prompt.
- **Spam** — show only requests whose WebID resolves to a real profile,
  and make Ignore one click.

## New state

| Whose | What | Why |
|---|---|---|
| Bob | `podbay/comms/audiences.ttl` | which containers Comms may grant on, and their labels |
| Bob | inbox | receiving `Follow` |
| Alice | per-contact status in `contacts.ttl` | pending vs following |
| Bob | `podbay/comms/shared-with/<webid>.ttl` per follower | what that person may read; the only thing they need to find it is their own WebID |
| Alice | inbox | receiving `Accept` / `Undo` — courtesy, not mechanism |
| every post | a stable identifier, and the audiences it went to | dedupe across copies; finding every copy to edit or delete |

No per-contact source list: it was needed only because the `Accept`
was the sole carrier of the URL. Alice derives the location of her
`shared-with` document from her own WebID, so there is nothing to
store, nothing to keep in step, and nothing to go stale.

The reading side needs almost nothing else: `load-posts+` already
merges several sources per author and skips those it's refused, so
"sources from the type index" plus "sources named in my shared-with
document" is one concatenation. Its dedupe key is the one thing that
changes — from the resource URL to the shared identifier, falling back
to the URL.

## Suggested order

Each step ships something on its own. An earlier draft made the
Followers panel step one, which bundled a prerequisite with a feature
and named the step after the half that can't work yet: without an
inbox, the pending-requests list is necessarily empty.

1. **Audience containers and the manifest.** *Built.* Comms creates its
   own containers under its own path, records `label → URL` in
   `audiences.ttl`, and the composer picks by label.

   Useful on its own, independent of following. The composer's
   destination picker currently labels containers with the last two
   path segments, so opaque names would read `posts/a7f3c9` — the
   manifest is what makes it say **Friends**. It also stops Comms
   writing into a container it may not own, which is where this whole
   design started.

2. **Grant panel, with `shared-with/`.** Paste a WebID, pick an
   audience, Allow; Revoke alongside. Removes the trip to Airlock, the
   container navigation and the sharing pane — the friction this is all
   about. The access-writing code moves from `airlock/pod.cljs` into
   `podbay.shared`.

   The `shared-with` document belongs in the same step rather than a
   later one: a grant the recipient cannot discover does nothing, so
   splitting them would ship a feature that appears not to work. Still
   needs the other person to tell you out of band that they'd like
   access.

3. **Inbox notification.** The panel gains a second source of entries —
   requests that arrived on their own — so nobody has to be told out of
   band. Genuinely last, and genuinely optional: with `shared-with/`
   doing discovery, the inbox only saves a conversation.

Deliberately **not** in step 1: posting to several audiences at once.
That needs the shared identifier and the dedupe change, and it's a
separate increment on the same code path. Step 1 posts to exactly one
audience, which is what the composer does today.

## Still open

- Migrating an existing flat posts container into audience containers:
  move the posts, or adopt the container as a single audience?
- Whether the manifest should be discoverable by other Podbay apps, or
  stay private to Comms.
- Whether Airlock should learn to read the manifest, so opaque
  container names are legible when browsing your own pod.
- Whether `Undo` on revoke is worth the extra write, given the follower
  finds out anyway on the next refresh.
- Which identifier a multi-audience post should carry.
  `dcterms:identifier` with a UUID is the obvious choice, but a reader
  that doesn't know the convention shows duplicates — so it's worth
  checking whether anything in ActivityStreams already means "these are
  the same post filed twice" before inventing one.
- Whether adding an audience *after* posting should retroactively copy
  the post there, or only apply from then on. Copying is what people
  will expect; it also means an old post can silently reach someone new,
  which is the sort of surprise this design is otherwise avoiding.
- Whether duplicated attachments should be deduplicated by content
  hash. It would save the bytes, but a shared media resource needs
  access for the union of the audiences referencing it, which is the
  problem the pointer approach failed on.

