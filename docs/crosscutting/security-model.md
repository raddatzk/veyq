# Security & Encryption Model

> The dominant cross-cutting concept. veyq is **end-to-end encrypted everywhere** — every scope
> (personal, group, tenant). The **server is operator-blind**: it stores ciphertext, opaque
> operational metadata and a blind job queue, and never holds a key that opens content. Every other
> concept — processing, search, sharing, recovery, deletion — is designed around this.
>
> Plain-language companions for end users (docs site): [Is my data safe?](../../site/src/content/docs/privacy/is-my-data-safe.md) and
> [What happens to my photos when quantum computers arrive?](../../site/src/content/docs/privacy/quantum-computers-and-your-photos.md);
> for operators, [Running your own instance](../../site/src/content/docs/run/index.md).
> For a Server Owner handling a reported share: [Moderation](../../site/src/content/docs/run/moderation.md).
> For security researchers, the docs site's Security area: [Start here](../../site/src/content/docs/security/start-here.md), [Threat model](../../site/src/content/docs/security/threat-model.md), [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md), [Cryptography](../../site/src/content/docs/security/cryptography.md), [Key hierarchy](../../site/src/content/docs/security/key-hierarchy.md), [Protocols](../../site/src/content/docs/security/protocols.mdx), [Known limitations](../../site/src/content/docs/security/known-limitations.md), [Roadmap](../../site/src/content/docs/security/roadmap.md), [Reporting a vulnerability](../../site/src/content/docs/security/reporting.md).
> Sibling engineering docs: the [account & key model](../features/account/enablers/en.account.key-model.md), the
> [scopes & tenancy model](scopes-and-tenancy.md); byte formats live in the conformance-pinned suite ([`src/packages/crypto/src/v1.ts`](../../src/packages/crypto/src/v1.ts)) and its vectors ([`contracts/crypto/vectors/v1.json`](../../contracts/crypto/vectors/v1.json)), and what every seal binds is [ADR 0072](../decisions/0072-a-seal-binds-its-object.md).

This document is the **target model**. What ships in the first build is tracked via the inbox and
change-requests, not here — with one exception: [§ What holds today](#what-holds-today-on-a-client-the-operator-does-not-serve)
states, per attack, which parts of the model are built against an active operator.

## The one guarantee

A user's **unshared** content is confidential **even from the operator**. The server enforces
*authorization* on opaque identifiers; the client enforces *confidentiality* with keys the server
never sees. Confidentiality is therefore cryptographic, not a server-side access-control promise.

## Threat model & trust boundaries

| We protect against | How |
|---|---|
| Stolen disk / DB dump / subpoena of data at rest | All content E2E-encrypted; the server holds no content keys |
| Passive operator reading the database | Cannot recover content — every blob and sensitive metadata field is encrypted |
| Offline brute-force of a weak password → **content** | **Secret Key (2SKD)**: the unlock KEK derivation folds in a high-entropy Secret Key the server never stores ([account key model](../features/account/enablers/en.account.key-model.md#unlock-methods--kek-derivation)), so a cracked password alone unlocks nothing |
| Offline brute-force of a weak password → **server login** | The server stores only an Ed25519 **auth public key** (not a credential); the raw password never reaches the server. Forging a login means brute-forcing the password through Argon2id — yielding a session + operational metadata, **never content** directly (see [ADR 0003](../decisions/0003-operator-blind-password-auth.md)). Every request that replaces the identity or mints a cold credential needs a signature only the Secret Key derives ([ADR 0060](../decisions/0060-key-rotation-after-compromise.md) Decision 2). Admitting a member from a session is refused too: the server mints a member invite only when its invite key carries the caller's own owner signature under the identity key it stores (`TenantHandler.signedByCaller`, [ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 1), which a session without the vault cannot make — the job ADR 0020's admission tag did, on an honest server |
| The server swapping or replaying ciphertexts between objects | Every seal binds its purpose **and** the object it is stored as, so a ciphertext served for another object fails to open ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)). **Freshness is not covered:** the server still chooses which authentic version of an object, and which epoch's grant, a client receives |
| **Online** brute-force / credential-stuffing / DoS on the public auth surface | **Per-IP rate limiting** of the public auth endpoints (`@RateLimit(key = IP)` → token-bucket `429`) — defense-in-depth, **honestly limited** (per-instance, resets on restart). Mechanism + verification: [cc.security.rate-limit](cc.security.rate-limit.md) |
| Active operator doing key-substitution during key exchange | **Partly, and honestly limited** — see *Key-directory trust* below |

We do **not** claim immunity to an *actively malicious* operator before key transparency exists; we
state that gap plainly. In a **self-hosted** deployment the operator is the user, so that threat is moot.

### The operator we blind is the *veyq* operator — third-party services are a separate, consented boundary

"Operator-blind" names one adversary: **the veyq server / operator.** It does **not** mean the client never
talks to any external service. A feature may let the client reach a **third party directly** — today the
opt-in **map tiles** of Memories' maps (the place editor's and a level's map: the viewport's `{z,x,y}` and the client
address reach the configured tile host, [ADR 0018](../decisions/0018-map-tiles-provider-neutrality.md)), the opt-in
**reverse-geocoding** lookup that turns a photo coordinate into a place name
([ADR 0016](../decisions/0016-geocoding-provider-neutrality.md)) — both only after the member's consent, one consent
for the two — and the automatic **breached-password check** and **day's exchange rates** of Expenses (both below).
Such a request is a **different trust boundary** the user consents to, and it is fenced so it can never widen the veyq
operator's view:

- **Client-direct, never proxied through the veyq backend.** Routing it through veyq's server would hand the
  operator the coordinate — so these requests go **straight from the device to the provider**. Proxying is
  explicitly disallowed.
- **No veyq identity, no content context.** The provider receives only what its function needs — a bare
  coordinate or a tile's `{z,x,y}` (+ the client IP) — never a user id, a Memory id, or any veyq token.
- **Opt-in + transparent — and opt-in is standing consent** (two stated exceptions: the breached-password check and
  the exchange rates, below). Off by default; enabling it is an explicit choice
  with a plain-language note that a request leaves the device. Once enabled it may run **automatically** (e.g. a
  reverse lookup on the first geotagged media of an auto-location) — enabling it *is* the consent to those
  requests; it **never** fires without opt-in. A blind, request-free alternative stays available (e.g. typing a
  place name).
- **Results are sealed content.** Anything fetched (e.g. a resolved place name) is sealed into the node's meta, under
  a field key derived from `K_node` like any other field — so it is shareable to other members **without their device making a request**, and the veyq
  operator still sees only ciphertext.

**The breached-password check is the second instance, and the first exception to opt-in.** The four dialogs that
choose a password (set up the server, join a tenant, change a password, recover access) ask the Have I Been Pwned
range API whether the password appears in known breaches. The fences apply like this:

- **Only a 5-hex SHA-1 prefix leaves the device** (k-anonymity, padded response); the match against the returned
  suffixes is computed locally.
- **Over a finished password only — when the field is left, and on submit — never while typing.** A query at every
  typing pause (a debounce) sends the prefix of each *partial* password. A short one, or a few keystrokes added to one
  already recovered, has so few candidates that its 20-bit prefix all but identifies it, so the sequence hands over
  the password a few keystrokes at a time. Leaving the field is when a person treats the password as done, so the
  check runs then (owner decision 2026-10-14), and once more on submit when the password changed since; every query
  is a whole candidate, never a fragment.
- **Automatic, not opt-in** (owner decision 2026-10-14). It is one of the two third-party requests that run without
  consent (the exchange rates below are the other), and the reason is what crosses: a 5-hex prefix carries no content and no identity, where a geocoding request
  carries the coordinate itself. **What it does disclose, stated:** the browser's `Origin` header names the veyq
  instance, so HIBP (and the CDN in front of it) learns that this IP uses this instance and is choosing a password.
  The docs site says so, in [Is my data safe?](../../site/src/content/docs/privacy/is-my-data-safe.md) and
  [Change your password](../../site/src/content/docs/account/change-password.mdx). The field itself carries no note: the privacy notice names the check, its recipient and its switch
  (ADR 0093 § Legal basis; the note was removed under the copy rule, owner 2026-10-21).
- **Its address is the Server Owner's** ([ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 7): the register entry `core.breached-password` carries it, a new instance starts with the HIBP range API, a
  mirror can replace it, and an instance with none configured (or the entry switched off) runs no check at all, sends
  nothing and says nothing.
- **It advises, never gates.** A hit shows a warning that needs an **explicit "Use it anyway"** before the dialog
  submits that password; the result is not stored. **A check that could not run** (offline, firewalled, the admin
  container with no route out) **does not stop anything**: the dialog submits and says, in one line, that the
  password could not be checked. Stopping there would block every password choice on an instance with no
  internet, for a check nobody opted into.

