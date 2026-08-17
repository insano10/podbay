# Following someone, without the fiddling

**Status: design only. None of this is built.** Recorded so the
reasoning survives, not just the conclusions — the conclusions are the
easy part to reconstruct.

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
whatever the user wants — each is a container Comms made, beneath the
registered posts location. A grant applies to one audience container,
so it covers exactly the posts filed there.

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

**Register each audience container in the type index, not the parent.**
Registering only the parent would force a follower to *list* it to find
the audiences inside, which needs read on the parent — exactly the
grant being avoided. It wouldn't work anyway: `posts-in-container+`
drops sub-containers. Registering each audience means a follower reads
the type index, tries every container, and the ones they aren't allowed
get 401 and are skipped. `load-posts+` already merges per source and
only fails when *every* source fails.

**Except private audiences, which aren't registered at all** — see
below. The public type index is world-readable, so registering
`friends/`, `family/`, `work/` would publish the structure of someone's
relationships. Private audience containers are conveyed to approved
followers directly instead.

## The inbox

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
3. Comms checks **Alice** advertises an inbox too. If not, it says so
   now rather than sending a request that can't be answered.
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
11. Comms writes two things on that container, read only, never control:
    - the container's own access, so Alice can list it
    - the inherited access, so she can read what's inside
12. Comms POSTs to **Alice's** inbox, carrying the container URL — the
    discovery payload that replaces public registration:

```turtle
<> a as:Accept ;
   as:actor <https://bob.example/card#me> ;
   as:object <https://alice.example/card#me> ;
   as:target <https://bob.example/social/posts/a7f3c9/> .
```

13. Comms deletes the `Follow` from Bob's inbox — acted on.
14. Bob's panel shows Alice under *Friends*, with Revoke.

**Alice reads**

15. Alice's Comms reads her inbox and finds the `Accept`.
16. It records the container URL against Bob as an extra source.
17. It deletes the `Accept`.
18. Her feed reads Bob's public registrations **plus** that container.
    Bob moves from *requested* to *following*.

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

The inbox buys exactly one thing: **private audiences that aren't
announced to the world**. Without it the feature still works, worse:

| | Bob has an inbox | Bob doesn't |
|---|---|---|
| **Alice has one** | Full flow: opaque private container, nothing published | Manual: Alice sends Bob her WebID |
| **Alice doesn't** | Bob approves onto a *publicly registered* container; Alice discovers it by retrying, 401 becoming 200, with no notification | Manual |

The bottom-left cell is why this can ship in stages: Alice only needs
an inbox to learn a URL she couldn't otherwise discover.

## Failure modes

- **Bob has no inbox** — say so and fall back to manual, rather than
  recording a pending state that can never resolve.
- **Inbox POST refused (403)** — "couldn't ask Bob", not a silent
  pending.
- **Alice has no inbox** — fails *late*: Bob approves, the Accept can't
  be delivered, Alice never learns the URL. Hence the check at step 3.
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
| Alice | per-contact source URLs | granted containers, learned from `Accept` |
| Alice | inbox | receiving `Accept` / `Undo` |
| every post | a stable identifier, and the audiences it went to | dedupe across copies; finding every copy to edit or delete |

The reading side needs almost nothing: `load-posts+` already merges
several sources per author and skips those it's refused, so "sources
from the type index" plus "sources I was told about" is one
concatenation. Its dedupe key is the one thing that changes — from the
resource URL to the shared identifier, falling back to the URL.

## Suggested order

1. **Followers panel in Comms**, with a working Allow that writes the
   grant. Removes the trip to Airlock — the largest single win, and it
   needs no new protocol. Requires the audience containers and manifest.
   The access-writing code moves from `airlock/pod.cljs` to
   `podbay.shared`.
2. **Inbox notification**, so Bob learns without being told out of
   band.
3. **`Accept` carrying the container URL**, which is what allows
   private audiences to stay unregistered.

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
