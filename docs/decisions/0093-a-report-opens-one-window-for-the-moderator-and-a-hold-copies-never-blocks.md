# ADR 0093 — A report opens one window for the moderator, and a hold copies, never blocks

- Status: Accepted 2026-10-16; § 8 is built (the last editor per field group, and the take-back it tightens), the
  rest is not. It is on the V1 line (owner 2026-10-16,
  [inbox](../inbox.md) #462).
- Date: 2026-10-16

Provenance: **(M)** is measured in this worktree (file and line named); **(S)** comes from a read-only sweep of the
backend's deletion paths, its file:line references not re-read one by one; **(R)** is remembered law, not measured,
and listed under § Open legal questions.

## Context

The security model's target is that "Trust & Safety operates at the share boundary": a report carries the share
link, so the operator can decrypt a reported public share, verify, quarantine, preserve evidence and act. **Built
today: neither half** ([security-model](../crosscutting/security-model.md) § Sharing & share-boundary Trust &
Safety, M). There is no report intake, no operator action on a single share, and the one lever is suspending a
whole tenant ([ADR 0066](0066-tenant-suspension.md)); the Run page tells the operator to ask the tenant's owner
([moderation](../../site/src/content/docs/run/moderation.md), M).

Five facts shape the design:

- **Duties fall on each Server Owner as a host, not on veyq's authors** (R): act expeditiously once a precise notice
  gives knowledge (DSA Art. 6), offer an electronic notice path (Art. 16), give the affected a statement of reasons
  (Art. 17), report suspected offences against life or safety, child sexual abuse included (Art. 18, recital 56),
  remove within an hour on a terrorist-content removal order (Regulation 2021/784), and never copy child sexual
  abuse material to "collect evidence" (§ 184b StGB). veyq's part is to make an operator able to do this without
  reading anything nobody reported.
- **Attribution is partial.** The server records who uploaded a medium (`veyq_media.created_by` /
  `share_identity_id`, `owner_key`, [ADR 0034](0034-media-uploader-buckets.md)), a Depot file's current content
  (`veyq_node_attachment.uploader_user_id` / `share_identity_id`,
  [ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 8), a guest's identity
  folder (`veyq_node.share_identity_id`) and a share's creator (`veyq_share.owner_account`). It records nobody for
  a node's name or meta, a share's note or name, a bucket's name, or a space's name and icon (M, both baselines).
- **A seal opens with its object's key, and that key opens more.** `seal` is AES-GCM directly under the object's
  key ([`seal.ts`](../../packages/crypto/src/seal.ts), M): a node's name under its parent's key, a node's meta and
  a bucket's name under `K_node`, a share's note and names under `K_share`. Only a group's name and a space's icon
  use keys derived for that field alone (`group-name-key`, `K_icon`). A media is not a node: its `K_blob`s are
  wrapped under its direct parent's `K_node` ([en.memory.media](../features/memory/enablers/en.memory.media.md),
  M); a chapter has a `K_node` of its own ([uc.memory.chapters](../features/memory/use-cases/uc.memory.chapters.md),
  M); a guest's upload opens through the drop key → identity key → `K_blob`
  ([ADR 0031](0031-uploader-public-share.md) point 7, M).
- **Data is destroyed on many paths, some at once** (S): a share delete or re-issue nulls every sealed column and
  key wrap; every edit of a name, meta, note, password, bucket name or space name and icon overwrites in place; a
  Depot replace or cover replace drops the old blob row; a guest's delete of an own upload and a member's
  expedited purge skip the 30-day trash; `abandonShareNode` erases an empty guest placeholder at once. Every store
  object goes through one choke point, `StoreReclaimSweep`, about an hour after its row
  ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)).
- **No log holds evidence.** No table stores an address; logs are operational and name no person
  ([ADR 0092](0092-logs-are-operational-and-name-no-person.md) § 1). A guest has no account and no address, so
  without a record an upload through a public drop link names nobody.

## Decision

The mechanism lives in **core** ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)): node,
share, report and hold are platform terms, and Depot needs them as much as Memories.

### 1. Every report is a report share plus a hold

- **A report share** is minted by the reporter's client for exactly what is reported:
  - a **node** (Memory, chapter, folder, Depot file): `K_node` wrapped under a fresh `K_report`, the existing share
    mechanism;
  - a **medium**: the `K_blob` of **every** blob it has (original and derivatives) wrapped under `K_report`, no
    `K_node`. A guest-uploaded medium is reached through the drop key and the identity key; the drop private key
    never leaves the client, and the identity key goes only when the reporter chooses "everything by this
    uploader";
  - a **text field**: the field key of § 9.
