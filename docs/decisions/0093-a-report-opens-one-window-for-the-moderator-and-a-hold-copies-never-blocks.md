# ADR 0093 — A report opens one window for the moderator, and a hold copies, never blocks

- Status: Accepted
- Built: partly (not built: § 1 a field on its own other than a node's name, and the reporter's handover of a guest's
  identity key; § 11 the records' extension while proceedings are pending; § Legal basis, Still open 1 a hold until
  the handover beyond 90 days, and Still open 2 an authority's link to a snapshot)
- Date: 2026-10-16

Provenance: **(M)** is measured in this worktree (file and line named); **(S)** comes from a read-only sweep of the
backend's deletion paths, its file:line references not re-read one by one; **(L)** is the legal research of
2026-10-16, summarized with its primary sources under § Legal basis — research, not legal advice.

## Context

The security model's target is that "Trust & Safety operates at the share boundary": a report carries the share
link, so the operator can decrypt a reported public share, verify, quarantine, preserve evidence and act. **Built
today: neither half** ([security-model](../crosscutting/security-model.md) § Sharing & share-boundary Trust &
Safety, M). There is no report intake, no operator action on a single share, and the one lever is suspending a
whole tenant ([ADR 0066](0066-tenant-suspension.md)); the Run page tells the operator to ask the tenant's owner
([moderation](../../site/src/content/docs/run/moderation.md), M).

**Who runs an instance.** veyq assumes an instance is hosted not only for a family but also by a club or a
company (owner 2026-10-16). Such an operator is a **hosting service** under the DSA, bound by Art. 11–18, and not
an online platform as long as a share's audience is chosen by its sender (recital 14); a **controller** under the
GDPR; a hosting service provider under the terrorist-content Regulation (EU) 2021/784 ("TCO"); and a provider who
identifies itself under § 5 DDG or § 18 MStV (L). A household instance is the subset where some of these duties
may not apply. **The design never relies on that**: every mechanism below is built for every instance, and it costs
nothing until someone reports. What a household operator may leave out is **their own call**, and it is made by
leaving values empty, not by a mode (§ 14, owner 2026-10-21): every instance serves the full texts, and an unset value
shows as not stated — never by leaving off the guest-write record (§ 10, kept wherever a guest may write), and never the report, the hold, the block or the moderators, since criminal law and the hosting privilege's
"once known, act" reach every operator (§ 7 DDG covers unpaid services too, L). The choice says plainly that it is
the operator's assessment and not veyq's. The hosting-not-platform reading holds while a share has no directory, no search
and its key in the fragment; a feature that changes any of these (a public gallery, a listing of public shares, a
link without a fragment key) needs its own ADR.

Five facts shape the design:

- **Duties fall on each Server Owner as a host, not on veyq's authors** (L): act expeditiously once a precise notice
  gives knowledge (DSA Art. 6), offer an electronic notice path (Art. 16), give the affected a statement of reasons
  (Art. 17), report suspected offences against life or safety, child sexual abuse included (Art. 18, recital 56),
  block within an hour on a terrorist-content removal order and keep what was blocked for six months (TCO Art. 3,
  6), and never copy child sexual abuse material to "collect evidence" (§ 184b StGB). veyq's part is to make an
  operator able to do this without reading anything nobody reported.
- **Attribution is partial.** The server records who uploaded a medium (`veyq_media.created_by` /
  `share_identity_id`, `owner_key`, [ADR 0034](0034-media-uploader-buckets.md)), a Depot file's current content
  (`veyq_node_attachment.uploader_user_id` / `share_identity_id`,
  [ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 8), a guest's identity
  folder (`veyq_node.share_identity_id`) and a share's creator (`veyq_share.owner_account`). It records nobody for
  a node's name or meta, a share's note or name, a bucket's name, or a space's name and icon (M, both baselines).
- **A seal opens with its object's key, and that key opens more.** Before § 9 a field was sealed with AES-GCM
  directly under its object's key ([`seal.ts`](../../src/packages/crypto/src/seal.ts), M): a node's name with its
  parent's key, a node's meta and a bucket's name with `K_node`, a share's note and names with `K_share`. Only a
  group's name and a space's icon used keys derived for that field alone (`group-name-key`, `K_icon`), and those per
  epoch. A media is not a node: its `K_blob`s are
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
share, report and hold are platform terms, and Depot needs them as much as Memories. Who moderates — moderators the
Server Owner appoints, sharing one instance moderation key — is
[ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)'s; §§ 1–3, 6–7 and 11–13
state what it means here. Every fixed text this ADR adds — the statement and its passages (§ 7), the disclosure
(§ 10), the order forms (§ 12) and the child-sexual-abuse warning (§ 13) — is a draft until a qualified legal review.

### 1. Every report is a report share plus a hold

- **A report share** is minted by the reporter's client for exactly what is reported:
  - a **node** (Memory, chapter, folder, Depot file): `K_node` wrapped under a fresh `K_report`, the existing share
    mechanism;
  - a **medium**: the `K_blob` of **every** blob it has (original and derivatives) wrapped under `K_report`, no
    `K_node`. A guest-uploaded medium is reached through the drop key and the identity key; the drop private key
    never leaves the client, and the identity key goes only when the reporter chooses "everything by this
    uploader";
  - a **text or icon field**: that revision's `K_field` (§ 9).
- **Anyone who can see it can report it.** A member, and a guest of a `READ` or `READ_WRITE` link, whose client
  reaches `K_node` through `symWrap(K_share, K_node)`; the server checks that every reported id lies inside what the
  reporter's grant or link reaches. A guest of an upload link (`WRITE_ONLY`) sees only their own uploads, so they
  report **the link** (its name and note) and nothing else; its `K_share` opens no content and travels in the
  report as is.
- **Selection.** "Report" is one more action in the existing selection bar (nodes and media) and in an item's menu
  and the lightbox, for guests as for members; one report holds up to 100 items, beyond that the UI offers to
  report the enclosing node.
- **The report dialog** asks for what DSA Art. 16(2) names (L): a reason (required), a confirmation that the
  statements are accurate and complete in good faith (required), a contact (optional), and a category — child
  sexual abuse material, terrorist content, threat to life or safety, other illegal content, breach of the terms.
  On the first, the dialog says that a report without a name is expected (Art. 16(2)(c) exempts it). The exact
  location is the report's own ids.
- **A report share is invisible and inert to everyone but the moderators.** It appears in no listing, carries no
  `sealed_share_key`, no member can revoke it, it lives as long as its case's records (§ 11), and the server serves it **only
  against proof of the moderation key** (§ 2) **and to a session with the `MODERATOR` role**
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 2) — never to the
  client that minted it, or a reporter about to lose access would keep it. It is served regardless of what later happens to the original share (revoked, expired,
  password changed) or to the tenant (suspended).
- **Context is not evidence.** The reporter's client may add what it saw (a note's text) as context; evidence is
  only what the moderator opens from the held ciphertext.
- **Where the reporter hands over no identity key**, "everything by this uploader" is a moderator's step taken from
  the case (§ 6): the items that uploader had uploaded to the space, counted from the clear attribution, blocked and
  held. The flow is [uc.moderation.report](../features/moderation/use-cases/uc.moderation.report.md).

### 2. A report is sealed to the moderation key

- **The moderation key is the instance moderation key's current epoch**
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 2): one key, granted
  to every moderator the Server Owner appoints, the Server Owner among them, and rotated into a new epoch when one leaves. The
  reporter's client seals to the public half the server publishes for that epoch (`sealTo`); a report stays sealed
  to the epoch it was made under.