**Verification:** [`passwordWarning.test.ts`](../../src/web/src/features/account/passwordWarning.test.ts) (identical
in `src/admin-web/`) pins the rule on the request itself: nothing while typing; one prefix of the finished password on
blur; no second request for an unchanged blur; a password changed since is queried again on submit; nothing for a
password the policy refuses; a hit blocks the submit until "Use it anyway"; an edit clears that acceptance; a check
that could not run goes ahead. Each dialog's own test (`ChangePasswordPanel.policy`, `TenantJoinView.passwordPolicy`,
`RecoverView`, admin-web's `SetupView`) pins that its password field's blur runs the check, that its submit awaits
it, the note and the one line for a check that could not run; `TenantJoinView.passwordPolicy` also pins that the
sign-in password, which chooses nothing, is never sent.

**The day's exchange rates are the third instance, and the second exception to opt-in** ([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
decision 8, [uc.expenses.exchange-rates](../features/expenses/use-cases/uc.expenses.exchange-rates.md)). Where an
Expenses entry's currency differs from its Pot's base, the device fetches the whole day's table of the European Central
Bank's euro reference rates and fills the rate in. The fences apply like this:

- **Only a date leaves the device.** The address is a `{date}` template; the device puts its own date in and sends a
  plain `GET` — no currency, no amount, no query parameter of its own, no custom header, no credentials, no Referer.
  The cross rate (`rate(base) / rate(currency)`) is computed on the device, so the provider never learns which
  currencies a Pot holds. **What it does disclose, stated:** the client's address, the `Origin` naming the instance,
  and that this device writes an entry in a foreign currency today — once a day at most: one table per date is kept in
  the device's local storage for every Pot, and a day whose request failed is not asked again.
- **Automatic, not opt-in** (owner 2026-10-23), for the breached-password check's reason: what crosses carries no
  content. Proxying it through the backend is refused — it would hand the operator the date of a trip.
- **Its address is the Server Owner's** (ADR 0088 decision 7): the register entry `expenses.exchange-rates` carries it,
  a new instance starts with Frankfurter's endpoint for the ECB's rates (`api.frankfurter.dev/v2/providers/ecb/rates`,
  owner 2026-10-25: the ECB's rates alone, never the blended default), and the Server Owner or a tenant owner switches it
  off. **Who may fetch follows the tenant's switch, not the instance's**: a member's client reads the entry from its
  tenant's set, a link guest's from its Pot read (`exchangeRateService`), since a guest holds only the instance's
  manifest — so a guest's device never fetches where the tenant's privacy notice names no fetch.
- **It fills, never gates.** A save never waits for the provider and seals whatever rate the field holds; a saved rate
  never moves. A provider that cannot be reached, or a currency its table lacks, leaves the rate to be typed.
- **Its source is cited**, as the ECB's terms for reusing its statistics ask: the editor says the rate is computed from
  the ECB's euro reference rates of the rows' date, and the privacy notice names Frankfurter as the service the browser
  contacts and the rates as the ECB's.

**Verification:** [`exchangeRates.test.ts`](../../src/web/src/features/expenses/exchangeRates.test.ts) pins the request
itself (one per device and day for any number of Pots, kept across a reload; the date and nothing else; not repeated
for a failed day) and the exact cross rate; [`EntryEditor.rates.test.ts`](../../src/web/src/features/expenses/components/EntryEditor.rates.test.ts)
that nothing is asked while the Pot's session names no table, that a save does not wait for an unreachable provider and
that a typed or saved rate is never written over; [`potSession.rates.test.ts`](../../src/web/src/features/expenses/potSession.rates.test.ts)
and `PotSliceTest.aGuestsReadNamesTheRateTableExactlyWhileItsTenantHasTheRatesOn` the tenant's switch reaching a
member and a guest. Lane L1 drives it in a browser against a fixed ECB table, never the real provider
(`tests/e2e/src/egress.ts`).

So the veyq operator stays blind by construction; what crosses goes straight to the provider, never through veyq: a
consented, identity-free request the **user** turned on, the breached-password check's 5-hex prefix, or the exchange
rates' date.

## What is encrypted vs. server-visible

- **Encrypted (server-blind):** media bytes and all derivatives; journal text and embeds; sensitive
  metadata — titles, captions, descriptions, places, people/faces, participant relationships, tags;
  track and playlist details; GPS and capture time (sealed client-side, never read off the file); a blob's
  **type, upload name and modification time** (the `blob-meta` seal under the blob's own `K_blob`, declared once at
  the upload and never rewritten — padded to a 256-byte bucket, so its length tells the operator one of four sizes,
  never how long a name is; the plaintext size and upload time are left out because the server already observes
  both — the size on the blob row, the upload time live and in the object store's own write time of each chunk;
  a Memories original's file name and media type — photo, video or PDF — live there, so the operator learns neither).
- **Server-visible (low-sensitivity operational metadata):** existence and IDs of memories, media and
  shares; scope and membership (who belongs to which tenant/group); a member's **per-tenant username,
  identity public key, and group-add-consent flag** — visible to, and effectively **enumerable by**, any
  fellow tenant member (anyone can create a group to run the member-resolve, [ADR 0014](../decisions/0014-group-scopes-and-governance.md) §5);
  whether a member **has a profile picture** and how often it changed (`avatar_revision`; the picture itself is a
  constant-size sealed blob, [ADR 0084](../decisions/0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md); contract, crypto and backend built, the web client not yet);
  server timestamps (created, changed, trashed, last rotated) for ordering — stored as written, to the millisecond (a
  node's creation time is served to members on the node
  listing, `NodeSummary.createdAt`, as the order equal-named siblings are told apart by, [ADR 0015](../decisions/0015-operator-blind-readable-urls.md)); nothing rounds them, so they tell *when*, never *what*.
  **A time is stored only where something reads it** — a sort, a purge or expiry cutoff, a served field, or,
  named as such, an operator reading the database for audit evidence (inbox #318; the root test
  `EveryStoredTimeHasAReaderTest` names each stored time's reader and turns red on one without).
  So the database does **not** hold when an account, tenant, tenant profile, scope, blob, upload session, attachment
  or medium's derivative was created, when a share link was deleted (its tombstone is a flag), a medium's creation
  apart from its change time (which starts at the attach, inbox #398), when a scope's key epoch rotated, when a member re-sealed
  their favorites, device register or identity pins, when a share guest joined, when a job was declined, or when
  first-run setup completed; the request time the server observes live is not kept for them. What it does hold, by
  reader: session family creation + last refresh (served), token/challenge/invite/share/recovery/pairing/
  confirmation expiries, a recovery/pairing/confirmation/invite/job/reclaim row's creation (purge or order), a
  membership's creation (the orphan rule's successor), a node's creation (served) and trash time, a group's and a
  medium's trash time and a medium's change time (cursor + purge; set at attach, so also its creation), a bucket's
  creation (order), a share's creation (served), `email_confirmed_at` (its presence shows "confirmed"; the time is
  [ADR 0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) Decision 1's record,
  kept by decision, inbox #398), and when a member accepted each terms revision they
  accepted (a history, one row per revision — audit evidence, served by no API — below); **which member verified
  which in a tenant, and when** (a verification row's plaintext address and write time, served to the tenant's members;
  its statement is sealed under the tenant key, [ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  § Consequences); sizes; job-queue and op-log offsets; presence facts; a media
  item's **trash state** (a trashed flag + who trashed it + when, until the scheduled purge); a media item's
  **uploader attribution + bucket structure** (an opaque `owner_key` per medium — `user:{id}` / `share:{id}` —
  plus its `bucket_id` and the bucket rows' shape: node, parentage, `is_primary`, per-bucket counts; the
  bucket/uploader **names stay sealed** under a field key derived from `K_node` — a WRITE_ONLY guest's from its own key, [ADR 0034](../decisions/0034-media-uploader-buckets.md),
  [ADR 0031](../decisions/0031-uploader-public-share.md) point 7); a Depot file's **uploader attribution** (the member
  or guest identity that uploaded its current content, on its attachment - served to members only, as `uploaderKey`
  `user:{id}` / `share:{id}`; [ADR 0083](../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) Decision 8);
  a Memory's **banner** — that it has one and whether it is a video (its `BANNER` / `BANNER_POSTER` attachment rows and
  their sizes), never its frame, focal point or a pixel; a banner (or a cover) uploaded equal to a gallery medium shares
  that medium's stored object, so its row names the medium, and even unshared a banner's size can match a medium's -
  accepted (inbox #505, [ADR 0102](../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  § What the server sees);
  the **last editor of each sealed field group** — a node's name and meta, a share's name, note and private label, a
  group's name and icon, a bucket's name, a medium's meta: the member or guest identity whose write the ciphertext is,
  served to no client and never what was written ([ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 8, [en.platform.field-editor](../features/platform/enablers/en.platform.field-editor.md));
  wherever a link lets its guest write (no switch of its own: `contribute` and `drop-box` decide), the **guest-write
  record**: per write a link's guest makes that enters it as a last editor, the client address of core's one resolver
  (never one the client chose, [ADR 0094](../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)),
  its source port where known and the time to the millisecond, with the guest identity and the object — kept N days
  (1 to 30, default 7), copied by a hold, left out of the instance backup's rows; never a member's write, a read, a
  User-Agent or content ([ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10, [en.moderation.guest-write-record](../features/moderation/enablers/en.moderation.guest-write-record.md));
  per-parent blinded tokens; to a **public share's guest**, the shared node's **scope id** — every `K_node` seal the
  viewer opens is bound to it ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)), so it rides with the
  subtree, and two links into one space are therefore linkable by anyone holding both (the ids of the shared
  node and its media already made the subtree linkable; the scope id extends that to sibling Memories of one
  space); the tenant's **terms text + `terms_revision`** (the space owner's *public* policy,
  served to everyone incl. pre-auth) and a member's **accepted terms revisions** and **when they accepted each**
  (one row per accepted pair — the space's revision and the operator section's revision — never
  overwritten; `accepted_at`, the server's own clock, written once per revision — audit
  evidence for a dispute, also about a superseded revision; restored by owner decision 2026-10-09 after #318 had
  dropped it, kept as a history since #402) (acceptance metadata, like membership — never content, [ADR 0035](../decisions/0035-server-terms-of-service.md)); the **account email**
  (plaintext — the login identifier and the source of the deterministic auth salt,
  [ADR 0003](../decisions/0003-operator-blind-password-auth.md); named here to close a prior inventory gap); a
  tenant's **per-tenant SMTP config** (host/port/from/username/TLS + the owner-authored invite **template text** —
  operator-visible operational config) and the **send-fact** of every mail veyq sends — a member invite, a
  password-recovery link and an address confirmation (recipient + send time, once a tenant has configured
  SMTP), plus the account's **`email_confirmed_at`** (when its holder last showed they read that address —
  operational metadata of the address's own class, gating nothing,
  [ADR 0074](../decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md)), while the SMTP **password is a server-held secret sealed at rest** (AES-256-GCM
  under a server-managed KEK — ciphertext + nonce only, [ADR 0050](../decisions/0050-operator-config-secret-encryption.md):
  the KEK is a `0600` key **file** generated on first start, plus a non-invertible HKDF **fingerprint**
  of it beside every sealed value so a lost/changed key is detected, never a silent unseal — the fingerprint is a
  hash of a full-entropy key, not the key, so the "no usable secret in a DB dump" property holds); a mail itself
  carries only link + domain + expiry + neutral copy, never sealed metadata
  ([ADR 0049](../decisions/0049-email-delivery-boundary.md)).
  The **instance's own mail settings** are of the same class (host/port/from/username/TLS, the password sealed the
  same way, the hash of the outstanding confirmation code, and which address the Server Owner confirmed and when), and
  so is the **release check** — its switch, its source, its last success and the notices it found, which describe
  published software only ([ADR 0097](../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)).
  Its daily request names no release, and the notice mail carries release numbers, a date and a link, no user data.