- **Anyone who can see it can report it.** A member, and a guest of a `READ` or `READ_WRITE` link, whose client
  reaches `K_node` through `symWrap(K_share, K_node)`; the server checks that every reported id lies inside what the
  reporter's grant or link reaches. A guest of an upload link (`WRITE_ONLY`) sees only their own uploads, so they
  report **the link** (its name and note) and nothing else; its `K_share` opens no content and travels in the
  report as is.
- **Selection.** "Report" is one more action in the existing selection bar (nodes and media) and in an item's menu
  and the lightbox, for guests as for members; one report holds up to 100 items, beyond that the UI offers to
  report the enclosing node.
- **A report share is invisible and inert to everyone but the moderator.** It appears in no listing, carries no
  `sealed_share_key`, no member can revoke it, it lives as long as its case, and the server serves it **only
  against proof of the moderation key** (§ 2) — never to the client that minted it, or a reporter about to lose
  access would keep it. It is served regardless of what later happens to the original share (revoked, expired,
  password changed) or to the tenant (suspended).
- **Context is not evidence.** The reporter's client may add what it saw (a note's text) as context; evidence is
  only what the moderator opens from the held ciphertext.

### 2. A report is sealed to the moderation key

- **The moderation key is the Server Owner's identity key** today (it exists from first-run setup,
  [ADR 0065](0065-admin-ui-cli-separate-service.md)). The reporter's client seals to its public half (`sealTo`).
- **In the clear:** category, time, the reported ids (the hold needs them). **Sealed:** `K_report`, the reporter's
  text, the reporter's contact, the context. A database dump opens no report.
- **Nobody else learns of a report** until it is decided: not the creator, the uploader, the tenant owner or a member.
  The Server Owner is notified by mail to the instance contact (§ 11): category and "new case", never a key or a
  link; urgent categories at once, the rest bundled. A reporter who left a contact gets a receipt and, later, the
  decision (DSA Art. 16(4)–(5), R).
- **Nothing is blocked by a report.** A human decides.

### 3. Review is a share view in moderation mode; deciding is admin-web's

- **admin-web shows the queue** (clear fields only) and opens `https://<tenant domain>/s/…#<sealed report>`.
- **web/'s share viewer in moderation mode** signs the Server Owner in and unlocks their vault (any method they have,
  a trusted device included; passkeys when they exist), opens the report, proves the moderation key to fetch the
  report share, and shows it **with the guest viewer** plus the report's details, read-only. It loads no member shell
  and needs no tenant membership; this is the only flow where a Server Owner signs in on a tenant host without one.
- **Every decision is a Server-Owner operation on the admin channel** ([ADR 0065](0065-admin-ui-cli-separate-service.md)),
  in admin-web and with the same reach in `veyq-admin`, so an operator away from admin-web can act over SSH.

### 4. A hold is a snapshot plus a pin

- **On a report** the server copies the affected rows into hold tables: node, media and blob rows, key wraps,
  sealed fields, attribution (§ 8) and guest-write records (§ 10) — ciphertext and metadata only — and **pins** the
  blobs' `storage_key`s.
- **The one hook into existing code:** `StoreReclaimSweep` skips a key an active hold pins, and a skip is not a
  failed attempt (or the key would be abandoned after five). Every other path keeps running: deletes, trash,
  overwrites, revokes, `abandonShareNode`. So the hold is **invisible by construction**, the tenant's quota falls
  as the user expects, and the operator pays for held bytes.
- **The report share reads from the snapshot**, which makes review independent of anything done after the report.
- **It ends** when the case is dismissed or after **90 days**, which the moderator can extend (an authority's
  request, an open objection). Then the snapshot is dropped, the pins released, and the sweep catches up.

### 5. Bounds against misuse

The rate-limit posture of every new endpoint ([ADR 0051](0051-declarative-rate-limit-posture.md): per IP, per
principal, per share); a generous cap on a reporter's open reports, as a cost bound; **one case per reported item**,
a further report attaching its reason to the case and the hold; a dismissal ends the hold at once; the moderator can
bar a reporter. A hold harms little even when abused: the reporter never gets the held data back (§ 1), the cost is
a delayed erasure and a moderator's look.

### 6. Measures are chosen when deciding, in steps, and all are reversible

| Step | Effect |
|---|---|
| Block the share | the link answers as revoked |
| Block the content | the server refuses to serve it to anyone, members included; members and guests see "Blocked by the operator", no reason; the uploader can delete it but not replace or restore it |
| Block the uploader | a member: sign-in on the instance; a guest: the identity **and the link it came through** (it could mint a new identity on the same link); the link's creator may issue a new one |
| Take the tenant offline | [ADR 0066](0066-tenant-suspension.md) |