- **In the clear:** category, time, the reported ids (the hold needs them). **Sealed:** `K_report`, the reporter's text,
  the context — and, apart from them, the reporter's contact (`moderation-report-contact` [epoch]), so it can go alone
  (§ 11). A database dump opens no report.
- **Nobody else learns of a report** until it is decided: not the creator, the uploader, the tenant owner or a member.
  Every accepted moderator is notified by mail at their account address, and the instance contact's notification
  address where one is set (§ 11;
  [ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 7): category and "new
  case", and where the moderation page is as plain text — Settings → Moderation, or `/moderation` on a space's address
  (owner 2026-10-22) — never a key, a host or a link; the first three categories of § 1 at once, the rest bundled. A
  reporter who left a contact gets a receipt and, later, the decision (DSA Art. 16(4)–(5), L). Both go by instance
  mail ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3), and the server
  never stores the address in the clear: the reporter's client sends it once beside the sealed copy and the server
  mails the receipt from memory, bounded per address; for the decision, the deciding moderator's client opens the
  sealed copy and sends the address once the same way (§ 7).
- **Nothing is blocked by a report.** A human decides.

### 3. Review is a share view in moderation mode; deciding is the moderation surface's

- **src/web/'s moderation surface**, a route on every tenant host reachable only with the `MODERATOR` role and the key
  proof ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6), shows the
  instance-wide queue (clear fields only) and opens `https://<tenant domain>/s/<case id>#moderation` on the reported
  tenant's host (as built: the fragment names the mode and carries no key; the case's reports and report shares come
  sealed through the key-proved review reads, uc.moderation.review-a-case).
- **src/web/'s share viewer in moderation mode** signs the moderator in and unlocks their vault **only to open their
  moderation grants** — no member shell, no scope grant fetched — keeps the moderation key and drops the Master Key
  and the identity private key before any reported content opens. It then opens the report, proves the moderation
  key to fetch the report share, and shows it **with the guest viewer** plus the report's details, read-only. It
  needs no membership in the reported tenant; this is the only flow where an account acts on a tenant host it does
  not belong to. The surface never offers to keep a trusted device there (0099 § 6).
- **Every decision is a moderator operation** in that surface — dismissing, the steps of § 6 within a moderator's
  reach, the statement of reasons and its deferral, objection decisions, hold extensions, the order path (§ 12), the
  CSAM flow and the BKA export (§ 13) — gated by the `MODERATOR` role and the key proof on the public listener.
- **admin-web keeps the Server Owner's part** on the admin channel ([ADR 0065](0065-admin-ui-cli-separate-service.md)),
  with the same reach in `veyq-admin` so an operator away from admin-web can act over SSH: the moderator roster, minting
  and rotating the key, the instance contact (§ 11), the steps of § 6 that stay theirs, and the order path (§ 12).

### 4. A hold is a snapshot plus a pin

- **On a report, or on an authority's order without one (§ 12),** the server copies the affected rows into hold
  tables: node, media and blob rows, key wraps, sealed fields, attribution (§ 8) and guest-write records (§ 10) —
  ciphertext and metadata only — and **pins** the blobs' `storage_key`s. A hold is the restriction of processing
  the GDPR knows (Art. 18), not a further use: invisible, readable only with the moderation key, never back to the
  reporter.
- **The one hook into existing code:** `StoreReclaimSweep` skips a key an active hold pins, and a skip is not a
  failed attempt (or the key would be abandoned after five). Every other path keeps running: deletes, trash,
  overwrites, revokes, `abandonShareNode`. So the hold is **invisible by construction**, the tenant's quota falls
  as the user expects, and the operator pays for held bytes.
- **The report share reads from the snapshot**, which makes review independent of anything done after the report.
- **It ends by its trigger**, each end an `expires_at` of its own (L):

  | Hold | Ends |
  |---|---|
  | Case open | at the decision, at the latest **90 days** after the report |
  | Case dismissed | **at once** |
  | Measure taken | **six months** after the statement of reasons is sent (§ 7; a deferred one counts from its sending, and where nobody can be told, from the decision); a lifted measure ends it with the lift |
  | Child sexual abuse material | at the recorded handover to an authority (reported to one, or information given to one), at the latest **90 days** after the report that took the hold or made the case one of this category; a measure or a statement never moves it further, an authority's order keeps what it holds (§ 13) |
  | Terrorist-content removal order | **six months** after the block, never shorter; longer on the authority's request while a review runs (TCO Art. 6) |
  | European Preservation Order | **60 days**, 30 more when the authority extends it, and while a confirmed production order needs it (Regulation 2023/1543 Art. 11) |
  | Any other authority order | the duration it names; one that names none, six months |

- **An extension** needs a concrete reason, sealed in the moderation log (§ 11) as its own event with its own
  `expires_at`: an authority's request or order with its reference, an objection, a lawyer's letter, a lawsuit, an
  Art. 18 report in progress. Each step is at most **six months** and lapses without a new reason. No extension
  ends a hold before the duration an authority's order sets. Beside the sealed reason the extension names its **reason
  type in the clear** — `AUTHORITY_REFERENCE`, `ART18_REPORT_IN_PROGRESS`, `OBJECTION`, `LAWYERS_LETTER`, `LAWSUIT` —
  metadata only, the details staying sealed. On a child-sexual-abuse case only an Art. 18 report in progress or an
  authority's request extends it — keeping that content is exempt only while it serves the report (§ 184b(5) StGB, L)
  — and **the server checks it**: such an extension must carry `AUTHORITY_REFERENCE` or `ART18_REPORT_IN_PROGRESS` and
  is refused otherwise (`409 extension_reason_not_allowed`, owner 2026-10-18); the handover still ends the hold. On
  every other case the type is optional and nothing about it is refused.
- **An authority's order holds to the end it sets**: no extension, lift or decision shortens it, from either door
  (§ 12).
- **At the end** the snapshot is dropped, the pins released, and the sweep catches up. The same purge that runs at
  startup (§ 10) ends an expired hold. The model is [en.moderation.hold](../features/moderation/enablers/en.moderation.hold.md).

### 5. Bounds against misuse

The rate-limit posture of every new endpoint ([ADR 0051](0051-declarative-rate-limit-posture.md): per IP, per
principal, per share); a generous cap on a reporter's open reports, as a cost bound; **one case per reported item**,
a further report attaching its reason to the case and the hold; a dismissal ends the hold at once; the moderator can
bar a reporter. A hold harms little even when abused: the reporter never gets the held data back (§ 1), the cost is
a delayed erasure and a moderator's look.

### 6. Measures are chosen when deciding, in steps, and all are reversible

| Step | Effect | Taken by |
|---|---|---|
| Block the share | the link answers as revoked | a moderator |
| Block the content | the server refuses to serve it to anyone, members included; members and guests see "Blocked by the operator", no reason; the uploader can delete it but not replace or restore it | a moderator |
| Block the uploader | a member: sign-in on the instance; a guest: the identity **and the link it came through** (it could mint a new identity on the same link); the link's creator may issue a new one | a guest: a moderator; a member: the Server Owner |
| Take the tenant offline | [ADR 0066](0066-tenant-suspension.md) | the Server Owner |

**A moderator proposes the Server Owner's steps**; the case shows the proposal, and the Server Owner takes it in
admin-web or `veyq-admin` ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
§ 6). Blocking a member's sign-in and taking a tenant offline reach content and people nobody reported, while blocking the share and the content already stops what was reported from being served.