**Applications widen what is visible by one fact: the node `kind`.** The kind of every node is a server-visible
column already (`FOLDER`, `MEMORY`, `CHAPTER`, `FILE`, `DEPOT_DROPBOX` today). Once an application adds kinds, it also shows the operator
**which applications a scope uses**, and the rows, timestamps and sizes each kind produces
([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)). Each application's concept
doc lists the further metadata its own kinds expose ([docs/apps](../apps/README.md)).

These operational fields are exactly what lets the server **sort, paginate, route, authorize and
bill** without decrypting. It is a conscious trade-off: a stricter "encrypt operational metadata too"
mode is possible at the cost of those capabilities. The server never sees content — only structure.

**One access-pattern finer than a per-blob timestamp is knowingly conceded, for range-streamed video playback.**
Every non-streamed read fetches a blob **whole**, so the operator normally learns only *that* a blob was accessed.
Playing a video streams it **range by range** ([ADR 0047](../decisions/0047-inline-video-playback-range-streaming.md)):
the object store then observes **which ciphertext chunks a playing client fetches, and when** — an *intra-media*
access pattern (which parts were watched, re-watched, skipped, seeked to), more granular than the
timestamps or sizes above. The owner accepted this for playback: hiding it would mean either downloading the whole
original first or proxying bytes through the server (breaking operator-blindness). It is scoped to playback and
changes nothing about content, which the server still never reads. A **file download** streams through the same
key-free Service Worker ([ADR 0047 § The download route](../decisions/0047-inline-video-playback-range-streaming.md#the-download-route))
but reads every chunk in order, so it shows the operator no more than a whole-blob read.

**A log carries ids, never what the operator could not otherwise read** ([ADR 0092](../decisions/0092-logs-are-operational-and-name-no-person.md)
§ 3). The app's lines name accounts, tenants, scopes, nodes, shares, blobs and jobs by id, a domain, an exception's
class, a SQLState and counts — never a key, a token, a sealed value or plaintext metadata, an email address, a network
address, a User-Agent, a request's URI or input, or a message someone else wrote (a database driver's, a mail
server's, a remote API's body); a constraint violation at commit, which Narayana logs, is rewritten to its class chain
and SQLState (`CommitFailureLogFilter`). An unexpected error is logged by its route template and class chain under a random
error id; its full text needs a DEBUG category raised on purpose. The admin console's access log keeps no reader's
address, the bundled proxy keeps no access log, and the e2e sweep fails a run whose logs hold a sealed value, a token
or an address a suite signed up with ([cc.security.wire-assertion](cc.security.wire-assertion.md)). A log is
operational, bounded by size, and never evidence: the evidence a report needs is a database record (ADR 0093 § 10).

This split is written in *kinds*; the contracts are written in *field names*. [ADR 0044](../decisions/0044-field-visibility-declared-at-the-field.md)
is what connects them: every field in `contracts/api/` declares which of these kinds it is, or the build fails.
This section stays the reason; the ADR carries the mechanism and does not restate it.

## Central axis: shared vs. unshared

The deciding property for risk is **whether content is shared**, not which scope it lives in. A
personal memory that is publicly shared becomes "shared" for these purposes.

| State | Meaning |
|---|---|
| **Unshared** | Only the owner's keys. Maximum confidentiality; the one guarantee applies in full. |
| **Shared** (group, tenant, public view, public uploader) | Content keys wrapped to other parties by the owner's explicit, informed action. Confidentiality from the *operator* still holds; from the *recipients* it is intentionally given up — except from a public uploader, who is handed no content key at all, only the Memory's drop public key ([ADR 0031](../decisions/0031-uploader-public-share.md) point 7). |

## Key hierarchy (envelope encryption)

Content is protected by layered envelopes so grants are cheap and shares are least-privilege. The
byte-level detail lives in the conformance-pinned suite ([`src/packages/crypto/src/v1.ts`](../../src/packages/crypto/src/v1.ts)) and its vectors ([`contracts/crypto/vectors/v1.json`](../../contracts/crypto/vectors/v1.json)), and every seal binds the object it belongs to ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)); account-level detail (unlock methods, recovery) in the account/key
model. The shape:

```mermaid
---
title: Envelope encryption — key hierarchy
---
flowchart TB
  unlock["Unlock methods<br>(one wrap each — see account/key model)"]
  muk["Master Key"]
  idk["Identity private key<br>(you)"]
  idk2["Identity private key<br>(another member)"]
  ksc["Scope content key K_SC<br>(current epoch)"]
  knode["Per-node content keys K_node"]
  content["Content<br>(memories, media, journal, sensitive metadata)"]
  share["Public share<br>(per-share key)"]
  NB["The scope's current content-epoch key is sealed once<br>per member — to each member's identity public key<br>(one wrap = the whole scope). Every member, you<br>included, opens it with their own identity private key."]

  unlock -->|"each wraps (one blob per method)"| muk
  muk -->|"unlocks"| idk
  idk -->|"opens a grant"| ksc
  idk2 -->|"opens a grant"| ksc
  ksc -->|"unwraps"| knode
  knode -->|"encrypts"| content
  knode -->|"one Memory's K_node wrapped to a<br>per-share key (token-unlocked)"| share
  ksc -.- NB

  classDef note fill:#fffdf0,stroke:#c9b458,stroke-dasharray:4 3,color:#5c5326
  class NB note
```