Blocking deletes nothing, so every step can be lifted. **An address is evidence, never the ground of a block.** A
copy a member made (Depot's "Copy to…") is a new blob under a new key; with no dedup the server cannot know it, and
a block reaches only the reported copy.

### 7. Statement of reasons, and objection

- **After a measure** the statement (DSA Art. 17) goes to the share's creator and the uploader, and to the scope
  owner on a content block; the moderator can defer it in a criminal matter. A dismissed report is told only to the
  reporter.
- **Objection in the app**, for the affected only, on the placeholder and the statement: free text sealed to the
  moderation key, reopening the case; one per measure, then the courts. A guest uses the instance contact. A
  reporter has no objection to a dismissal; they may report again with more.

### 8. Every sealed field records its last editor

The last editor of each field group — name, meta, note, icon — is stored as an opaque `user:{id}` or
`share:{identityId}`, the class [ADR 0034](0034-media-uploader-buckets.md) and
[ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) already disclose, for the same
reason (a sealed attribution could name anyone). The server knows it; whether members see it is a product question
outside this ADR. A hold freezes the reported revision with its editor. This also tightens
[ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6: a guest may abandon
an empty placeholder only if its name was last written by that guest's identity, and never one a live link is rooted
on. The editor is the authenticated caller of the write, never a request field, and every write of the ciphertext counts — a re-seal by a move or a restore as much as a new text, since the server cannot tell them apart (owner 2026-10-16). Clearing a field clears its editor. A field only its own row's subject writes (a share identity's name, a member's profile) needs no column. The model, the columns and the tests that pin each writer are
[en.platform.field-editor](../features/platform/enablers/en.platform.field-editor.md).

### 9. A text or icon field seals under a key of its own

`node-name`, `node-meta`, `bucket-name`, `share-note`, `share-name`, `share-memory-name`, `share-identity-name`,
`member-name` and `member-avatar` seal under `K_field = HKDF(K_parent, salt, "veyq/v1/field/<purpose>")`, the salt
drawn fresh **per write** and stored beside the ciphertext. A report hands over `K_field`: it opens that one
revision of that one field, and the operator verifies by opening the held ciphertext, whose AAD binds the object
([ADR 0072](0072-a-seal-binds-its-object.md)). `group-name` and `space-icon` already work this way; a medium's
`blob-meta` is covered by its `K_blob`. veyq is not live, so this is a stored-format change with a reset, not a
suite v2. AES-GCM is not key-committing: only a field's own writer could craft a ciphertext that opens differently
under two keys, and § 8 attributes it to them.

### 10. The guest-write record

Taken over from [ADR 0092](0092-logs-are-operational-and-name-no-person.md), which keeps the retention period's
range and its no-extension rule (§ 2 there).

- **One core table, `veyq_guest_write_record`**, one row per guest write that enters a guest as last editor (§ 8):
  upload commit, identity creation, folder creation, rename, meta. A row holds the client's address, the server
  time it was observed, the share identity, the object (kind, id, field group) and `expires_at`. No User-Agent.
  Nothing a member writes or anyone reads is recorded.
- **The record never takes an address a client can choose.** A forged address in evidence names an innocent third
  party. Quarkus takes the leftmost `X-Forwarded-For` entry and, without trusted proxies, believes any peer, so
  where the address comes from is decided apart from this ADR (owner 2026-10-16), in
  [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md): the record takes the address of
  veyq's one core resolver, the rightmost `X-Forwarded-For` entry that is not a trusted proxy. When the resolver
  yields no address, nothing is recorded. On Compose the address is strong by construction; on Helm it is only as
  strong as an enforced NetworkPolicy (0094 decision 6), and the disclosure and the Run docs say so. The
  slice test is 0094's: a guest write carrying `198.51.100.66, 203.0.113.77` records `203.0.113.77` (the
  control), and `198.51.100.66` is in no record and no log.
- **Switch `core.public-shares.guest-write-record`**, operator only (`*`), on by default
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)). Off: nothing is
  recorded, and the next purge deletes every live row; hold copies stay.
- **Retention is an instance setting** (0092 § 2: N days, 7 by default, 1 to 30). A row is written with
  `expires_at = observed_at + N` as N stands then, and the purge deletes a row when
  `expires_at <= now OR observed_at + N_now <= now`, so a shortening reaches every row at once and a lengthening
  only new ones: no guest is kept longer than the page told them. The purge also runs at startup, so a restored
  backup's stale rows go at boot; it is the reader `EveryStoredTimeHasAReaderTest` asks for. The foreign keys
  delete with the identity or object; a hold has copied what was reported.
- **Disclosure** on every guest write page and in the terms, with the value current at the write: "your address is
  kept for N days, longer only if a report concerns this content (up to 90 days, extendable)".

### 11. The moderation log, and the instance contact