Blocking deletes nothing, so every step can be lifted. Every step hangs on ids, never on a report or a key, so an
authority's order (§ 12) takes the same steps. **A step ends with its target** (GDPR Art. 5(1)(e)):
when what it stands on is erased — a link deleted, a node or an item purged, a guest identity dropped, a space, an
account or a tenant purged — the eraser ends it in its own transaction, lifted by no one and logged so; standing on
nothing it would block nothing and still hold its case's records forever (§ 11). A step taken on a target already
erased is **taken and ends at once, not refused** (owner 2026-10-19): the decision and its statement stay, and the hold
stays as the evidence it is. The hold is not ended by it: it keeps its own end (§ 4). Wherever a lifted step is shown —
the case, the affected's statement, the Art. 18 export — one that ended with its target says so ("ended when the content
was erased"), never plain "lifted": nobody lifted it. **An address is evidence, never the ground of a block.** A
copy a member made (Depot's "Copy to…") is a new blob under a new key; the server cannot know it, and a block reaches
only the reported copy. Where blob rows share one stored object inside a node (a cover, a banner, a gallery original,
[ADR 0013](0013-blob-storage-presigned-direct-to-store.md) § 5), **a block hangs on the stored object** (owner
2026-10-19): a block on one reaches every row on the object - the same bytes under another id, refused `451` - and
erasing the blocked item does not end it while another row still holds the object. It then stands on that object alone
(`veyq_moderation_block.held_objects`) and ends when the object's last row goes (`MeasureTargets.objectsGone`, from
`BlobUploadStore.deleteBlobRow` and `ScopePurge`) or a moderator lifts it. So deleting a blocked photo never serves it
again as the Memory's cover or banner. The same holds for a step taken **after** the item was erased (owner 2026-10-19):
it does not end at once where another row still holds the item's object — it hangs on that object, read from its
case's hold since the item's own rows are gone (`MeasureTargets.survivingObjects`), so deleting a reported photo before
the decision does not leave its bytes served as the banner either. Where no hold holds the item, nothing names its
objects, and the step ends at once. The review and the steps are
[uc.moderation.review-a-case](../features/moderation/use-cases/uc.moderation.review-a-case.md) and
[uc.moderation.take-a-measure](../features/moderation/use-cases/uc.moderation.take-a-measure.md).

### 7. Statement of reasons, and objection

- **After a measure** the statement (DSA Art. 17) goes to the share's creator and the uploader, and to the scope
  owner on a content block. It names the measure and its reach, the facts and whether a report or an order led to
  it, that no automated means decided, the ground — its category **and the provision of the law or the clause of
  the terms relied on**, required, with the facts explaining why the content falls under it
  (Art. 17(3)(d), (e)) — that the measure applies wherever the instance is reached and lasts until it is lifted
  (Art. 17(3)(a)), and the objection and the courts (Art. 17(3), L). A dismissed report is told only to the reporter.
  The statement and the answer to an objection reach each affected member in the app and by instance mail at their
  account address ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3); a
  reporter who left a contact gets the decision on their report as § 2 sends the receipt. A guest is told nothing in the app for a moderator's measure: it has no account, so a guest uploader counts as
  nobody to tell (DSA Art. 17(2)); an authority's removal order is the exception (§ 12).
  There is one form on every instance (§ 14): the reduced form a *private* mode served until 2026-10-21 is gone, so a
  moderator always states the facts and the provision or clause.
- **The facts are sealed once, and per affected member only their key** (owner 2026-10-19). The moderator's browser
  seals the facts under a fresh 32-byte `K_facts`, bound to the case and to the statement, whose id that browser mints
  (`moderation-statement-facts` [caseId, statementId]; [ADR 0072](0072-a-seal-binds-its-object.md) Decision 3), and
  wraps `K_facts` to each affected member's identity (`moderation-facts-key` [caseId, statementId, accountId]): a
  fixed 1,181 bytes per member whatever the facts' length, where a copy of the facts per member was up to 9,341. The
  wrap binds the statement as well as the member because the member addresses it by both (ADR 0072 Decision 1). The
  log keeps its own copy sealed to the moderation key (`moderation-note` [epoch, caseId]) rather than a wrap of
  `K_facts`: it stays with the case's records for their whole retention (§ 11) and opens as every other note does,
  independent of the members' copy, and one more copy per measure costs nothing beside the wraps. All wraps travel in
  the measure's one request, taken whole or refused: at most **6,000 members** (`StatementRecipients.MAX_SEALED`, what
  the server's 10,240 KiB request limit holds at 1,650 bytes a member beside every other field of the request at its
  largest), past which a step with facts is refused by name, never cut. That wall no longer moves with the facts'
  length; only paging the upload would remove it.
- **The Server Owner's own steps carry a statement too** where they are taken over content — a member's sign-in
  block (Art. 17(1)(d)), a tenant taken offline or deleted (Art. 17(1)(c)) — in the same template and form, entered
  with the step in admin-web or `veyq-admin` (a step from a case's proposal always; a step without a case, when the
  Server Owner says it is over content); a tenant's deletion over content is recorded as a step of its own
  (`TENANT_DELETED`), which stopping the deletion lifts and the tenant's purge ends. Its recipients are the blocked member, and the tenant's owners for a tenant;
  a blocked member cannot sign in and a tenant offline cannot be entered, so it goes **by mail at the account
  address, facts included** — the Server Owner's own words, which the server sees as it sends them, sealed to the
  moderation key for the log — and the objection goes to the instance contact (owner decisions Q24–Q25, 2026-10-18:
  facts by mail rather than a restricted second session kind for one case; a tenant's owners only, since facts mailed to
  every member could expose one member's content to all). While the instance names no contact (§ 14), that objection
  has no route but the courts the statement names: accepted for now (owner 2026-10-23), so a Server Owner's step does
  not offer the in-app objection a moderator's measure has. The mail goes once: the server keeps no clear copy to
  retry.
  **So the step reports what the mail reached** (owner 2026-10-21): its answer's `statementDelivery` says whether the
  mail reached everyone, and names the address of each one it did not reach — the instance mail is not set up, or the
  send failed; a step with nobody to tell (a tenant without an owner, an account erased before the send) says so, and
  one whose recipients could not be read after the commit says it cannot tell. It is an outcome, not counts: the
  client derives nothing, and "cannot tell" has no honest count. admin-web keeps the Server Owner's own words in view
  beside it, so they tell those through the instance contact while the words are still on their screen, and warns
  before the step while no instance mail is set up; `veyq-admin` prints it and warns on stderr, exiting 0. A step over
  content is never refused for want of mail: what must go does not wait on the mail. The report is the answer's alone;
  what is stored stays each recipient's `mailedAt`.
- **The reporter's decision carries their redress** (DSA Art. 16(5)): one fixed bilingual passage — that they may
  report again with more information, the instance contact, that they may go to court, and that, where the DSA reaches
  this instance, they may complain to the Digital Services Coordinator of the Member State where they live or are
  established (DSA Art. 53; in Germany the Bundesnetzagentur, the authority § 12(1) DDG names under DSA Art. 49(1)).
  The qualifier is the passage's own, not a mode's: veyq cannot know whether the DSA reaches an instance (§ 14), and
  a right that only the DSA grants is never promised unconditionally. An empty instance contact renders as the gap.
- **Deferring the statement is an authority's switch, never the moderator's discretion** (L: Art. 17 DSA has no
  exception of its own; TCO Art. 11(3) lets the authority forbid the notice for six weeks, renewable once by six).
  It needs the authority's reference (sealed) and an end date, at most six weeks per request; at that date the
  moderation surface reminds and the statement goes out unless a new request renews it.
- **Objection in the app**, for the affected only, on the placeholder and the statement: free text sealed to the
  moderation key, reopening the case; one per measure, then the courts. A guest uses the instance contact. A
  reporter has no objection to a dismissal; they may report again with more.
- **Another moderator reviews the objection**
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 5): the server
  refuses a decision on it from the account that took the measure while another accepted moderator holds the key,
  and the log records the reviewer. With one moderator the objection does not wait: the decider reviews it, the log
  marks the review as theirs, and the answer to the objector says so. The flow is
  [uc.moderation.object-to-a-measure](../features/moderation/use-cases/uc.moderation.object-to-a-measure.md).

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

