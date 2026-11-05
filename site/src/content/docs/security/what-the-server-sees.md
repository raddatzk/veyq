---
title: What the server sees
description: The metadata a veyq server can read by design, what stays sealed, and the six deliberate concessions with their reasons.
sources:
  - docs/features/moderation/enablers/en.moderation.moderation-key.md
  - docs/features/moderation/enablers/en.moderation.log.md
  - docs/features/moderation/use-cases/uc.moderation.report.md
  - docs/features/moderation/use-cases/uc.moderation.review-a-case.md
  - docs/features/moderation/use-cases/uc.moderation.take-a-measure.md
  - docs/features/moderation/enablers/en.moderation.hold.md
  - docs/features/moderation/enablers/en.moderation.guest-write-record.md
  - docs/crosscutting/security-model.md
  - docs/decisions/0044-field-visibility-declared-at-the-field.md
  - docs/decisions/0047-inline-video-playback-range-streaming.md
  - docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md
  - docs/decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md
  - docs/decisions/0014-group-scopes-and-governance.md
  - docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md
  - docs/decisions/0092-logs-are-operational-and-name-no-person.md
  - docs/decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md
---

The server never sees content, only structure. Operator-blind does not mean metadata-blind: a defined set of
operational facts stays readable so the server can sort, paginate, route, authorize and bill without decrypting.
This page lists that set by category, what is sealed instead, and the six places where veyq knowingly shows the
operator more than the general rule would suggest
([security model § What is encrypted vs. server-visible](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-is-encrypted-vs-server-visible)).

A stricter mode that also seals operational metadata is possible, at the cost of exactly those server capabilities;
it is a conscious trade-off, not an oversight.

## How the line is held: every field declares its class

The split above is written in kinds ("sizes", "timestamps"); the API contracts are written in field names.
[ADR 0044](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0044-field-visibility-declared-at-the-field.md)
connects the two, and the build fails without it:

- A field with `contentEncoding: base64` is **opaque**: ciphertext, a wrapped key, a public key, a signature, a
  salt or a blinded token. The server derives no meaning from it.
- **Every other scalar** in `contracts/api/` carries `x-veyq-visibility` with one of nine classes: `id`,
  `structure`, `measure`, `time`, `enum`, `flag`, `token`, `public`, `pii`. An unmarked scalar fails the build.
- **`pii` also requires `x-veyq-rationale`**, naming the mechanism that forces the plaintext. The set of
  deliberately sensitive plaintext fields is therefore a standing, greppable budget, and every addition to it is
  argued in the diff that adds it.

The point is the default: silence fails. The limit is stated in the ADR itself: this checks the **contract, not
the traffic**. A client that writes plaintext into a field marked opaque satisfies it completely, and it does not
see path or query parameters, presigned-URL query strings, database columns with no contract, error message text,
undeclared headers, or the object-store key. A green run means "no unclassified field in a JSON body and no
unclassified declared header", never "operator-blind, verified". What the server actually receives is a separate
sensor ([ADR 0053](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0053-wire-assertion-canary-sweep.md));
[Verify it yourself](/security/verify-it-yourself/) covers both.

## What is sealed

Sealed client-side, never readable by the server:

- media bytes and every derivative; journal text and embeds;
- sensitive metadata: titles, captions, descriptions, places, people and faces, participant relationships, tags;
  track and playlist details; GPS position and capture time, which the client seals itself rather than letting the
  server read them off the file;
- node names (folders, Memories, chapters, files): navigation uses a per-parent keyed MAC of the name, which the
  server stores and enforces uniqueness on without being able to reverse it;
- a blob's **type, upload name and modification time**, in a seal under the blob's own key, padded to a 256-byte
  bucket so its length shows one of four sizes, never how long a name is. A Memories original's file name and media
  type (photo, video or PDF) live there, so the operator learns neither;
- bucket and uploader names, a share's note and private name, and every identity pin: whom it pins, the key's
  fingerprint and key-chain head, when it was first seen and whether a join record replaced it, each in an entry
  sealed under the Master Key;
- a member's **join record** (the keys they joined with and the owner-signed invite they came through), sealed under
  the tenant key;