- **Cheap grants, scoped shares.** Adding a member = one seal of the scope's current content-epoch key to
  their identity (an X-Wing encapsulation to their identity public key, then AES-256-GCM under the derived key;
  [Cryptographic stance](#cryptographic-stance)). A public share = one node's content key (a Memory's, a chapter's or a folder's —
  [ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)) wrapped to a per-share key. **Revoking a member
  = rotate `K_SC` forward into a new epoch** granted to the remaining members
  ([ADR 0024](../decisions/0024-scope-key-rotation.md)); already-read content cannot be retroactively pulled
  back (a documented limitation). The concrete key model — `K_SC` (scope), `K_node` (per node, every kind, the
  random, many-wrapped **share grain**), `K_blob` (per blob) — is pinned in
  [ADR 0012](../decisions/0012-content-key-hierarchy.md) and
  [ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 1.
- **A field seals under a key of its own.** Every text or icon field — a node's name and meta, a medium's two
  metadata tiers, a bucket's name, a guest's display name, a share's note, name and label, a member's name and
  picture — seals under `K_field = HKDF(K_parent, salt, "veyq/v1/field/<purpose>")`, where `K_parent` is the key the
  field belongs under (the parent's `K_node` for a name, the node's own for its meta, `K_share`, `K_tenant`) and the
  16-byte salt is fresh per write and travels in the field's envelope (all the server learns: one random salt per
  write, nothing about a key). One revision's `K_field` opens that revision
  of that field and nothing else, which is what a report of a field will hand over
  ([ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9,
  [en.platform.field-key](../features/platform/enablers/en.platform.field-key.md)).
- The identity private key is protected by the **Master Key**, itself wrapped **separately by each
  unlock method**. Adding or removing a method changes one wrap, never the content.
- The whole chain — which key opens which, who grants to whom, rotation and the node re-key, securing the account and
  identity trust — is [keys, grants and trust](keys-grants-and-trust.md).

## Applications are compiled in — never loaded

veyq's applications are first-party and built into the release; there is no runtime loading of application code
on the server or in the client. Code running in the client reaches every key of the user's unlocked scopes, so a
way to add code after the fact would be a way to read everything, and the release identity
([ADR 0057](../decisions/0057-release-identity-and-deployment-recipe.md)) and the client trust tiers
([ADR 0026](../decisions/0026-client-trust-tiers.md)) would no longer say what runs
([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decision 5).

**Known gap, per application:** the server holds an application's entries and chooses what it serves. Binding an
entry to its object ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)) does not bind it to its
neighbours, so for an append-only application (a ledger) a server can **drop or reorder** entries unless a
sequence number or hash chain is bound into each entry's seal. That is the same limit as freshness
above, and each such application states it in its concept doc ([docs/apps](../apps/README.md)). Expenses' answer,
decided and not built, is a graph of seen revisions inside each seal, which catches a dropped entry somebody wrote
after and leaves the newest ones to the freshness limit
([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 5).

## Cryptographic stance

Standard, well-reviewed primitives only (no home-grown crypto); **post-quantum from the start** for encryption
(a hybrid KEM, secure if either component holds) because decade-lived memories make harvest-now-decrypt-later
real, while signatures stay classical for now; and **crypto-agility** via a versioned suite id, so upgrades never
break old data. The hybrid KEM is **X-Wing** (X25519 + ML-KEM-768, `draft-connolly-cfrg-xwing-kem-11` — an
individual Internet-Draft, not an RFC) through `@noble/post-quantum`, held to the draft's own published test
vectors; a wrap to a public key is X-Wing, then HKDF-SHA256 over the seal's context into an AES-256-GCM key —
HPKE-like in shape, **not** RFC 9180 HPKE. Built today, the suite id is stored **per account** (`veyq_user.suite_id`) and bound into every wrap's
authenticated data (`wrapAad`), not carried in the bytes of each artifact — enough while one suite exists, so a
second suite has to bring a per-artifact label first; nothing re-wraps old data onto a newer suite. Content itself is
AES-256-GCM alone; the hybrid KEM wraps keys. The strategy, its rejected alternatives, and the conformance
vectors that pin a suite's bytes are all
[ADR 0017](../decisions/0017-crypto-primitives-and-post-quantum-strategy.md).

## Names & blinded tokens

Names (folders, memories, chapters, files) are encrypted, yet navigation uses human-readable permalinks. One
server-blind token makes this work without the server ever seeing a name:

- **Slug token** — `HMAC-SHA256(slugKey, NFC(fold(name)))`, with
  `slugKey = HKDF(parentKey, "veyq/v1/slug-token:" + parentSlot)`. It is keyed by the **parent node's content
  key** (`K_SC` at the root) and the parent slot (the parent's node id, or the scope id at the root), so the same
  name under two parents yields unrelated tokens
  ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 2;
  `slugToken` in [`v1.ts`](../../src/packages/crypto/src/v1.ts), vectors `slug_token` and `directory_entry`).
  `fold` is the node kind's slug function — `slugify` for containers, `fileNameSlug` for files — and is frozen into
  every stored token. The server enforces uniqueness per parent (`UNIQUE(parent_id, slug_token)`) without seeing
  names. A keyed MAC (not a plain hash), so the operator — lacking the parent's key — cannot reverse low-entropy
  slugs; whoever holds that key (a member, or a guest of the Memory it belongs to) can. It is a **consistency aid,
  not an authenticity control**: the client computes it; the server cannot recompute it.
- **No scope-wide dedup; a shared object only inside one node.** Scope-wide dedup is **declined**
  ([ADR 0013](../decisions/0013-blob-storage-presigned-direct-to-store.md#5-one-stored-object-for-an-equal-file-inside-one-node-nothing-wider)): every blob has a
  random `K_blob`, so identical bytes in two nodes are two ciphertexts and two stored objects, and the server learns
  no content equality across them. Inside one node, the client shares: an upload into a Memory's cover or banner
  dialog equal to a gallery original (and a gallery upload equal to the video banner) is a second blob row on the
  stored object, its `K_blob` wrapped again for the new slot. The client compares a SHA-256 sealed under the blob's
  own key; the server compares nothing, but **it sees which existing blob the new row shares** - which medium is the
  cover or the banner. Accepted residual (inbox #505, [ADR 0102](../decisions/0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md)
  § What the server sees). The suite still pins a keyed `dedupToken` (vector `dedup_token`), but no client calls it;
  [ADR 0024](../decisions/0024-scope-key-rotation.md) says how a future scope-wide slice would key it across epochs.

## Sharing & share-boundary Trust & Safety

- A **public share** wraps the subtree's content keys to a per-share key. The **share secret lives in
  the URL fragment (`#…`)** and never reaches the server. Anyone with the link decrypts client-side.
- **Trust & Safety operates at the share boundary**
  ([ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)). A
  report carries a report share for exactly what was reported, sealed to the **instance moderation key**, so the
  operator side **can decrypt what was reported, and nothing else**, to verify, hold evidence and act; an
  authority's order acts on ids without a report. That key is one key in epochs, granted to each **moderator** the
  Server Owner appoints (the Server Owner among them) and rotated forward when one leaves
  ([ADR 0099](../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)).
  Review and decisions run in `src/web/`'s moderation surface, gated by a `MODERATOR` role **and** a signature with
  the moderation key, and the page that renders a report holds that key and nothing of the moderator's vault. A
  moderator blocks a share, its content or a guest uploader; blocking a member and taking a tenant offline stay
  the Server Owner's. A second moderator reviews an objection where there is one. Against the operator this is
  accountability, not a control: the Server Owner owns the server and the roster, and the surface is
  operator-served, passive-operator-safe only ([ADR 0026](../decisions/0026-client-trust-tiers.md)).
  **Built today: the intake, the review and the measures.** A member or a guest reports what they see: their client
  seals the report share and their text to the current epoch — their address apart, so it can go alone six months
  after the decision was told — the server stores the category, time and ids in the
  clear, opens one case per item and takes a **hold** — a copy of the reported rows and a pin on their store objects
  that the reclaim sweep skips, so a delete after the report removes the item for everyone but the moderators
  ([uc.moderation.report](../features/moderation/use-cases/uc.moderation.report.md),
  [en.moderation.hold](../features/moderation/enablers/en.moderation.hold.md)). A moderator lists the queue in clear
  fields, opens a case's sealed reports in the browser, and reviews the held snapshot in the share viewer's moderation
  mode on the reported tenant's host, over moderation reads behind the role and a key proof per operation and case that
  serve only the hold's ciphertext and log an *opened* event per fetch — never a guest endpoint, a link's password or
  a scope client ([uc.moderation.review-a-case](../features/moderation/use-cases/uc.moderation.review-a-case.md)). The
  decisions are rows by id that delete nothing and lift by closing the row: dismissing (the hold ends at once),
  extending a hold with a sealed reason, barring a reporter, and the measures — a link blocked (every guest path answers
  as an unknown link), content blocked (`451 blocked_by_operator` on every byte path, the two `presignGets` callers held
  by `PresignGetsHonourContentBlocksTest`, listings marking it), a guest uploader blocked with its link — while a
  member's sign-in block (every session ended, every sign-in refused) and a tenant's suspension are the Server Owner's,
  on the admin channel, taken or declined from a moderator's proposal
  ([uc.moderation.take-a-measure](../features/moderation/use-cases/uc.moderation.take-a-measure.md)). Measures are clear
  metadata, since the server must refuse what they name; notes and reasons are sealed (`moderation-note` [epoch,
  caseId]). A moderator's measure carries its **statement of reasons**: the ground in the clear, the facts sealed in the
  moderator's browser to the moderation key for the log, and once more under a fresh `K_facts` bound to the statement
  (`moderation-statement-facts` [caseId, statementId]) whose key alone is wrapped to each affected member's identity
  (`moderation-facts-key` [caseId, statementId, accountId]) — so the affected read them on the placeholder and the
  server holds neither in the clear; the
  instance mail tells them it is there, never the facts. An affected member **objects** once per measure, sealed to the
  moderation key (`moderation-objection` [epoch, statementId]); the server refuses its decision from the moderator who
  took the measure while another holds the key ([uc.moderation.object-to-a-measure](../features/moderation/use-cases/uc.moderation.object-to-a-measure.md)).
  The identity a moderator wraps the facts' key to is the one the server serves, unpinned — the moderation surface holds no
  Master Key to read a pin store — which costs nothing against the operator: the Server Owner holds the moderation key
  and reads the log's copy of the same facts anyway. **A Server Owner's own step over content** (a sign-in block, a
  tenant offline) carries a statement too, and it is the one place facts reach a mail: the blocked member cannot sign in
  to open a sealed copy, so the Server Owner's own words go by mail to the member or the tenant's owners — a disclosure
  by their author, the server reading them as it sends them (ADR 0049 decision 2) — and are stored only sealed to the
  moderation key's public half by admin-web or the CLI (`moderation-step-facts` [epoch, statementId]); the server keeps
  no clear copy, so a failed send is not retried. The **provision** a ground relies on is clear text beside it, the
  moderator's or Server Owner's own short reference. An **authority's order** acts on ids without a key: entered by a
  moderator with the key proof or by the Server Owner on the admin channel, its reference sealed by the entering client
  to the moderation key's public half (`moderation-order` [epoch, orderId]), it blocks a link and its content and holds
  them to the end it sets, and the guest identity whose upload a removal order blocked is told so on its link through
  its contributor token, when the order was received and the instance contact — nothing a guest without that token
  learns (TCO Art. 11); an **Art. 18 export** carries only the clear fields the server holds — held metadata and the
  guest-write record — never content ([uc.moderation.authority-order](../features/moderation/use-cases/uc.moderation.authority-order.md),
  [uc.moderation.report-to-an-authority](../features/moderation/use-cases/uc.moderation.report-to-an-authority.md)).
  A child-sexual-abuse case's hold ends at the recorded handover, at the latest 90 days after the report; no measure
  or statement keeps it longer (§ 184b(5) StGB). **Retention:** a closed case's records — reports with their sealed
  parts and report shares, measures, statements, objections, orders — go with its log at 31 December of the third year
  after the close, never while a measure of it is in force; a Server Owner's step on no case goes with its statement at
  31 December of the third year after its lift ([en.moderation.log](../features/moderation/enablers/en.moderation.log.md)).
  Not built, open legally: a hold until the handover beyond 90 days, and an authority's link to a held copy. No
  moderation path has run on a deployed instance.
  The fixed **operator section** every space's terms carry
  states all this to members and guests — the notice path, what is kept, the measures, the statement and the
  objection, and the authorities — veyq's text, one form on every instance, filled with the operator's published values
  (an empty one shown as not stated), public without an account ([ADR 0035](../decisions/0035-server-terms-of-service.md) decision 6).
  The fixed **instance privacy notice** states the GDPR Art. 13 information for members, reporters and guests — what
  the server keeps in the clear, why, how long, who receives it, the rights — from what the running instance does and
  the operator's published values, public at `/privacy` on every tenant host (decision 8). Both are public operational
  content, never E2EE content.
  Each field revision seals under a `K_field` of its own, which opens nothing else, so a report hands over exactly
  the revisions it names ([en.platform.field-key](../features/platform/enablers/en.platform.field-key.md)).
- **Consequently:** private (unshared) content is **unscannable** by design; public shares are
  decryptable-on-report — notice-and-takedown works exactly where content is distributed.
- **A guest's write keeps where it came from, briefly** ([en.moderation.guest-write-record](../features/moderation/enablers/en.moderation.guest-write-record.md)).
  A link guest has no account, so the evidence a report about its upload can carry is the connection: wherever a link
  lets its guest write, each write that enters a guest as last editor stores the client address the
  network vouches for, its source port and a millisecond time, for N days (1 to 30, default 7); a report's hold copies
  the rows of what it concerns. Guests are told before their first write, with N and the right to object, and linked
  to the full Art. 13 text in the instance privacy notice, which the operator section of the terms points to. The record
  adds operator-visible metadata about guests, never content, and nothing about members or readers; an address
  without a port may name no single subscriber behind carrier-grade NAT, and on Helm the address is only as strong as
  an enforced NetworkPolicy ([ADR 0094](../decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)
  decision 6).
- **A link's id alone opens no write.** The share id is stored raw (members rebuild links from it, seals bind it,
  every guest request path carries it), so a database copy or an access log holds every id. Naming yourself as a
  guest — the one write the id used to open — presents a token derived from `K_share`, of which the share keeps only
  the hash; without the fragment no guest is named and nothing is uploaded
  ([ADR 0076](../decisions/0076-a-guest-write-proves-it-holds-the-links-key.md)). Reads by an id holder stay
  possible and serve ciphertext only. A layer-1 control: the operator, being the server, could write anyway.
- **Revocation is soft, and independent of scope rotation.** Disabling a share / expiry stops *new* access;
  already-downloaded content is not retractable. A scope key rotation (a member removal) does **not** revoke a
  share — its re-key re-wraps the node's new key under the link's own `K_share`
  ([ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4) — so shares are revoked **separately** (a removed
  member with the link keeps reading until then). The no-install web viewer is operator-served, so it is
  **passive-operator-safe** (the client-integrity tier, [ADR 0026](../decisions/0026-client-trust-tiers.md)), not
  operator-proof against a *foreign* operator; the viewer surfaces this honestly via a footer link here.
- **Full mechanism:** the [Sharing](../features/sharing/_index.md) feature
  ([ADR 0029](../decisions/0029-public-viewer-share.md) — the public share resource + anonymous access path; a
  Memory of any scope the curator belongs to, §6).
- **An editable curator note (+ private name)** rides the same blind path: an optional **per-locale** message the
  sharer attaches (sealed under a field key derived from `K_share`, rendered on the share page in the viewer's
  locale) and an optional **private name** (the same, a **management-only** label the viewer never receives). To make them
  editable **in place**, the share also stores `sealed_share_key` — a recoverable copy of the per-share key sealed
  under the scope key **`K_SC` at the scope's current epoch** (stored with that epoch), openable **only with `K_SC`**,
  which neither the operator nor any share link holds, so `K_share` never enters the operator's reach. The
  conscious consequence: a **scope member** can now **derive** `K_share` for that Memory's shares — harmless (they
  already read the content, and revoke stays owner-gated). It is deliberately *not* every `K_node`-holder: a READ or
  READ_WRITE link holds `K_node`, and a copy under it would open every sibling share's password, label and note to
  that link plus a dump. Recorded in [ADR 0036](../decisions/0036-editable-share-note-recoverable-share-key.md)
  point 4.
- **A link a departed member could copy is re-issued, never revived**
  ([ADR 0075](../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)). Every member can rebuild
  **every** link of a Memory from its recovery copy, so a member removed from a group or the tenant may hold every
  link that existed on its Memories while they belonged — and using one needs **no dump**: against an honest server
  the link is a bearer capability, and a rotation re-wraps it to the new `K_node` rather than closing it
  ([ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4). A **re-issue** (one request, one transaction: a new
  id and a fresh `K_share` carrying the old settings, the old link deleted) is what cuts them off. Re-activation is
  refused (`reissue_required`) for a share whose owner has left, whose copy predates the scope's current epoch or
  its last member removal (a scope counts its removals, because the tenant never rotates on one), or that a bulk
  switch-off marked — every link of the space, one already off included; a group or tenant removal can opt to turn off every link of its space in the same
  transaction, and any owner of a space can do so at any time. This is the
  **honest-server** cut: with a dump the departed member already holds `K_node` and reads the Memory whatever the
  link does until the scope next rotates (a group removal's own rotation; the tenant's explicit re-key), which
  re-keys every node — the ADR 0012 class; after it, a link they could rebuild reads what is added below its root
  until it is re-issued.
- **An optional share password** is an **operator-blind access-control gate**, *not* a cryptographic one
  ([ADR 0037](../decisions/0037-password-protected-shares.md)): the content stays E2EE under the unchanged
  `K_share` fragment, and the password gates **what the server serves** (a locked resolve returns only the
  challenge; no `wrap`/name/note/media without a valid token) — the same shape as the `contributorToken`. The
  server holds only `password_required` (coarse metadata) + `pw_salt` + `pw_verifier` (= `SHA-256` of the
  client-side `Argon2id(pw)` token) + `sealed_password` (one more opaque `K_share`-sealed ciphertext, a
  curator-only reveal copy) — never the raw password. **Honest limit:** it defends a **leaked link against an
  honest operator** (the realistic self-hosted / trusted case), not a malicious one who could bypass the check and
  serve ciphertext to a colluder holding the fragment — the same posture as the passive-operator-safe web viewer
  ([ADR 0026](../decisions/0026-client-trust-tiers.md)). A crypto-gate (fold `Argon2id` into the key) is a
  documented future upgrade.
- **The accountless-visitor terms acceptance is client-only** ([ADR 0035](../decisions/0035-server-terms-of-service.md)
  §3, [uc.sharing.share-accept](../features/sharing/use-cases/uc.sharing.share-accept.md)): before viewing, an
  account-less share visitor accepts the space's terms, but the acceptance lives in **localStorage** (per
  tenant, holding the last two pairs accepted: the space's revision with the operator section's revision) — the server records **nothing** about the visitor, so opening a share stays anonymous. The
  terms are **public operational content** (no blindness cost), served from the *share* via a guest
  `GET /api/shares/{shareId}/terms` keyed on the non-enumerable `shareId` (no `tenantId` existence oracle). It is an
  **operational gate, not enforcement** — bypassable by an accountless visitor and no legal-non-repudiation record;
  the operator's real remedy for a public share is the **decryptable-on-report** review-and-removal path above, not
  this prompt.

## Key-directory trust — TOFU pinning + out-of-band verification ([ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md))

Wrapping a content key to "Bob" needs Bob's identity public key, which the operator-blind server relays
from its directory. A malicious server could serve an attacker's key instead (key-substitution / MITM)
and read what is wrapped to it. The defense, and how it works, is
[keys, grants and trust § Trust](keys-grants-and-trust.md#trust-who-is-who): a **key chain** every rotation extends
with a step signed by the old identity **and** the old Secret-Key proof key; per tenant an owner-rooted **join
record** that anchors who is who; a **pin store** on the device that remembers each contact's newest head; and
**verifications**, statements a verifier signs after comparing safety number and chain code out of band. What follows
is what that defense buys against the operator, and what it leaves.

- **One gate, never a block on a write.** A key the client cannot anchor is never wrapped to and never accepted as a
  granter: a rotation declares that member pending, the grant-delivery worker declines the job
  (`identity_unanchored`), a group add refuses, and the member lists show *Can't confirm this key*. A legitimate key
  change is a signed step, so there is nothing for a human to accept, and nobody's write waits on the gate
  ([ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md) Decision 2).
- **A stolen device cannot rotate its way in.** Forging a chain step needs the password and the Secret Key, which a
  linked device never receives, and the server takes no grant to a key that is not its recipient's current one
  ([ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decisions 7 and 10).
- **A member the operator invents is an anomaly, unless it presents them as an owner.** Every newcomer writes a join
  record, so a non-owner without one is a membership only the server can have made, and no member's client wraps to
  it. What is left of the operator's class: the server's role list decides who is an owner, so a membership it writes
  as an owner is a root, trusted on first use; the tenant scope's own grant from another member, and a grant in a
  tenant this device cannot read, are judged by the pin alone. A join record an owner's retired key signed counts only
  with that owner's endorsement by their current key, which their securing writes for every record it rooted
  ([ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
  decision 21), so a thief holding the retired key mints nothing that anchors after the rotation. An honest server verifies a member invite's owner signature under the caller's identity key
  before it mints, so an owner's **session** without the vault mints no invite; and the operator, who mails the
  invite link, holds no code to open it.
- **The pin store and the who-trusts-whom graph stay the client's.** The pin store is sealed under the Master Key at
  opaque keyed ids; the server sees how many pins an account holds, which the grant rows already let it count
  ([ADR 0104](../decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)). A
  verification's content is sealed under the tenant key, but its row is addressed in the clear, so the server learns
  **who verified whom, in which tenant, and when** — never what was confirmed. Decided, not an oversight: the server
  authenticates every write, so it knows the verifier whatever the row is keyed by, and a compare's two writes arrive
  seconds apart, so an opaque subject would hide little (ADR 0111 § Consequences). A device refuses a pin store or device
  register served older than it has held (ADR 0028 Decision 7).
- **The first-use gap (honest), now narrow:** if the operator substitutes Bob's key *before* Alice ever
  pinned the real one — and re-wraps genuine content too — nothing looks wrong until the two **compare safety
  numbers and chain codes** on their profiles or **Key Transparency** exists (the eventual end-state). Inside a tenant
  that is left for root owners, and for a member the server presents as an owner, since roles are the server's word;
  everyone else is anchored by a join record an owner rooted. A newcomer anchors its inviting owner through the
  key-chain head sealed into the link and the epoch it is handed through the owner's own grant beside it (ADR 0111
  decision 19). Outside a shared tenant — the moderation delivery — the pin alone decides. **Withholding** a step is seen only by a device that saw a
  newer one, or by two people comparing chain codes.
- **First-online-wins is a per-key-holder honesty measure (keyed on the declining account, so its other
  devices are excluded too), not a global veto:** a key-holder that cannot anchor Bob's served key declines, but a
  *different* key-holder who never saw Bob has no pin and pins at first use. Deliberate — the blind server must not
  adjudicate a crypto claim, and a global quarantine on one worker's say-so would be a DoS. The value is raised
  detection.
- **Opt-in strict mode** (*verified* before a share) is **not built, and its shape is open:** a per-account flag
  cannot protect a shared scope, which any co-member can key
  ([uc.spaces.require-verified-identities](../features/spaces/use-cases/uc.spaces.require-verified-identities.md)).
- **Rotation is how a compromise ends, and it is the only remedy that reaches key material.** A revoke takes
  server access and never keys ([ADR 0058](../decisions/0058-revoke-never-takes-key-material.md)); securing the
  account replaces the Master Key, the identity and the Secret Key together and re-keys every scope
  ([ADR 0060](../decisions/0060-key-rotation-after-compromise.md)). It is **forward secrecy, not repair** —
  what was already read stays read — and it is gated on the **Secret Key** and the password, both checked by the
  server, so the attacker it defends against cannot rotate first.

## Client integrity & trust tiers — what a malicious operator can do

Confidentiality (Layer 2 below) is enforced by the **client**, so it holds against an *actively* malicious
operator **only on a client whose code the operator cannot silently alter**. This splits by client, and we state
it plainly ([ADR 0026](../decisions/0026-client-trust-tiers.md)):

- **Self-hosted:** operator = user → moot. No adversary serves you your own code. This is veyq's **default
  posture**, and therefore its high-assurance answer — the question dissolves rather than being mitigated.
- **Operator-served web:** the operator serves the code each load → an active operator can serve tampered code
  that captures keys/plaintext. **Protects against a *passive* operator only** (honest serving + at-rest),
  honestly labelled — linked from the share page; not yet surfaced to a signed-in member — never claimed
  operator-proof.
- **Trusted central / attested web** (code from an origin the operator does *not* control, reproducibly built —
  SaaS-era): restores the full guarantee in the browser, for the no-install flows (a public-share/upload
  viewer). An **operator-owned** domain is *always* operator-served — it owns its DNS, so it controls delivery.

Constant on every tier: the server can **never** read already-stored content (no keys); it **always** sees
operational metadata, can deny/alter access, and can *attempt* key-substitution on new exchanges (detectable,
above). **The honest boundary: passive operator → protected everywhere; active operator → protected when you
host it yourself, passive-only on someone else's instance.** Stating this precisely is a trust asset, as it is
for Signal and Proton.

### What holds today on a client the operator does not serve

A web client you serve yourself against someone else's server, a trusted/attested origin, and later the native
apps: there, confidentiality is **designed** to hold against an actively malicious operator. This table is the
one place that says how much of that design is **built**. Self-hosted, none of it is live (the operator is you);
on operator-served web, the answer is "passive only" whatever the table says. A change-request that closes a row
edits that row in its docs phase. **A check the server runs never closes a row here** — the operator *is* the
server, and skips the check or writes the table behind it; only something a client verifies with a key the
operator lacks can.

| An active operator … | Today | Closed by |
|---|---|---|
| reads content already stored | **Cannot** — it holds no key | — |
| gets a scope's key by admitting an account of its own | **Cannot** as a member: a key-holder's rotation re-grant, a delivery to a member who is behind or a group add wraps only to a member whose join record an anchored owner's invite key signed, and a non-owner without one is an anomaly. **Can** as an owner — the server's role list decides who is one, and an owner with no join record is a root, trusted on first use. **Cannot** with a stolen retired owner key: a join record that key signs counts only with the owner's endorsement by their current key, which their securing writes for every record it rooted (ADR 0111 decision 21). A stolen owner **password** cannot (honest server): the server mints a member invite only when its key carries the caller's own owner signature under the identity key it stores (`TenantHandler.signedByCaller`), which a session without the vault cannot make, and the invitee checks that signature under the owner's chain walked from the head sealed in the link, whose code the operator that mails it never sees. The admission tag that closed this row is gone ([ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 5, 2026-10-28) | [ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions 12 and 15 (the join record as the anchor) for a member; signed roles and removals for an owner, not designed (the membership protocol ADR 0020 declined) |
| plants a scope key of its own choosing in a grant | **Cannot** in the personal scope — its grant opens only if it is self-signed under the vault's own key, and the signed kind stops a group's grant posing as it — nor under a granter the recipient has already pinned, nor in a group of the active tenant under a granter without an owner-rooted join record, nor in a newcomer's invite — whatever a rotation carried must peel down to the inviting owner's own grant, which the operator cannot link to (ADR 0111 decision 19), nor at a later epoch of a scope the device holds a key of — the grant's epoch links must peel down to that key, which the operator never held (ADR 0111 decision 23). **Can** under a granter identity it fabricates where the pin alone decides — the tenant scope's own grant, a grant outside the active tenant — in a device's first grant of the scope — another device than the one that accepted the tenant, which holds the key its accept checked or minted — which the recipient pins on first use (below). **Can**, with a member the tenant anchors who is in no role in the group, in a device's first grant of a group — a newcomer's after a consent accept included, which holds nothing — because that granter is judged by its standing in the tenant; no gain over a group of that member's own the operator presents as this one (below) | [ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md) Decision 6, ADR 0111 decision 23 |
| installs an identity of its own on an account | **Can** — it writes the directory itself. Through the API a session or a password no longer suffices: rotation, change-password and recovery need a signature of the Secret-Key proof key ([ADR 0060](../decisions/0060-key-rotation-after-compromise.md) Decision 2), which binds every caller but the operator | Key Transparency ([ADR 0060](../decisions/0060-key-rotation-after-compromise.md) (g)); until then a member who pinned the account's key-chain head — and in a tenant every member, where the account joined with a join record — sees an anomaly and wraps nothing to it, because a new identity is reached only by a step signed by the old identity and the old Secret-Key proof key ([ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md) Decision 2) |
| serves a ciphertext as another object's — incl. a shared Memory's key on a private one, which then takes its uploads | **Cannot** — every seal binds its purpose and the object it is stored as, and a client opens it only at the address it asked for; what remains is *which authentic version* it is served — held only where a device keeps a mark, the two rows below — and, among one node's item marks, *which entry is the caller's own* (below) | [ADR 0072](../decisions/0072-a-seal-binds-its-object.md) |
| links a device of its own while the user links theirs | **Cannot** — the QR carries a secret the relay never receives, and both relayed values carry a MAC under the channel key it derives: the trusted device seals only to an answer whose MAC verifies, the new device opens only an envelope whose MAC does. What remains is an attacker who also reads the QR (below) | [ADR 0059](../decisions/0059-device-linking.md) §11 |
| gets a removed group member re-granted by the removal's own rotation | **Cannot** — the removal and its rotation are one request, and the remover's client wraps the new epoch only to the members it picked from, minus the one it removes, however the server lists them afterwards. A rotation *queued* to another key-holder is outside this row (below) | [ADR 0024](../decisions/0024-scope-key-rotation.md) Decision 4 |
| has a member seal new content under an epoch the removed member holds | **Cannot** once the member's device has held the newer epoch — each device remembers the highest epoch it has held for each scope and opens no older grant, whatever current epoch the operator pairs it with, and no newer one whose links do not peel down to the key it held — the removed member could link a key of its own to the old epoch, not to the one the rotation made. A member who never learnt of the newer epoch is the fork (below) | [ADR 0024](../decisions/0024-scope-key-rotation.md) Decision 6, ADR 0111 decision 23 |
| hands a removed group member, or an account's old identity after a rotation, what is added inside a folder or Memory that existed before the rotation — with a dump | **Cannot**, except through a link made before, until it is re-issued — every rotation re-keys every live and trashed node of the scope: a fresh key each, every field under the old one re-sealed, each `K_blob` re-wrapped. A write into a node not yet re-keyed is refused (`409 node_rekey_pending`, layer 1), and a member's device seals nothing under a node key bound to an epoch below the highest it has held for the scope (layer 2). What existed at the rotation stays readable to them; a link keeps opening the new key, and its `K_share` is rebuildable from the recovery copy they could open | [ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md), [ADR 0075](../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md) |
| wipes or rolls back the pin store or the device register, so a detected key change becomes a silent first use | **Cannot** on a device that has held one — it keeps the highest revision it opened and refuses an older store, a missing one and one that does not open, before every read and every write; a linked device starts from its linker's marks. A device with no mark yet trusts the first store it is served (below). **Residual, consented:** a refused document's one way back is the user starting it over (empty) after a confirmation that names the cost — no server response reaches that write, but a server that keeps a document refused can wait for the user to press it; the downgrade is visible and consented now, not silent. On a client the operator serves, it could ship a client that writes the empty store itself ([ADR 0026](../decisions/0026-client-trust-tiers.md)) | [ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md) Decision 7 |
| reads an uploader link's Memory — with the link and a dump; no activity needed | **Cannot** — an uploader link carries no wrap of `K_node`, only the Memory's drop public key; its guests seal under keys of their own, wrapped to that drop key, so the link plus a dump reads what its guests uploaded and nothing else — no other media, no sibling share's key | [ADR 0031](../decisions/0031-uploader-public-share.md) point 7 |
| reads another share's password, private label or note, or rebuilds its link — with a viewer or contributor link of the same Memory and a dump | **Cannot** — each share's recovery copy of `K_share` is sealed under the scope key `K_SC`, which no share link carries; the link's `K_node` and its own `K_share` open none of it. A member removed from a group may still hold the old links (below) | [ADR 0036](../decisions/0036-editable-share-note-recoverable-share-key.md) point 4 |
| makes every unlock derive near-forever | **Cannot** — a client derives only with the profile it writes | [ADR 0017](../decisions/0017-crypto-primitives-and-post-quantum-strategy.md) rule 5 |

**What stays open once every row reads *Cannot*** — each named where it is decided:
- a key substituted before anyone pinned it, between members who never compared safety numbers (TOFU's first-use
  gap, ADR 0028) — inside a tenant only a root owner's or a member's the server presents as an owner — which also
  covers a device with no trust-state mark yet (Decision 7), a shared grant signed by a granter identity the operator
  fabricated where the pin alone decides (ADR 0028 Decision 6) — in a device's first grant of the scope, since a later
  epoch must link down to the key it held (ADR 0111 decision 23); the device that accepted a tenant holds the key its
  accept checked or minted, so this is another device of the account (a new or newly linked one) — and with a party who holds that key (a member, or one
  removed while the tenant has not re-keyed since) planting a linked next one with the operator — and, for a newcomer whose invite a rotation by someone other
  than its owner carried, a party who held the owner's anchor epoch (a member, a former member, a thief of a member's
  device) planting a later one with the operator (ADR 0111 § What stays open);
- who counts as an owner, which is the server's word (ADR 0111 § What stays open);
- a group's first grant on a device — a newcomer's after a consent accept, a new or linked device — from any member
  the tenant anchors, in the group or not: with the operator, that member has the device write under a key of its
  choosing, and one removed from the group links it down to the epoch it held, so the device reads the history too.
  Measured, and accepted for a nil gain: which groups an account is listed in, who else is in one and who proposed an
  add are the server's word, so the same member makes a group of its own that the operator presents as this one;
  nothing local tells the plant from an honest first grant, and closing it needs signed adds, removals and roles
  (ADR 0111 § What stays open, inbox #750);
- an owner's securing step withheld from a new account: a thief of the owner's retired key, with the operator, passes
  that newcomer's invite check under the retired key; the newcomer's join record then names that key without an
  endorsement, so every co-member reads it as an anomaly (ADR 0111 § What stays open);
- who verified whom in a tenant, and when: a verification's content is sealed, its address is not — decided, since
  the server authenticates the writer anyway (ADR 0111 § Consequences);
- a genuine tenant invite redeemed with the operator's key, which needs the code the operator guesses offline against
  50 bits behind Argon2id, or a password the owner chose ([ADR 0111](../decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 1);
- a revoked or expired tenant invite: its link and code still open the epochs it was granted, useful with a dump of
  the ciphertext, since the tenant does not rotate on a revoke (ADR 0111 § Consequences);
- a rotation withheld from members who never saw it (a fork), and rotations queued to another key-holder, which
  know only the server's member list (ADR 0024 § Guarantee);
- an attacker who both reads a pairing QR and runs the relay (ADR 0059 §11);
- the members a moderator seals a statement's facts to (`getModerationStatementRecipients`), whose keys the client
  seals to unchecked: the moderation surface holds no pin store and may share no tenant with them, so there is nothing
  to anchor them by. An operator that substitutes a key reads that statement's facts text, beside the kind, target,
  ground and provision it reads anyway (ADR 0111 § What stays open);
- item favorites, sealed under `E_0` by choice ([ADR 0068](../decisions/0068-favorites-two-confidentiality-levels.md)),
  so a former member can read a set written after their removal. Which entry is the caller's own is proven by a
  self-tag only their client computes (ADR 0068 §9), but the operator can still serve the caller an older genuine
  version of their own set (freshness), and it can inflate or deflate a node's mark count — withholding entries,
  serving stored sets again as extra entries on its own, forging entries with any present or past member
  (ADR 0068 §9);
- a member removed from a group or the tenant: every member can rebuild every link of a Memory, so they may hold
  every link that existed on its Memories while they belonged, and use one with no dump at all until it is
  **re-issued** — which cuts them off against an honest server; the removal, or any owner of the space later, can
  turn every link of it off at once ([ADR 0075](../decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)). With a dump they also
  read the old share's later password, label or note edits, and the Memory's content under `K_node` stays readable
  whatever the link does (the ADR 0012 class) until the scope next rotates; after that rotation's re-key, what is
  added below a link's root stays readable through that link's `K_share` until it is re-issued
  ([ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 4);
- a guest identity in a drop box from before a rotation: its `K_up` is re-sealed to the new drop key, not replaced
  (only the guest holds it), so whoever held the old drop key reads what that guest adds afterwards, until the
  link is re-issued (ADR 0109 point 5);
- the names a Memory's guests gave, to an operator holding a viewer or contributor link of it. No link is served
  a guest's name, but the names stay sealed under field keys derived from `K_node` (a contributor's) or from an
  uploader's own key, which the
  link opens through the drop key — and the operator holds that ciphertext. Accepted: that link already decrypts
  every medium of the Memory ([ADR 0030](../decisions/0030-contributor-public-share.md) point 8).

**A stronger unlock method does not move this boundary** ([ADR 0026](../decisions/0026-client-trust-tiers.md)
records why): the operator ships the code that consumes whatever the unlock produces. Unlock convenience and
client integrity are orthogonal.

## Recovery — no escrow

A memories product must not turn "forgot password" into permanent loss, yet there is **no escrow** — the
same property that stops the operator reading data stops it rescuing data. Independent ways back in
(detail in the account/key model):

1. **Another enrolled device** still holds the keys.
2. **Emergency Kit (Secret Key) + master password** on a fresh device.
3. **Emergency Kit alone, after a mailed link** — a forgotten password with no device. The Secret Key opens the
   account's `RECOVERY` wrap, which the server releases only against a one-time token mailed to the account
   address ([uc.account.recover-access](../features/account/use-cases/uc.account.recover-access.md),
   [ADR 0054](../decisions/0054-password-recovery-two-factor-release.md)). There is no separate recovery code:
   the Secret Key *is* the recovery factor.

Losing **all** paths is the documented, accepted residual loss.

## Revocation — two layers

These are the two general **enforcement layers** ([ADR 0026](../decisions/0026-client-trust-tiers.md) — L1 access
control, honest-operator; L2 crypto, the only real operator boundary), here applied to revocation:

1. **Server-side access invalidation — instant, no keys.** The server stops honoring the revoked share
   token / membership and serves no further blobs. This is access control, which the server is allowed
   to do (it reads no content). Effective immediately, no client required.
2. **Content-key rotation — eventual, by a key-holder.** A client mints a new content-key **epoch** and
   re-wraps it to the remaining parties ([ADR 0024](../decisions/0024-scope-key-rotation.md)) — defense-in-depth
   against a revoked party who cached ciphertext *and* a **malicious operator** who would feed them post-removal
   ciphertext (an honest operator is already stopped by layer 1). May be queued until a key-holder is online; not a
   hole, because layer 1 already blocks new fetches. Every rotation re-keys every node of the scope too
   ([ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)) — without that, what is added
   inside an existing folder or Memory would seal under a key the revoked party holds. Writes into a node wait while
   it is re-keyed. A link made before keeps working, re-wrapped to the new key, and stays the revoked party's way in
   until it is re-issued.

**Whether layer 2 fires is a per-scope policy** ([ADR 0025](../decisions/0025-forward-secrecy-policy-per-scope.md)),
because rotation re-grants **every** remaining member (`O(N)`): **groups** rotate automatically on removal (small,
sensitive sharing), and on an owner's explicit re-key for the compromise case; the **tenant** (the primary, potentially large content home) uses layer 1 alone by default and
rotates only on an **explicit re-key** for the compromise case — a tenant owner's own, alone or opted into a
removal, or securing the account; a **personal** scope has
nobody to rotate away from on a removal, and rotates only when its own account is secured. Layer 1 is unconditional
for all three. Who commits a rotation and who re-keys the nodes, per kind, is
[keys, grants and trust § Rotating a scope](keys-grants-and-trust.md#rotating-a-scope).

**Honest limit:** content the revoked party already downloaded and decrypted cannot be retracted — rotation
protects only future content. Where layer 2 is policy-deferred (the tenant default), a revoked member who **both**
hoarded the key/ciphertext **and** colludes with a malicious operator retains that narrow residual until an explicit
re-key — the same posture 1Password takes for shared vaults.

## Deletion & erasure

One mechanism serves both data-loss protection and erasure: **soft-delete → trash → retention window →
scheduled hard-purge**. In backups every capture veyq's tooling wrote is kept at most B days — an instance setting,
30 until the Server Owner sets 7 to 365 — the newest too, on Compose and on the cluster, and an object pool drops
what only an older capture named; an erasure ledger beside leg (c)'s key, which no restore touches, is re-applied at
every start before the instance serves, so a restore of an older capture erases again what was erased since and
keeps a moderation block or case
([ADR 0056](../decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8). A copy the operator moved
off the host is theirs to delete by B, and a restore onto a new host re-applies only the ledger carried there. An
account is erased on request and an active tenant can be deleted
([ADR 0101](../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)): the account stops
at the request — sessions, sign-in, its pending invites and pairings, its memberships of shared scopes — and after the
scope trash's retention one purge erases it with its own scopes, every row keyed on it and its address wherever it was
copied; a tenant goes offline at once and is erased after the same window. An application left switched off in a
tenant is erased there on a day its members are told, `veyq.application.off.retention` after it went off: every node of
its kinds made before the day, in every scope of the tenant, goes through the trash purge's erase, and switched on
before the day nothing goes
([uc.platform.erase-switched-off-application](../features/platform/use-cases/uc.platform.erase-switched-off-application.md)).
Nothing is decrypted to erase: what goes is
ciphertext and metadata, its bytes through the reclaim list. What it put into shared scopes stays with the scope under
its opaque id, shown as a former member's, and holds and the moderation records naming it stay to their own ends, its
terms acceptance rows with them while a case names it. `scripts/validate_account_erasure_arity.py` holds that every
table reachable by foreign key from an account or a tenant is erased or cascades and that every column naming an
account or its address without a key is classified. The two steps carry
different authority. **Trashing is reversible** — an item moves to a trash and is restorable for the retention
window — so it may be done by **any member of the scope** (recovery, not destruction), and it is **audited**:
the trashed record carries **who** trashed it and **when**, so the action is accountable rather than anonymous.
The trash's grain is deliberately not uniform: a node (Memory/Chapter) moves to the **scope's** trash, reachable
without its own container ([ADR 0061](../decisions/0061-node-deletion-reversible-node-trash.md)); a medium moves
to a tab on **its Memory's** trash, a narrower "start light" scope with no cross-scope trash
([uc.memory.delete-media](../features/memory/use-cases/uc.memory.delete-media.md)). **Purging is irreversible** — the blob-level hard-purge that erases an item across all references. It
happens two ways: the **scheduled retention job** (or an explicit erasure request), and — for an item **already in
the trash** — a member may **expedite** its purge by hand (the item was already reversibly removed, so this only
brings the inevitable purge forward, bypassing the retention window). A member cannot purge a **live** item
directly: destruction always goes **trash → purge**, never a one-step "delete forever" of another member's live
content. A deletion audit records accountability without retaining the erased data.

**A public link goes with what it shows** ([ADR 0029](../decisions/0029-public-viewer-share.md) §7). Purging a
Memory deletes every link on it, and a curator can delete a link by hand: the share row becomes a tombstone that
keeps only its id, scope, node, role and creation time — the sealed title, note, label, recovery copy, password fields and
key wrap are erased — so the link can never answer again and its id is never minted again. A guest's identity row
(their sealed name and wrapped key) is erased once its link is deleted and nothing of theirs remains: no photo,
trashed or not, and no bucket. The photos guests added belong to the Memory, not to the link, and go only with it.
Purging a whole space removes its links outright and retires their ids. As for every erasure here, a dump or a
backup taken before keeps the old rows.