`node-name`, `node-meta`, `media-meta`, `media-exif`, `bucket-name`, `share-note`, `share-name`, `share-memory-name`,
`share-identity-name`, `member-name` and `member-avatar` seal under `K_field = HKDF(K_parent, salt,
"veyq/v1/field/<purpose>")`, the 16-byte salt drawn fresh **per write** and carried inside the field's own envelope
(`0x03 ‖ salt ‖ nonce ‖ aead`). A report hands over `K_field`: it opens that one revision of that one field, and the
operator verifies by opening the held ciphertext, whose AAD binds the object or whose key is unique to it
([ADR 0072](0072-a-seal-binds-its-object.md) Decisions 1 and 2; the three share fields bind only their purpose, and
`K_share` is one share's). `media-meta` and `media-exif` are fields although § 1 hands a medium over by its
`K_blob`s: they belong under the Memory's key, so without a key of their own a moderator would see a picture
without its date or place; which keys a medium report carries is the report's own decision. `group-name` and
`space-icon` are not fields: they derive their key per (scope, epoch), so a key opens every revision of that epoch,
and they stay so because a pending invitee is handed `K_name`, only the scope's members read either, and a group's
name is not on § 1's list. A medium's `blob-meta` is covered by its `K_blob`. veyq is not live, so this is a
stored-format change with a reset, not a suite v2. AES-GCM is not key-committing: only a field's own writer could
craft a ciphertext that opens differently under two keys, and § 8 attributes it to them. The model, the envelope and
the tests are [en.platform.field-key](../features/platform/enablers/en.platform.field-key.md).

### 10. The guest-write record

Taken over from [ADR 0092](0092-logs-are-operational-and-name-no-person.md), which keeps the retention period's
range and its no-extension rule (§ 2 there).

- **One core table, `veyq_guest_write_record`**, one row per guest write that enters a guest as last editor (§ 8):
  upload commit, identity creation, folder creation, rename, meta. A row holds the client's address, its source
  port (`client_port`, nullable: unknown where no hop wrote one), the server time it was observed in milliseconds
  UTC, the share identity, the object (kind, id, field group) and `expires_at`. No User-Agent. Behind carrier-grade
  NAT only the address, the port and an exact time together name a subscriber (0094 M8), so an export states the
  time zone, and the Run docs ask the operator to keep the server's clock on NTP: a clock minutes off makes the
  port worthless, because the port soon belongs to someone else.
  Nothing a member writes or anyone reads is recorded.
- **The record never takes an address a client can choose.** A forged address in evidence names an innocent third
  party. Quarkus's forwarding takes the leftmost `X-Forwarded-For` entry, the one a client writes, so where the
  address comes from is decided apart from this ADR (owner 2026-10-16), in
  [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md): the record takes the address of
  veyq's one core resolver, the rightmost `X-Forwarded-For` entry that is not a trusted proxy. When the resolver
  yields no address, nothing is recorded; when it yields no port, the address is recorded alone. On Compose the address is strong by construction; on Helm it is only as
  strong as an enforced NetworkPolicy (0094 decision 6), and the disclosure and the Run docs say so. The
  slice test is 0094's: a guest write carrying `198.51.100.66, 203.0.113.77` records `203.0.113.77` (the
  control), and `198.51.100.66` is in no record and no log. A stop entry `203.0.113.77:51234` records port `51234`,
  and `198.51.100.66:4711, 203.0.113.77` records none, with `4711` in no record and no log. Where the stop entry
  carries no port, it comes from the one source the operator configured (0094 decision 7): on Compose the recipe's
  Traefik plugin, on Helm the PROXY protocol or a header the edge overwrites; behind any other edge the record keeps
  the address alone.
- **Its basis is Art. 6(1)(f) GDPR** (L): a host has no duty to keep addresses and is permitted to keep them
  briefly, for a stated purpose, disclosed — the legitimate interest in tracing the misuse of a guest link once it
  is reported. The Server Owner is the controller; the information duty is theirs, not the tenant owner's.
- **No switch: the record is kept wherever a guest may write** (owner 2026-10-21; it replaced the switch
  `core.public-shares.guest-write-record`, whose default followed the operating mode). A row exists only where a link
  lets a guest write, so `core.public-shares.contribute` and `.drop-box` — at the instance and in each tenant — already
  decide where it is kept, and a Server Owner who wants no guest address kept switches guest writes off. Why no switch
  of its own: what the record is for, a write link that reached strangers, is exactly what an operator-blind Server
  Owner cannot see, so a switch would let them drop the trace where nobody can tell it is needed; and a default per
  mode made the mode a second source of truth for it
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 14, revised). The
  basis below is unchanged, the disclosure is shown on every instance, and N bounds it on every instance.
- **Retention is an instance setting** (0092 § 2: N days, 7 by default, 1 to 30). Seven days is the supervisory
  practice and roughly what an access provider keeps today, past which a host's address resolves to nobody; a value
  above seven needs a reason the Run docs ask the operator to write into their record of processing (L). A row is
  written with
  `expires_at = observed_at + N` as N stands then, and the purge deletes a row when
  `expires_at <= now OR observed_at + N_now <= now`, so a shortening reaches every row at once and a lengthening
  only new ones: no guest is kept longer than the page told them. The purge also runs at startup, so a restored
  backup's stale rows go at boot; it is the reader `EveryStoredTimeHasAReaderTest` asks for. The foreign keys
  delete with the identity or object; a hold has copied what was reported.
- **Backups.** The instance backup's database dump ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md),
  `src/tools/instance_backup.py`) **leaves out the table's rows**, keeping its schema: what is lost is at most 30
  days old and expiring anyway, and what was reported lives in the hold tables, which are backed up. Where an
  operator backs up otherwise (volume snapshots, their own dumps), the Run docs give the deletion concept the
  EDPB's erasure report asks for (L): a backup retention B, at most 30 days recommended for a backup holding the
  table; access to a backup only to restore it; the startup purge after every restore; and **N + B** in the
  disclosure. B is the instance setting every capture veyq writes is pruned by
  ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8, built).
- **Disclosure (GDPR Art. 13, at collection), from the Server Owner, never from a tenant's terms.** Every guest
  write page carries one first-layer sentence with the value current at the write — "your IP address and source
  port and the time are kept for N days, longer only if a report or an authority's order concerns this content" —
  and a link to the full text in the instance's privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8,
  the one home of every Art. 13 passage; the operator section of the terms, decision 6, points there), which names
  the controller and contact, purpose, basis and legitimate interest, the data, N (and N + B where it applies), the
  hold durations of § 4, the recipients (law enforcement on request or suspicion), the rights and the supervisory
  authority, and that a guest who does not want this can view but not add. **The right to object (Art. 21) is
  stated separately** from the rest (Art. 21(4)), on the page as in the text; a guest names the identity it objects
  for by the guest id its own view shows, and an objection erases the rows so far without stopping later writes from
  being recorded (owner 2026-10-18). The first-layer sentence's wording waits on counsel (§ Legal basis, Still open 4
  and 10). The model is
  [en.moderation.guest-write-record](../features/moderation/enablers/en.moderation.guest-write-record.md).

### 11. The moderation log, and the instance contact