- what a **verification** confirms — the two key-chain heads and the verifier's signature — sealed under the tenant
  key.

Each text or icon field (a name, a node's or a medium's metadata, a share's note and label, a member's name and
picture) seals under a key of its own, derived per write. Inside its ciphertext the server stores the 16-byte random
salt that key was derived with, one per write and 16 bytes of length more, and learns nothing about any key from it
([Cryptography](/security/cryptography/#what-a-seal-binds)).

## What is server-visible

### Identity and membership

- **Scope and membership:** who belongs to which tenant and group, and in which role.
- **Per open member invite:** the invited address, its expiry, the invite's public key, the inviting owner's
  signature over it and that owner's identity key, the tenant key wrapped to the invite key, and the owner's own such
  grant kept beside it as the invite's anchor; the key, the signature, the grant and the anchor are cleared once the
  invite is accepted, revoked, re-sent or expires. The code that opens the invite link never reaches the server; the
  link's sealed part passes through it only while it is mailed.
- **Who invited whom:** per member, the owner whose invite they joined through, recorded at the accept, and that
  owner's signed endorsement of the member's sealed join record, written when the owner secures their account
  ([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 21).
- **Per member, per tenant:** the username, the identity public key and the group-add-consent flag. Any fellow
  tenant member can enumerate these (concession 4 below).
- **Who verified whom:** per verification, its tenant, the verifying and the verified member and when it was written
  (its content is sealed, above), served to the tenant's members
  ([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 12).
- Whether a member **has a profile picture** and how often it changed (`avatar_revision`); the picture itself is a
  constant-size sealed blob. Contract, crypto and backend are built; the web client is not yet.
- The account's **Ed25519 auth public key**, which verifies a signed login challenge. It is not a credential, and
  the password never reaches the server
  ([ADR 0003](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0003-operator-blind-password-auth.md)).
- The account's **Secret-Key proof public key**, which verifies a credential change, and its **key chain**: each
  identity and proof public key the account has had, the two signatures on each step and when the step was written.
  The chain is served only to the person, to members of a tenant they are in, between two accepted moderators, and —
  an inviting owner's — to the holder of their open invite's token.
  While an account is being secured, a **stage** of its self-grants: the new identity public key it declares, one
  grant per scope as ciphertext, the new identity's signatures over the open invites it signed and its endorsements
  of the join records it rooted, and when it expires; the publish deletes it, and an abandoned one is purged
  ([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 6–7, 20 and 21).

### Structure and ids

- Existence and ids of nodes, media and shares; the tree's shape (parentage). Seals do not bind a node's parent,
  so a move re-seals one node, not its subtree
  ([ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md)
  Decision 4).
- Each node's **kind** (concession 3), its own key's **epoch** (how often that key was replaced) beside the scope
  epoch it was minted at, and whether it is **due** for a re-key.
- **Key replacement:** per scope epoch a **succession public key and signature**, the statement by which the old key
  vouches for the new one — no link from one key to another, and nothing in it opens anything; per link the scope
  epoch it was minted at and whether the end-date sweep closed it. Beside every scope-level value an author writes, the **wrap of its own value key**,
  which every write of the value and every rotation rewrites. At a rotation the server sees which wraps and which
  scope-root entries are rewritten — never a value, which a rotation leaves byte-identical — and at a link's close that the link closed and which nodes lie below it — rows and a tree it
  serves anyway. No key, and no new correlation
  ([ADR 0115](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0115-a-scope-key-is-replaced-never-chained.md) decision 9,
  [ADR 0114](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0114-closing-a-link-re-keys-the-subtree-below-it.md) decision 10).
- Per-parent blinded name tokens.
- A medium's **uploader attribution and bucket structure**: an opaque owner key per medium (`user:{id}` or
  `share:{id}`), its bucket, and the buckets' shape and counts; a Depot file's uploader attribution, served to
  members only.
- **Who last wrote each sealed field** — a folder's, file's or Memory's name and metadata, a link's name, note and label, a
  space's name and icon, a bucket's name, a medium's metadata: the member or guest identity behind the last write,
  served to no one and never what was written. It decides that a guest can take back only an empty upload of its own.
- A medium's **trash state**: a flag, who trashed it and when, until the scheduled purge.
- A Memory's **banner**: that it has one and whether it is a video (one or two attachment rows and their sizes), never
  its framing or its picture
  ([ADR 0102](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)).
- **Which gallery item a cover or banner shows, where it shares that item's stored object.** An upload into a
  Memory's cover or banner equal to a photo or video of the same Memory or chapter (and a gallery upload equal to its
  video banner) is not stored again: the new blob row names the existing blob and gets its storage key. Accepted for
  the storage it saves, and the sizes already hint at it: even unshared, a video banner's size can equal its gallery
  original's, and an image banner's or a cover's the preview's. Nothing wider is deduplicated: the same file in two
  Memories, or twice in one gallery, is two objects, and a copy or import makes each blob its own
  ([ADR 0013](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md#5-one-stored-object-for-an-equal-file-inside-one-node-nothing-wider)).
- Job-queue and op-log offsets, presence facts, and a grant-delivery decline status.
- An account's **identity-pin entries**: an opaque id per pin (keyed by a Master-Key-derived key, so it names no
  contact and differs between accounts), which entry a write touched, and how many there are — the count was
  never hidden, and the grant rows let the server count the identities a key-holder wrapped to anyway
  ([ADR 0104](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) decision 5).

### Timestamps

Stored **as written**, to the millisecond (a node's creation time is served to members, to order equal-named
siblings). Nothing rounds them: they tell *when*, never
*what*.

**A time is stored only where something reads it**: a sort, a purge or expiry cutoff, a served field, or, named as
such, an operator reading the database for audit evidence. The root test `EveryStoredTimeHasAReaderTest` names
each stored time's reader and fails on one without. So the database does **not** hold, for example, when an
account, tenant, scope, blob or upload session was created, when a share link was deleted, when a scope's key
epoch rotated, when a member re-sealed their pins or device register, or when a share guest joined; the request
time the server observes live is not kept for them. What it does hold, each with its reader, is listed in the
[security model](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-is-encrypted-vs-server-visible).

### Sizes and access

- Sizes: a blob's plaintext size is on its row; the blob-meta seal shows one of four padded sizes.
- Access: every read except range-streamed playback fetches a blob **whole**, so the operator learns only *that*
  a blob was accessed. Playback is concession 1.

### Sharing

- A share's id (stored raw: members rebuild links from it and seals bind it), its role, its state, and its
  creation time.
- Whether it has a password, and the salt and verifier that check one; the password itself is held only sealed
  under the link's own key.
- To a **public share's guest**: the shared node's scope id (concession 5) and the root node's kind.
- Nothing about an accountless visitor's acceptance of the tenant's terms: that lives in the visitor's browser.

### Mail and SMTP

- A tenant's **SMTP configuration** (host, port, from, username, TLS) and the owner-authored invite template text.
- The **send fact** of every mail veyq sends — a member invite, a password-recovery link, an address confirmation:
  recipient and send time.
- The SMTP **password** is a server-held secret sealed at rest under a key file the server manages, never stored in
  the database in the clear
  ([ADR 0050](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0050-operator-config-secret-encryption.md)).
- A mail itself carries only a link, the domain, an expiry and neutral copy, never sealed metadata.
- The instance's **own mail settings**, for its release notices to the Server Owner: the same fields, the password
  sealed the same way, the hash of an outstanding confirmation code and which address was confirmed when. A notice
  mail carries release numbers, a date and a link. The **release check** behind it stores its switch, its source and
  what it found about published releases; its daily request names no release
  ([ADR 0097](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)).

### Moderation

- **Who runs the instance** ([uc.admin.operator-details](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.operator-details.md)): the instance contact with its languages, the statement address, the provider identification and the
  supervisory authority, which are published by design and served to anyone; and the notification address in the
  clear, which the server must hold to mail it and serves only on the admin channel, with the hash of an outstanding
  confirmation code and which address was confirmed when.
- **The roster**: which accounts the Server Owner appointed as moderators, by whom and when, whether each accepted,
  and the address confirmation for moderation notices (the hash of an outstanding code, which address was confirmed
  when) ([ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)).
- **The moderation key's epochs**: per epoch its public half (what a report seals to, and the key a moderator's proof
  verifies under), why it was made, and whether a rotation is due; per moderator and epoch a grant sealed to that
  moderator's identity. Never the key itself: it opens on a moderator's device only.
- **The moderation log's structured fields**: the event (appointed, accepted, key handed on, removed, stepped down,
  identity replaced, new key; for a case: report received or attached, opened for review, dismissed, measure taken or
  lifted, proposed, proposal taken or declined, hold extended or ended, reporter barred, receipt or decision sent to a
  reporter, statement sent or deferred, objection received or decided, order received, handed to an authority), its time, the moderator it
  concerns, who acted, the epoch, a reason category, a measure's kind and target id, and an extension's new end. Free
  text — a decision's note, an extension's reason — only sealed to the moderation key in the moderator's browser, bound
  to its epoch and case (`moderation-note` [epoch, caseId]). Kept to 31 December of the third year after the
  moderator's removal; a case's events, and everything of the case below, to 31 December of the third year after the
  case closed — never while a measure of it is in force (a measure ends when it is lifted or what it stands on is
  erased) — and then deleted together.
- A key proof's challenge, for five minutes.
- **A report's clear fields** ([uc.moderation.report](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.report.md)):
  its category, the epoch it is sealed to, when it arrived, each reported item's kind and id, and who sent it — the
  reporting member's account, or the link a guest came through. The reporter's text and context, their address apart,
  and the keys to what they reported, are sealed to the moderation key. The address also crosses once in the clear, for
  the receipt the server mails, and is not stored that way; its sealed copy is deleted six months after the reporter was
  told the decision. A case per item (its category and state), a log event per report, and
  whether the "new case" mail went out.
- **A hold** ([en.moderation.hold](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.hold.md)):
  copies of the reported rows as stored (ciphertext and metadata) and the storage keys it pins, until its end — so the
  server keeps what a member deleted after a report, readable to no one but the moderators: for up to 90 days while
  the case is open (a dismissal ends it at once), six months after a measure (its lift ends it), and beyond that only
  by a moderator's extension, at most six months at a time, or for as long as an authority's order says. Child sexual
  abuse material is kept only until the handover to an authority is recorded, at the latest 90 days after the report.
- **A case's measures** ([uc.moderation.take-a-measure](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.take-a-measure.md)),
  in the clear, because the server must refuse what they name: per measure its kind (a link, content, a guest identity,
  a member's sign-in, a barred reporter), the id it names — the link, the node or item, the guest identity, the account
  —, the case it answers, who took it and when, and when it was lifted. The same for a proposal to the Server Owner
  (its step, its target, who proposed it, when, and whether it was taken or declined), and for a case its state, when
  it was decided and closed, and its hold's end. The reports stay sealed as above.
- **The review** ([uc.moderation.review-a-case](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.review-a-case.md)):
  which moderator fetched which case's report shares and when. What it serves is the hold's ciphertext and presigned
  reads of its pinned objects; the report, the report share and the content open in the moderator's browser with the
  moderation key, never on the server.
- **The guest-write record** ([en.moderation.guest-write-record](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.guest-write-record.md)),
  while the Server Owner keeps it switched on: for each write a link's guest makes (naming itself, a folder or file it
  creates, renames or moves, a photo or file it adds), the client's IP address and source port as the server's one
  resolver vouches for them, the time to the millisecond, the guest identity and the object — for N days (7 by
  default, 1 to 30). Concession 6.

### Account email

The **account email**, in plaintext (concession 2), and `email_confirmed_at`, when its holder last showed they read
that address.

### Terms acceptance

The tenant's **terms text and revision** are the space owner's public policy, served to everyone. A member's
**accepted terms revisions and when they accepted each** are kept as a history, one row per revision, never
overwritten: audit evidence for a dispute, served by no API.

### Logs

A log holds less than the database, never more. The application's lines name accounts, tenants, scopes, nodes,
shares, blobs and jobs **by id**, with a domain, an exception's class, a database's SQLState and counts. They never
carry a key, a token, a sealed value, plaintext metadata, an email address, a network address, a User-Agent, a
request's URI or what it sent, or text someone else wrote (a database's message, a mail server's reply, a remote
API's body). That includes the one line the transaction manager writes when the database refuses a write only as it
commits, as when two requests race for the same email address: it names the error's type and SQLState, not the
database's message. An error the server had no answer for is logged by a random error id, its route template and its exception's class
chain; its full text is written only while an operator has raised one category on purpose. The
admin console's access log keeps no reader's address; the bundled proxy keeps no access log, which a posture check
holds; the object store logs warnings only. A rate limiter keeps a client's address, or an email address for the
recovery and invitation mails, in memory as a bucket's key until the bucket has refilled. Logs are bounded by size and
never evidence: a report's evidence is a database record. Every end-to-end run fails if a log holds a sealed value, a
token or an address a suite signed up with ([Verify it yourself](/security/verify-it-yourself/)).

## The six concessions

Each of these shows the operator something finer than, or outside, the general rule, and each was accepted for a
stated reason.

| # | Concession | What the operator learns | Why it was accepted | Decided in |
|---|---|---|---|---|
| 1 | **Range streaming of video** | Which ciphertext chunks a playing client fetches, and when: which parts of a video were watched, re-watched, skipped or seeked to. Finer than a per-blob timestamp. | Hiding it means either downloading the whole original before the first frame or routing bytes through a decrypting server, which would break operator-blindness and self-hostability. Scoped to playback (the same route also plays audio, and a file on a Depot file's page); read-ahead is a playback setting, not a privacy control. A download also streams but reads every chunk in order, so it shows no more than a whole-blob read. | [ADR 0047](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0047-inline-video-playback-range-streaming.md#the-accepted-seek-leak--stated-plainly) |
| 2 | **Plaintext account email** | Each account's email address. | It is the login identifier and the source of the deterministic auth salt, which makes the server answer identically for existing and non-existing accounts. | [ADR 0003](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0003-operator-blind-password-auth.md) |
| 3 | **Node `kind`** | The kind of every node (`FOLDER`, `MEMORY`, `CHAPTER`, `FILE`, `DEPOT_DROPBOX` today), and so which applications a scope uses and the rows, timestamps and sizes each kind produces. | The node tree belongs to the platform, not to one application, so sharing, trash and move are built once; the server applies each kind's placement and guest rules through fail-closed seams that read the kind. | [ADR 0079](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) |
| 4 | **Member enumeration within a tenant** | To any fellow tenant member: every member's username, identity public key and group-add-consent flag. | Adding someone to a group resolves a username to an identity public key on the server. The resolve is owner-gated, but any member can create a group and own it at once, so the gate is convenience, not confidentiality, and is not presented as such. | [ADR 0014](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0014-group-scopes-and-governance.md) §5 |
| 5 | **Share linkability via the scope id** | A guest learns the shared node's scope id, so two links into one space are linkable by anyone holding both. | Every node-key seal the viewer opens is bound to its scope, so the id travels with the subtree. The shared node's and its media's ids already made one subtree linkable; the scope id extends that to sibling Memories of the same space. | [ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md) |
| 6 | **The guest-write record** | Wherever a link lets its guest write: the IP address, source port and millisecond time of each write a link's guest makes, with the guest identity and the object written, for N days (default 7, at most 30). Never a member's write, never a read, never content or a User-Agent. No switch of its own: switching guest writes off keeps none. | A host that is told of misuse through a guest link must be able to name the connection it came from, and behind carrier-grade NAT only the address, the port and an exact time together name a subscriber. The address is the hop the network vouches for, never one the client wrote, so it cannot name an innocent third party; guests are told before their first write. A report's hold copies the rows of what it concerns; everything else expires. The instance backup leaves the rows out. | [ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10, [ADR 0094](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md) |


:::note
Every server-visible fact on this page is also visible to a [passive operator](/security/threat-model/) and
to anyone holding a database dump. What an *active* operator can do beyond reading it is the subject of the
[Threat model](/security/threat-model/).
:::