- **An append-only table**, one row per case event: received, report attached, **opened** (the server served the
  report share against the moderation key — the decryption itself it cannot see), decided (dismissed, or the step
  and ids), lifted, hold extended or ended, statement sent or deferred, objection received, handed to an authority.
  Structured fields in the clear (event, time, moderator account, ids, step); every free text (reasons, notes, an
  authority's reference) sealed to the moderation key. No content, no key. Shown per case in admin-web. Never in a
  log: a log names a case id and nothing more (0092 § 1). Not tamper-evident: the operator owns the database, and
  the log is for accountability to the affected and within a team.
- **An instance contact**, set in admin-web and the CLI: the DSA point of contact, the notification address, and
  the address the disclosures and statements name.

## Consequences

- The operator can act on one share, one item or one uploader instead of a whole tenant, and reads only what was
  reported. Unreported content stays unreadable; the T&S target in the security model becomes buildable as written.
- Costs: a new share kind (medium-scoped and field-scoped), hold tables and the sweep's skip, a moderation mode in
  the share viewer, admin-web's queue and the CLI's moderation commands, an editor column per field group (built), per-field
  keys in the crypto suite with new vectors, the guest-write record, the moderation log. `EveryStoredTimeHasAReaderTest`
  needs a reader for each new time: the purge for records and holds, admin-web for the log.
- **A single operator reviews their own measure** on objection, and the whole Server Owner vault is open while
  reviewing. A moderator role with its own key pair (inbox #463) answers both and adds a deputy for when the owner
  is away.
- An operator away from admin-web acts over SSH with `veyq-admin`, and the Run docs show how to reach admin-web
  remotely (an SSH tunnel or a VPN).
- **Limits stated in the docs:** a copy escapes a block; held data lives in backups beyond every retention; a
  household instance may be outside the DSA and the GDPR (R).
- **Docs** ([moderation](../../site/src/content/docs/run/moderation.md) stops sending the operator to the tenant
  owner; the security model's T&S section, `CLAUDE.md`'s invariant on what the server keeps,
  [ADR 0035](0035-server-terms-of-service.md) decision 3 and the terms template) change in one CR shared with
  0092.

## Rejected alternatives

- **Block on report.** A report is not knowledge of illegality, and it would let anyone switch off any link.
- **Copy the reported content into the report**, sealed to the operator. A copy of child sexual abuse material is the
  thing never to make; the hold pins the originals instead.
- **A server-held report key under the config-secret KEK**, to skip the sign-in. The server process could then open
  every reported item, and [ADR 0050](0050-operator-config-secret-encryption.md)'s property — every `wrapped_*`
  column is client-sealed and unopenable by the operator — would break for the most sensitive data on the instance.
- **Reports in the clear in the database.** A dump would open every reported item.
- **Moderating by mail**, information in the mail and the decision as a reply. Puts keys at a mail provider, makes a
  spoofable mailbox a Server-Owner channel past the admin channel, needs inbound mail, and mail scanners pre-open
  links. Mail notifies only.
- **Moderation actions in web/.** They would put Server-Owner operations back on the public listener, in the surface
  that renders untrusted content. **A vault in admin-web**, to avoid web/: contradicts admin-web's "no vault, no
  content key, ever".
- **A review mode that presents a password token** to the original share. Superseded by the report share, which has
  no password and does not depend on the original.
- **Handing over `K_share` or a parent key for a text field.** Opens the whole subtree or the siblings.
- **Every editor's history** instead of the last editor. More metadata for what the hold already freezes.
- **Blocking by address.** Addresses change, and the record would become more than evidence.
- **For the guest-write record** (from 0092): hashing the address (an IPv4 hash falls to 2³² guesses) or truncating
  it (a /24 loses the evidence); sealing it to a hold key (the operator sees every address live at their own proxy);
  columns on the identity and upload tables (each write overwrites the last, and the purge would touch two
  applications); a tenant owner's switch (the operator answers for the server); an access log (0092).

## Open legal questions

Not legal advice; each needs someone qualified before anything ships.

1. Does the DSA apply to a household instance, and what follows from the GDPR's household exemption
   (Art. 2(2)(c))?
2. The terms template's clauses on the notice path, on keeping reported content and on the guest-write record.
3. The hold's maximum (90 days, extendable) and the moderation log's retention (the general German limitation
   period is three years).
4. The record's basis: Art. 6(1)(f), no retention duty on a host (*Breyer*, C-582/14); its retention range (N
   days, at most 30), the cap being what bounds the legitimate interest; and that backups hold it beyond its
   period.
5. Where an Art. 18 report goes in Germany (the BKA's central reporting office, R), and what an operator may keep
   when reporting child sexual abuse material (§ 184b(5) StGB).
6. The lead time before a first terrorist-content removal order, and whether the one-hour deadline binds a small
   host the same way.
7. The current state of a German IP-retention duty, and whether it would reach a host.