- **An append-only table**, one row per case event: received, report attached, **opened** (the server served the
  report share against the moderation key — the decryption itself it cannot see), decided (dismissed, or the step
  and ids), lifted, hold extended or ended, statement sent or deferred, objection received, order received (§ 12),
  information given to an authority, handed to an authority.
  Structured fields in the clear (event, time, moderator account, ids, step); every free text (reasons, notes, an
  authority's reference) sealed to the moderation key. No content, no key. Shown per case in the moderation
  surface. Never in a log: a log names a case id and nothing more (0092 § 1). Not tamper-evident: the operator owns the database, and
  the log is for accountability to the affected and within a team.
- **Instance events**, with no case id
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4): a moderator
  appointed, accepted, delivered the key (epoch, by whom), removed or stepped down, and an epoch minted (by whom, and a reason category: removal, identity rotation,
  compromise, by hand). Kept to 31 December of the third year after the moderator's removal.
- **Its retention** (L): a case's events are kept until **31 December of the third year after the case closes** —
  the civil limitation period from year's end, and the one for the DSA's fines — dismissed cases included, since a
  dismissal is what an injured party may later contest. A case closes when its last hold has ended and no objection
  is open: a dismissal, the lift of its last measure, a decided case whose hold the cleanup or — on a child-sexual-abuse
  case — the handover ended. An undecided case stays open, in the queue, while a moderator still owes its decision:
  **no case closes itself** — that would be a decision no person took (owner 2026-10-18). Instead the moderators are
  reminded (DSA Art. 16(6), "timely"): at 30, 75 and 90 days after the case's first report, while it is undecided, every
  accepted moderator with a confirmed address and the instance contact's confirmed notification address get one mail
  per reminder, with each case's category and age — no id, no link, as the new-case mail (§ 2) — and the queue marks a
  case older than 90 days as **overdue**.
  Longer only while proceedings are pending, as a logged extension (not built for the records; a hold's is).
- **Every moderation record goes with its case's log, in one purge** (owner 2026-10-18; GDPR Art. 5(1)(e)): on the
  case's date the case row, its reports' sealed parts with their reporter fields and its report shares — which wrap
  `K_report`, the key to what was reported; a report spanning several cases goes with the last — its measures and
  proposals, its statements with their recipients, its deferrals and objections, the orders that opened it, and its
  events. **Never while a measure of it is in force**: deleting the row would lift a block nobody lifted (§ 6), and a
  standing measure needs its statement for as long as it can be contested (DSA Art. 17, GDPR Art. 17(3)(e)); its lift
  — or its target's erasure, which ends it the same way (§ 6) — is an event of its own date, which moves the case's. Nor while a proposal of it waits for the Server Owner. An order
  that opened no case goes with its event at 31 December of the third year after its entry; a Server Owner's step on no
  case (§ 7) goes with its statement at 31 December of the third year after its lift. The hold's snapshot and
  pins are content and end by § 4's table, long before.
- The **reporter's contact** goes **six months after the decision is told to them** (DSA Art. 16(5)), or — where it
  could not be told — six months after the case's decision, and never while a case of the report is open; a "receipt
  sent" (`RECEIPT_SENT`) and "decision sent" (`DECISION_SENT`) event stays as the proof. The rest of the report stays
  with its case.
  The purge that computes each case's date at closure is the log's reader for `EveryStoredTimeHasAReaderTest`.
- **An instance contact**, set in admin-web and the CLI: the single point of contact for users and authorities
  (DSA Art. 11–12) and the TCO contact point for removal orders (TCO Art. 15), with the languages it answers in;
  the notification address, confirmed by a code sent to it before it receives anything; and the address the
  disclosures and statements name. It is public, in the operator section of the terms. It is never required and
  nothing warns about it (§ 14): an empty contact renders as "not stated by this operator" wherever a text names it,
  so the gap is visible to whoever reads it. The flow is
  [uc.admin.operator-details](../features/admin/use-cases/uc.admin.operator-details.md).

### 12. An authority's order acts on ids, within the hour, without a key and without a report

- **A removal order names a URL**, which the server cannot read and for which no report share exists. So the order
  path is separate from the report flow, entered by a moderator in the moderation surface with the key proof, or by
  the Server Owner in admin-web **and** in `veyq-admin` without a key — two operations, because the admin channel's
  marker names a door per operation
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6): in goes the
  share URL or share id, the authority's reference and the time of receipt; at once, with no review of its own (the authority has
  reviewed), the server **blocks the share and the content it reaches** by id (§ 6) and opens a case with a **hold
  without a report** whose end the order sets (§ 4). Out comes the text for the feedback form (TCO Annex II, the
  time of the block in UTC to the second) and a draft for the form that reports an obstacle (Annex III).
- **One hour from receipt at the contact point** (TCO Art. 3(3)), with no exemption for a small host (L). veyq
  cannot make an operator read their mail; the Run docs recommend forwarding the instance contact to a phone, and
  say plainly that an operator who cannot keep the hour turns public links off (`core.public-shares`).
- **A preservation order or a request for information** (the e-Evidence Regulation, §§ 100j–100k StPO) opens the
  same hold without a block. Information handed over is an event in the moderation log with the reference sealed.
- **The notice to the uploader** (TCO Art. 11, DSA Art. 17) follows § 7, including its deferral on the authority's
  request. **A guest uploader is told too, on a removal order** (TCO Art. 11(1) asks the host to make the information
  available to the content provider, with no condition of a known contact, unlike DSA Art. 17(2)): an identity whose
  upload a removal order blocked — whose client still holds that identity — is answered, on the link it came through,
  even while that link is blocked: removed on an authority's order of `<date>`; the reasons or a copy of the order on
  request from `<instance contact>` (Art. 11(2)). Nothing else is served to it. A running deferral (Art. 11(3))
  holds this back exactly as it holds the statement. This overrides the earlier owner answer that a guest is told
  nothing, for an authority's removal order only, by the rule that a legal duty wins; for a moderator's measure the
  DSA Art. 17(2) reasoning stands.
- The flow is [uc.moderation.authority-order](../features/moderation/use-cases/uc.moderation.authority-order.md).

### 13. Art. 18 reports, and child sexual abuse material

- **Where an Art. 18 report goes** (L): in Germany to the BKA as the central office (§ 13 DDG), through its portal
  `u-entrance.bka.de` after registering by mail (the Run docs say to register **before** an emergency); without a
  registration, a criminal complaint with the police; in acute danger, 110. Terrorist content already reported
  under TCO Art. 14(5) needs no second report. The moderation surface exports the clear fields the BKA form asks for — case id,
  times in UTC, the uploader's account or share identity, the address from a held guest-write record, the category —
  and **never a content file**. Handing over is an event in the moderation log (§ 11).
- **Child sexual abuse material gets its own flow.** The mail goes out at once (§ 2); the moderation surface shows, before
  the report opens, "do not download, forward or screenshot; review only as far as needed, block the content, inform
  the police or the BKA"; and it **proposes "Block the content" as the first measure**, because once known,
  serving it on is a making-available of the host's own. A human still decides (§ 2). The hold ends at the
  handover, at the latest 90 days after the report (§ 4), so it serves the reporting duty and nothing else (§ 184b(5)
  StGB, L): recording the handover ends it, and the moderation surface says so before it is recorded. A file or a
  screenshot never goes by mail or to a hotline. The flow is
  [uc.moderation.report-to-an-authority](../features/moderation/use-cases/uc.moderation.report-to-an-authority.md).

### 14. No operating mode: every instance carries the full texts, and the Server Owner fills in what applies

Every instance serves **one** form of each fixed text: the full operator section of the terms (ADR 0035 decisions
6–7), the full statement of reasons (§ 7) and the full privacy notice (ADR 0035 decision 8). **Nothing is required
and nothing warns**: every value the Server Owner may set — the instance contact (§ 11), the provider identification
(ADR 0035 decision 7), a privacy contact — is optional, and an empty one renders as "not stated by this operator"
wherever a text names it (owner 2026-10-21). Which duties reach an instance turns on facts veyq cannot see — who the
members are, whether a tenant owner posted a link publicly — so the Server Owner fills in the values once they apply,
as on any other self-hosted service; the gap stays visible to whoever reads the text until they do.

**What the Server Owner is told, as information and not as a choice**: at first-run setup, in admin-web's operator
settings and in the Run docs. The GDPR's household exemption covers only a natural person's purely personal or family
activity, and it ends once content is made reachable for an unrestricted number of people — a link shared publicly
(CJEU C-101/01 *Lindqvist*, C-345/17 *Buivids*); whether the DSA and the TCO Regulation reach an unpaid private
instance is legally uncertain (L, item 1); an operator who shares links publicly or runs veyq for a club or a company
is probably not exempt, and then owes at least a name and a postal address (§ 5 DDG, § 18(1) MStV) and the controller's
contact details (Art. 13(1)(a) GDPR). It covers every tenant on the instance: the Server Owner is the host for all of
them (ADR 0099 § 1), and cannot see whether a tenant owner posted a link publicly, so a friend's tenant stays private
only as long as its links do. *This is your own assessment, not veyq's.*

**Why no mode** (owner 2026-10-17, retired 2026-10-21). A mode *private* or *club, organisation or company*, chosen at
setup, decided four things: a reduced statement and operator section, whether admin-web warned about empty values,
and — as a preset — the guest-write record's switch. Once the record had no switch (§ 10) and warnings had no reader
an operator would not ignore, the mode decided only how long the texts were, and its reduced texts left out what veyq
does on every instance anyway (the report path, the order path, the Art. 18 export are built everywhere and refused
nowhere, "it costs nothing until someone reports"). So the full texts describe every instance more truly than the
reduced ones did, and one form of each is one state to test instead of two. A sentence in them that grants a right
only the DSA gives says *where the DSA reaches this instance* in its own words (§ 7's reporter redress).

## Consequences

- The operator can act on one share, one item or one uploader instead of a whole tenant, and reads only what was
  reported. Unreported content stays unreadable; the T&S target in the security model becomes buildable as written.
- Costs: a new share kind (medium-scoped and field-scoped), hold tables and the sweep's skip, a moderation mode in
  the share viewer, src/web/'s moderation surface with the queue, admin-web's and the CLI's Server-Owner steps, the
  moderator role and key ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)),
  an editor column per field group (built), per-field keys in the crypto suite with new vectors (built), the
  guest-write record, the moderation log. `EveryStoredTimeHasAReaderTest` needs a reader for each new time: the purge
  for records, holds and the log (each hold's and each case's own end).
- Further costs from the operator premise: the order path in the moderation surface, admin-web and the CLI with a
  hold that has no report and an end the order sets (§ 12); per-trigger hold ends and logged extensions (§ 4); the deferral switch (§ 7);
  the report dialog's fields and categories (§ 1); the CSAM flow and the BKA export (§ 13); the backup dump without
  the record's rows, which changes what [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)'s
  database leg holds (§ 10); and in [ADR 0035](0035-server-terms-of-service.md) the fixed operator section and the
  provider identification, each in one form (§ 14).
- **Transparency reports: the operator's duty, not built** (§ Legal basis, 8). An operator may owe three, and veyq
  compiles none: a yearly DSA Art. 15 report unless they are a micro or small enterprise (Recommendation 2003/361/EC;
  whether a non-economic club is an enterprise at all is Still open 8);
  a TCO Art. 7(2) report, whatever their size, for any calendar year they acted against terrorist content or were
  required to, before 1 March; and, under German jurisdiction, the yearly § 3(2) TerrOIBG figures to the
  Bundesnetzagentur by 1 March. The [Run area](../../site/src/content/docs/run/moderation.md#transparency-reports) says
  who owes what; the moderation log and the case list hold what such a report counts, and an export from them could be
  built later. The operator's other duties as controller — a breach, the record of processing with what the guest-write
  record, the reports and the holds keep, processors, an access request — are the Run area's
  [Data protection](../../site/src/content/docs/run/data-protection.md).
  **Not required, not built:** an internal complaint system (Art. 20, online platforms only); the in-app
  objection of § 7 is voluntary.
- A moderator acts from any tenant host they can sign in on; an operator away from admin-web acts over SSH with
  `veyq-admin`, and the Run docs show how to reach admin-web remotely (an SSH tunnel or a VPN).
- **Limits stated in the docs:** a copy escapes a block; held data and the moderation log live in backups beyond
  their retention; an address without a port may name no single subscriber; an operator who cannot keep the TCO
  hour has to turn public links off. A household instance may be outside the DSA, the TCO Regulation and the GDPR
  (L); the Run docs show which instances are (a table by operator kind, and that one link posted publicly can
  leave the household exemption), which values each kind of operator owes (§ 14), and that veyq meets a hosting
  service's duties on every instance once those values are set.
- **Docs** ([moderation](../../site/src/content/docs/run/moderation.md) stops sending the operator to the tenant
  owner and gains an emergency page: 110, the BKA portal registered ahead, `TCO@bka.bund.de`, what never to do;
  the security model's T&S section, `CLAUDE.md`'s invariant on what the server keeps,
  [ADR 0035](0035-server-terms-of-service.md) decisions 3, 6 and 7 and the terms template's clauses 2, 7 and 9)
  change; each ADR's CR carries its own share of what this ADR and [ADR 0092](0092-logs-are-operational-and-name-no-person.md)
  both touch. This ADR's is clause 9, clause 7's guest uploads and clause 2's sentence on what the host keeps about a
  guest write; clause 2's sentence on logs is 0092's.

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
- **Server-Owner operations in src/web/.** They would put them back on the public listener, in the surface that renders
  untrusted content. A moderator's operations are there, because the moderator role carries none of them and its
  gate is the role plus the key proof
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6). **A vault in
  admin-web**, to avoid src/web/: contradicts admin-web's "no vault, no content key, ever".
- **A review mode that presents a password token** to the original share. Superseded by the report share, which has
  no password and does not depend on the original.
- **Handing over `K_share` or a parent key for a text field.** Opens the whole subtree or the siblings.
- **Every editor's history** instead of the last editor. More metadata for what the hold already freezes.
- **Blocking by address.** Addresses change, and the record would become more than evidence.
- **For the guest-write record** (from 0092): hashing the address (an IPv4 hash falls to 2³² guesses) or truncating
  it (a /24 loses the evidence); sealing it to a hold key (the operator sees every address live at their own proxy);
  columns on the identity and upload tables (each write overwrites the last, and the purge would touch two
  applications); a tenant owner's switch (the operator answers for the server); an access log (0092).
- **Deferring the statement at the moderator's discretion**, "in a criminal matter". Art. 17 DSA knows no such
  exception; only an authority can ask for silence (§ 7).
- **One 90-day end for every hold.** Too long once a case is dismissed, too short after a measure (objection,
  proceedings) and against a TCO order's six months.
- **Keeping the guest-write record as long as the access providers may soon have to** (three months in the German
  bill). That bill does not reach a host, and the 1-to-30-day cap is what bounds the legitimate interest.
- **An operating mode, *private* or *club, organisation or company*** (built 2026-10-17, retired 2026-10-21; § 14
  says why): it ended as a second state for every fixed text that only shortened them, and a preset that let a
  private instance drop the guest-write record where it mattered most.
- **An operating mode per tenant.** The duties are the host's, and the host is the Server Owner for every tenant
  (ADR 0099 § 1); a tenant cannot be *private* on an instance whose operator hosts a club beside it.
- **Switching the report path off for a household** (§ 14). Criminal law and the hosting privilege's duty to act once
  a host knows reach a family's operator as much as a club's; a report path is how they come to know.
- **veyq deciding which duties apply** (by the number of accounts or tenants, or by whether public links are on).
  Which law applies turns on facts veyq cannot see — who the members are, whether a link was posted publicly — so the
  assessment stays the operator's, with § 14's information telling them what it turns on.
- **Crypto-shredding the record** (a daily key outside the database) to keep it out of backups. Solves volume
  snapshots too, but costs key management outside the database for what leaving the rows out of the dump does.

## Legal basis

Research, not legal advice, 2026-10-16. Each answer needs a qualified review before anything ships.

1. **Which law reaches an instance.** The DSA covers a service "normally provided for remuneration"; for a
   non-economic household instance that is contested, for a club with fees or a paid host it is likely. veyq is a
   hosting service (Art. 3(g)(iii), recital 29), not an online platform: recital 14 excludes information shared with
   a finite number of persons determined by the sender, and membership needs a human invitation. Art. 16–18 bind
   every hosting service with no exemption for small ones; Art. 15 and Art. 20–28 do not reach a micro or small
   host. The GDPR's household exemption (Art. 2(2)(c)) covers only a natural person's purely personal activity:
   never a club or a company, never someone who provides the means for others (recital 18), and not where content
   reaches an unrestricted number of people. § 7 DDG grants the hosting privilege to unpaid services too. § 5 DDG
   asks for an imprint from a commercial, usually paid service; § 18(1) MStV from every service not exclusively
   personal or familial. Publishing on the internet for an indefinite number of people is outside the household
   exemption (CJEU C-101/01 *Lindqvist*, C-345/17 *Buivids*), so one link posted publicly can end it. Whether the
   DSA reaches an unpaid private service is uncertain; the Bundestag's research service calls it so in
   [*WD 7 - 3000 - 026/26*, "Haftung von Serverbetreibern und Bestimmung der Nutzerzahlen dezentraler Netzwerke"](https://www.bundestag.de/resource/blob/1182736/WD-7-026-26.pdf)
   (2026; the document's existence and title measured 2026-10-17, its conclusion taken from the research report, not re-read). Hence § 14 leaves
   the assessment to the operator.
   [DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
   [CJEU C-25/17](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:62017CJ0025) ·
   [CJEU C-101/01](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62001CJ0101) ·
   [CJEU C-345/17](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62017CJ0345) ·
   [§ 7 DDG](https://www.gesetze-im-internet.de/ddg/__7.html)
2. **The terms.** Art. 14 DSA asks for the moderation policy, its procedures and human review in plain language;
   TCO Art. 7(1) for the policy on terrorist content; GDPR Art. 13 for the record's information at collection, and
   Art. 21(4) for the right to object stated separately. All fall on the provider, the Server Owner — hence the
   operator section of [ADR 0035](0035-server-terms-of-service.md) decision 6.
   [DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
   [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj) ·
   [TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784)
3. **Hold and log durations.** Neither the DSA nor the GDPR sets one for a host; storage limitation (GDPR
   Art. 5(1)(e)) and provable purposes do. The anchors are six months (TCO Art. 6, a duty; DSA Art. 20(1), an
   analogy), 60 + 30 days (Regulation 2023/1543 Art. 11, applicable since 18 August 2026) and three years from
   year's end (§§ 195, 199 BGB; § 31(2) no. 1 OWiG for the fines of § 33 DDG). Hence § 4 and § 11.
   [TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
   [Regulation 2023/1543](https://eur-lex.europa.eu/eli/reg/2023/1543/oj) ·
   [§ 199 BGB](https://www.gesetze-im-internet.de/bgb/__199.html)
4. **The guest-write record.** An address is personal data to a host (CJEU C-582/14, *Breyer*), and keeping it for
   the functioning of a service can rest on Art. 6(1)(f). No law puts a retention duty on a host. Seven days is the
   supervisory practice (the BGH allowed an access provider seven, III ZR 391/13); up to 30 is defensible with a
   reason. Backups are not exempt from erasure: deletions have to be tracked and applied on restore (EDPB CEF 2025
   report, § 4.2.6). Hence § 10.
   [CJEU C-582/14](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62014CJ0582) ·
   [EDPB CEF 2025](https://www.edpb.europa.eu/system/files/2026-02/edpb_cef-report_2025_right-to-erasure_en.pdf)
5. **Art. 18 and child sexual abuse material.** The BKA receives Art. 18 reports as the central office (§ 13 DDG),
   through its portal. § 184b(5) StGB exempts possession and making available only where they serve exclusively
   the lawful performance of state tasks, of agreements with a state body, or of official or professional duties;
   for a host bound by Art. 18, a hold that ends at the handover serves that duty. Hence § 13.
   [§ 13 DDG](https://www.gesetze-im-internet.de/ddg/__13.html) ·
   [§ 184b StGB](https://www.gesetze-im-internet.de/stgb/__184b.html) ·
   [BKA, Art. 18 reports](https://www.bka.de/DE/DasBKA/OrganisationAufbau/Fachabteilungen/ZentralerInformationsUndFahndungsdienst/Digitale_Eingangsstelle/Guidelines/Verdachtsmeldungen_node.html)
6. **Terrorist content.** One hour from receipt of a removal order (TCO Art. 3(3)); twelve hours' notice before an
   authority's first order to a host, save in an emergency (Art. 3(2)); no exemption for a small host — size counts
   only in the penalty (Art. 18(2)), and Art. 3(7) excuses only objectively justifiable technical or operational
   reasons. The host names a contact point (Art. 15), keeps what it blocked for six months (Art. 6) and tells the
   uploader unless the authority forbids it for 6 + 6 weeks (Art. 11). In Germany the BKA issues orders and the
   Bundesnetzagentur supervises (§ 1 TerrOIBG). The Regulation reaches only dissemination to the public; a `READ`
   link is a borderline case and treated as reached. Hence § 12.
   [TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
   [§ 1 TerrOIBG](https://www.gesetze-im-internet.de/terroibg/__1.html)
7. **IP retention in Germany.** No duty today: the old §§ 175 ff. TKG are inapplicable (BVerwG 6 C 6.22,
   14 August 2023). The bill BT-Drs. 21/6581 (three months) obliges only providers of internet access (§ 177
   TKG-E) and forbids storing data on the use of digital services on its basis; a host is reached only by requests
   for data it already has (§§ 100j–100k StPO-E, the e-Evidence Regulation).
   [BT-Drs. 21/6581](https://dserver.bundestag.de/btd/21/065/2106581.pdf) ·
   [BVerwG press release 66/2023](https://www.bverwg.de/de/pm/2023/66)
8. **Transparency reports** (research of 2026-10-18). DSA Art. 15(1) asks every provider of an intermediary service
   for a public, machine-readable report at least once a year — orders from authorities (Art. 9, 10), notices
   (Art. 16) and the action taken, moderation on its own initiative, automated means — and Art. 15(2) exempts only a
   micro or small enterprise in the sense of Recommendation 2003/361/EC (fewer than 50 staff, and turnover or balance
   sheet of at most EUR 10 million, linked and partner enterprises counted in) that is not a very large platform. The
   templates and the period are Implementing Regulation (EU) 2024/2835: the calendar year, published within two months
   of its end. A company or a larger association running an instance for its people is therefore reached, where the
   DSA reaches the instance at all (point 1). TCO Art. 7(2) asks a host that took action, or was required to, under the
   Regulation in a calendar year for a public report on it before 1 March of the next, with Art. 7(3)'s contents; size
   is no exemption. § 3(2) TerrOIBG has every host under German jurisdiction send the Bundesnetzagentur, per calendar
   year and by 1 March, the figures of TCO Art. 21(1)(c): how many access requests competent authorities made for
   content it preserved under Art. 6. Whether a year with nothing to count still needs a report is not settled by the
   text. Hence the Run area's table, and no export.
   [DSA](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) ·
   [Implementing Regulation 2024/2835](https://eur-lex.europa.eu/eli/reg_impl/2024/2835/oj) ·
   [Recommendation 2003/361/EC](https://eur-lex.europa.eu/eli/reco/2003/361/oj) ·
   [TCO](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) ·
   [§ 3 TerrOIBG](https://www.gesetze-im-internet.de/terroibg/__3.html)

### Still open

**How these items are treated until veyq is offered as a service** (owner 2026-10-18): while the owner does not
operate veyq as SaaS — instances run by their operators for a household, a club or a company — the items below are
taken to be met by what is built and decided, so none of them holds back a CR or a release. Nothing they name is built
on that assumption (no hold past 90 days, no authority link), and every legal text stays a draft. **Before veyq is
offered as a service, each item is answered by counsel**, and an answer that differs is a legal duty that wins over
what is built (`CLAUDE.md`).

1. **§ 184b(5) StGB beyond the 90 days, and for a non-commercial operator.** Built is the bound the law supports
   for a host bound by Art. 18: the hold ends at the handover, at the latest 90 days after the report (§ 4). Whether a
   hold may run *until* the handover past those 90 days is not decided and not built; and a household operator has no
   "professional duty" to point to, so whether even the bounded hold is covered for them is unsettled. Until it is, the
   Run docs advise contacting the police at once and keeping nothing beyond the hold.
2. **A time-bound authority link to a hold snapshot** (a report share served against a one-time token and logged),
   so the police can secure the evidence themselves without the operator making a file. It is a making-available
   under § 184b(1) no. 2; whether (5) covers it needs the police or a specialist before it is built. Not decided.
3. **Whether the German IP-retention bill has passed.** The hearing was on 7 October 2026; no vote was found. It
   reaches access providers only; if it passes, a value of N above seven gains weight, since a guest's address
   would then resolve for three months. Re-check before the CR is written.
4. **What the recorded source port is worth.** Behind carrier-grade NAT an IPv4 address without its source port
   often names no single subscriber, and the BKA's form asks for the port. Where it comes from is decided, not open:
   [ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md) decisions 3 and 7 — the stop entry of
   `X-Forwarded-For`, else the one configured source (on Compose the recipe's Traefik plugin, on Helm the PROXY
   protocol or a header the edge overwrites). What stays open is legal, not technical: the port is the one the
   operator's edge saw, so behind a further NAT of the operator's own it is not the subscriber's, and whether the
   disclosure must say which edge an instance runs before the record is handed over needs counsel before the
   moderation CR.
5. **§ 184b(5) StGB for a club's volunteer moderators.** A moderator a club appoints (ADR 0099 § 1) acts unpaid; whether
   reviewing and holding a child-sexual-abuse case until the handover is covered by "official or professional duties"
   (*dienstliche oder berufliche Pflichten*) for a volunteer is unsettled, as Still open 1 is for a household operator
   (audit 2026-10-18, C3). Until counsel answers, the Run docs give a volunteer the advice item 1 gives a household
   operator.
6. **Who is the controller of a member's account data** on an instance with several tenants: the Server Owner alone,
   or the Server Owner jointly with each tenant owner (GDPR Art. 26). It decides who owes the Art. 13 information of
   the instance privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8) and who answers an erasure
   request ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)); veyq builds
   both from the Server Owner, and the notice says so, until counsel answers (audit 2026-10-18, C6).
7. **The breached-password check on by default.** `core.breached-password` sends a 5-hex prefix from the browser
   without consent, and with it the browser's address and an `Origin` naming the instance
   ([security model](../crosscutting/security-model.md) § third-party services). Whether that rests on Art. 6(1)(f),
   whether a transfer to a third country is involved (the API and its CDN), and whether on-by-default meets Art. 25(2)
   is open (audit 2026-10-18, C7). Until it is answered the privacy notice names the check, its recipient and its
   switch, and the default stays as decided (owner 2026-10-14). The same three questions hold for
   `expenses.exchange-rates` ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 8):
   a date, with the browser's address and the `Origin`, to Frankfurter's public API, which runs behind Cloudflare's CDN
   (measured 2026-10-25); the notice names that fetch, its recipient and its switch the same way, and its default
   stays as decided (owner 2026-10-23).
8. **Whether a non-economic club is an "enterprise"** under Recommendation 2003/361 for the DSA Art. 15(2)
   exemption from the transparency report (audit 2026-10-18, C8). A micro or small *enterprise* is exempt; a club
   that is no enterprise is either outside the DSA (item 1) or not covered by the exemption's wording. The Run docs
   name the question instead of the answer.
9. **Whether TCO Art. 3(7) excuses a private operator who cannot be reached** within the hour (audit 2026-10-18, C9;
   the audit's own reading: rather not). Art. 3(7) names "objectively justifiable technical or operational reasons";
   whether an unattended household instance is one is open. § 12's advice stands: an operator who cannot keep the
   hour turns public links off.
10. **The layered Art. 13 information on a guest page, and Art. 14 for people a report names.** Whether a first-layer
    sentence plus a link meets Art. 13 on a page a guest reaches without an account (§ 10), and what Art. 14 GDPR
    (and § 33 BDSG's exceptions) asks of the host towards a third party a reporter names in a report's sealed text,
    which the host cannot read until a moderator opens it (audit 2026-10-18, C5).
11. **Reported content inside a backup capture.** A capture holds the ciphertext of everything stored when it was
    taken, including content later reported, blocked or confirmed as child sexual abuse material. Whether keeping such
    a capture until it expires at B is possession under § 184b(3) StGB, or covered as it is unreadable to the operator
    and kept only as a backup, is open (owner question 2026-10-18). A restore no longer undoes a block, loses a case or
    revives a hold that had ended ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8); this
    item is only about the capture's own existence.
12. **Whether erasing a switched-off application's content needs a basis in the terms.** A tenant owner or the
    operator switches an application off, and after a window its members' content in the tenant is erased
    ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8,
    [uc.platform.erase-switched-off-application](../features/platform/use-cases/uc.platform.erase-switched-off-application.md);
    owner 2026-10-25, inbox #539). Whether erasing content other members made, on someone else's decision and after a
    notice, needs a basis in the terms is open. If it does, it is also open whether that basis belongs in the operator
    section (veyq's fixed text, [ADR 0035](0035-server-terms-of-service.md) decision 6) or in the space's own terms.
    It is tied to item 6, who the controller is. Until counsel answers, the operator section stays as it is, and the
    privacy notice names how long content is kept (GDPR Art. 13(2)(a)).
13. **Whether the notice of that erasure is adequate.** Members are told the day by a banner and, where the instance
    has mail, by a notice and a reminder, each tried once, with no delivery guarantee. An instance without mail tells
    them by the banner alone. Whether that suffices as notice before content is erased is open.
14. **Whether switching an application off is a moderation decision.** The reading taken is no: a switch-off does not
    act on content for being illegal or against the terms, so it is no content moderation in the sense of DSA
    Art. 3(t), and it owes no statement of reasons under Art. 17 (§ 7). The reading was not checked against a source or
    by counsel. If it is wrong, a switch-off that leads to an erasure owes each affected member a statement.
