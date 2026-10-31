# ADR 0093 — A report opens one window for the moderator, and a hold copies, never blocks

- Status: Accepted
- Built: partly (not built: § 1 a field on its own other than a node's name, and the reporter's handover of a guest's
  identity key; § 11 the records' extension while proceedings are pending)
- Date: 2026-10-16
- Model: [Moderation](../features/moderation/_index.md)

## Context

The security model's target is that "Trust & Safety operates at the share boundary": a report carries the share
link, so the operator can decrypt a reported public share, verify, quarantine, preserve evidence and act
([security-model](../crosscutting/security-model.md) § Sharing & share-boundary Trust & Safety). Without this ADR
there is neither half: no report intake, no operator action on a single share, and the one lever is suspending a
whole tenant ([ADR 0066](0066-tenant-suspension.md)).

**Who runs an instance.** veyq assumes an instance is hosted not only for a family but also by a club or a company. Such
an operator is a hosting service under the DSA (not an online platform), a controller under the GDPR, a hosting service
provider under the terrorist-content Regulation (EU) 2021/784 ("TCO") and a provider who identifies itself under § 5 DDG
or § 18 MStV ([legal register](../crosscutting/legal-register.md) L1). A household instance is the subset where some of
these duties may not apply. **The design never relies on that**: every mechanism below is built for every instance, and
it costs nothing until someone reports. What a household operator may leave out is **their own call**, and it is made by
leaving values empty, not by a mode (§ 14): every instance serves the full texts, and an unset value shows as not stated
— never by leaving off the guest-write record (§ 10, kept wherever a guest may write), and never the report, the hold,
the block or the moderators, since criminal law and the hosting privilege's "once known, act" reach every operator (§ 7
DDG covers unpaid services too). The choice says plainly that it is the operator's assessment and not veyq's. The
hosting-not-platform reading holds while a share has no directory, no search and its key in the fragment; a feature that
changes any of these (a public gallery, a listing of public shares, a link without a fragment key) needs its own ADR.

Five facts shape the design:

- **Duties fall on each Server Owner as a host, not on veyq's authors**: act expeditiously once a precise notice
  gives knowledge (DSA Art. 6), offer an electronic notice path (Art. 16), give the affected a statement of reasons
  (Art. 17), report suspected offences against life or safety, child sexual abuse included (Art. 18, recital 56),
  block within an hour on a terrorist-content removal order and keep what was blocked for six months (TCO Art. 3,
  6), and never copy child sexual abuse material to "collect evidence" (§ 184b StGB). veyq's part is to make an
  operator able to do this without reading anything nobody reported.
- **Attribution is partial.** The server records who uploaded a medium (a member or a share identity, and its
  bucket's owner, [ADR 0034](0034-media-uploader-buckets.md)), a Depot file's current content
  ([ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 8), a guest's identity
  folder and a share's creator. It records nobody for a node's name or meta, a share's note or name, a bucket's name,
  or a space's name and icon.
- **A seal opens with its object's key, and that key opens more.** Before § 9 a field was sealed with AES-GCM
  directly under its object's key ([`seal.ts`](../../src/packages/crypto/src/seal.ts)): a node's name with its
  parent's key, a node's meta and a bucket's name with `K_node`, a share's note and names with `K_share`. Only a
  group's name and a space's icon used keys derived for that field alone (`group-name-key`, `K_icon`), and those per
  epoch. A media is not a node: its `K_blob`s are
  wrapped under its direct parent's `K_node` ([en.memory.media](../features/memory/enablers/en.memory.media.md),
  M); a chapter has a `K_node` of its own ([uc.memory.chapters](../features/memory/use-cases/uc.memory.chapters.md),
  M); a guest's upload opens through the drop key → identity key → `K_blob`
  ([ADR 0031](0031-uploader-public-share.md) point 7).
- **Data is destroyed on many paths, some at once**: a share delete or re-issue nulls every sealed column and
  key wrap; every edit of a name, meta, note, password, bucket name or space name and icon overwrites in place; a
  Depot replace or cover replace drops the old blob row; a guest's delete of an own upload and a member's
  expedited purge skip the 30-day trash; a guest's abandoned empty placeholder goes at once. Every store object goes
  through one choke point, the reclaim sweep, about an hour after its row
  ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)).
- **No log holds evidence.** No table stores an address; logs are operational and name no person
  ([ADR 0092](0092-logs-are-operational-and-name-no-person.md) § 1). A guest has no account and no address, so
  without a record an upload through a public drop link names nobody.

## Decision

The mechanism lives in **core** ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)): node,
share, report and hold are platform terms, and Depot needs them as much as Memories. Who moderates — moderators the
Server Owner appoints, sharing one instance moderation key — is
[ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)'s; §§ 1–3, 6–7 and 11–13
state what it means here.

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
- **The report dialog** asks for what DSA Art. 16(2) names: a reason (required), a confirmation that the
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
  — never a key, a host or a link; the first three categories of § 1 at once, the rest bundled. A
  reporter who left a contact gets a receipt and, later, the decision (DSA Art. 16(4)–(5)). Both go by instance
  mail ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3), and the server
  never stores the address in the clear: the reporter's client sends it once beside the sealed copy and the server
  mails the receipt from memory, bounded per address; for the decision, the deciding moderator's client opens the
  sealed copy and sends the address once the same way (§ 7).
- **Nothing is blocked by a report.** A human decides.

### 3. Review is a share view in moderation mode; deciding is the moderation surface's

- **src/web/'s moderation surface**, a route on every tenant host reachable only with the `MODERATOR` role and the key
  proof ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6), shows the
  instance-wide queue (clear fields only) and opens `https://<tenant domain>/s/<case id>#moderation` on the reported
  tenant's host. The fragment names the mode and carries no key; the case's reports and report shares come sealed
  through the key-proved review reads.
- **src/web/'s share viewer in moderation mode** holds the moderation key and nothing of the vault ([ADR
  0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6). It opens the report,
  proves the moderation key to fetch the report share, and shows it **with the guest viewer** plus the report's details,
  read-only. It needs no membership in the reported tenant; this is the only flow where an account acts on a tenant host
  it does not belong to.
- **Every decision is a moderator operation** in that surface — dismissing, the steps of § 6 within a moderator's
  reach, the statement of reasons and its deferral, objection decisions, hold extensions, the order path (§ 12), the
  CSAM flow and the BKA export (§ 13) — gated by the `MODERATOR` role and the key proof on the public listener.
- **admin-web keeps the Server Owner's part** on the admin channel ([ADR 0065](0065-admin-ui-cli-separate-service.md)),
  with the same reach in `veyq-admin` so an operator away from admin-web can act over SSH: the moderator roster, minting
  and rotating the key, the instance contact (§ 11), the steps of § 6 that stay theirs, and the order path (§ 12).

### 4. A hold is a snapshot plus a pin

- **On a report, or on an authority's order without one (§ 12),** the server copies the affected rows — ciphertext
  and metadata only, attribution (§ 8) and guest-write records (§ 10) included — into hold tables and **pins** their
  blobs' store objects. A hold is the restriction of processing the GDPR knows (Art. 18), not a further use:
  invisible, readable only with the moderation key, never back to the reporter.
- **It blocks nothing.** The one hook into existing code is the reclaim sweep skipping a pinned object, and a skip is
  not a failed attempt. Every delete, overwrite, revoke and trash keeps running, so the hold is **invisible by
  construction**, the tenant's quota falls as the user expects, and the operator pays for held bytes. The report share
  reads from the snapshot, which makes review independent of anything done after the report.
- **It ends by its trigger**, each end its own, since keeping is bounded by its purpose (§ Legal basis, 3):

  | Hold | Ends |
  |---|---|
  | Case open | at the decision, at the latest **90 days** after the report |
  | Case dismissed | **at once** |
  | Measure taken | **six months** after the statement of reasons is sent (§ 7; a deferred one counts from its sending, and where nobody can be told, from the decision); a lifted measure ends it with the lift |
  | Child sexual abuse material | at the recorded handover to an authority (reported to one, or information given to one), at the latest **90 days** after the report that took the hold or made the case one of this category; a measure or a statement never moves it further, an authority's order keeps what it holds (§ 13) |
  | Terrorist-content removal order | **six months** after the block, never shorter; longer on the authority's request while a review runs (TCO Art. 6) |
  | European Preservation Order | **60 days**, 30 more when the authority extends it, and while a confirmed production order needs it (Regulation 2023/1543 Art. 11) |
  | Any other authority order | the duration it names; one that names none, six months |

- **An extension** needs a concrete reason — an authority's request or order, an objection, a lawyer's letter, a
  lawsuit, an Art. 18 report in progress — logged as its own event (§ 11), at most **six months** a step, lapsing
  without a new reason; its type is in the clear and its details sealed. On a child-sexual-abuse case only an Art. 18
  report in progress or an authority's request extends it, and the server refuses any other reason: keeping that
  content is exempt only while it serves the report (§ 184b(5) StGB). The handover still ends it.
- **An authority's order holds to the end it sets**: no extension, lift or decision shortens it, from either door
  (§ 12).
- **At the end** the snapshot and the pins go, and the sweep catches up. The tables, the snapshot, the pin, the ends as
  built and the hourly cleanup are [en.moderation.hold](../features/moderation/enablers/en.moderation.hold.md).

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
authority's order (§ 12) takes the same steps. **A step ends with its target** (GDPR Art. 5(1)(e)): when what it stands
on is erased — a link deleted, a node or an item purged, a guest identity dropped, a space, an account or a tenant
purged — the eraser ends it in its own transaction, lifted by no one and logged so; standing on nothing it would block
nothing and still hold its case's records forever (§ 11). A step taken on a target already erased is **taken and ends at
once, not refused**: the decision and its statement stay, and the hold stays as the evidence it is. The hold is not
ended by it: it keeps its own end (§ 4). Wherever a lifted step is shown — the case, the affected's statement, the Art.
18 export — one that ended with its target says so ("ended when the content was erased"), never plain "lifted": nobody
lifted it. **An address is evidence, never the ground of a block.** A copy a member made (Depot's "Copy to…") is a new
blob under a new key; the server cannot know it, and a block reaches only the reported copy. Where blob rows share one
stored object inside a node (a cover, a banner, a gallery original, [ADR
0013](0013-blob-storage-presigned-direct-to-store.md) § 5), **a block hangs on the stored object**: a block on one
reaches every row on the object — the same bytes under another id, refused alike — and erasing the blocked item does not
end it while another row still holds the object. It then stands on that object alone and ends when the object's last row
goes or a moderator lifts it. So deleting a blocked photo never serves it again as the Memory's cover or banner. The
same holds for a step taken **after** the item was erased: it does not end at once where another row still holds the
item's object — it hangs on that object, read from its case's hold since the item's own rows are gone, so deleting a
reported photo before the decision does not leave its bytes served as the banner either. Where no hold holds the item,
nothing names its objects, and the step ends at once. The review and the steps are
[uc.moderation.review-a-case](../features/moderation/use-cases/uc.moderation.review-a-case.md) and
[uc.moderation.take-a-measure](../features/moderation/use-cases/uc.moderation.take-a-measure.md).

### 7. Statement of reasons, and objection

- **After a measure** the statement (DSA Art. 17) goes to the share's creator and the uploader, and to the scope
  owner on a content block. It names the measure and its reach, the facts and whether a report or an order led to
  it, that no automated means decided, the ground — its category **and the provision of the law or the clause of
  the terms relied on**, required, with the facts explaining why the content falls under it
  (Art. 17(3)(d), (e)) — that the measure applies wherever the instance is reached and lasts until it is lifted
  (Art. 17(3)(a)), and the objection and the courts (Art. 17(3)). A dismissed report is told only to the reporter.
  Each member reads the measure in the words of their own relation to it, which their recipient row records: a blocked
  link's creator "a link you created", a member whose upload the link reaches "a link to something you uploaded" (a
  member uploads as a member, never through a link), and a member with neither relation neutral words (inbox #821).
  The statement and the answer to an objection reach each affected member in the app and by instance mail at their
  account address ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decision 3); a
  reporter who left a contact gets the decision on their report as § 2 sends the receipt. A guest uploader whose upload a measure restricts is told on the link it came through, as § 12 tells it of a removal
  order: DSA Art. 17(2) asks this only where electronic contact details are known, which a link is not, but the route
  exists and the safe side costs nothing. A guest has no account, so nothing reaches it by mail. **What it reads is
  the statement's clear fields** — the measure and its reach, that it is told as the uploader, the ground and its
  provision, the date — on the link it came through, by the contributor token its client holds, even while that link
  is blocked, only while the measure stands, and nothing while a deferral holds the statement back: the read § 12 opens,
  which answers both. **Not the facts**: they are sealed to the members' identity keys (below), and a guest identity
  has none, so its statement says it may ask the instance contact for them; it objects there too. The template's
  guest words name it as the uploader where the member's would name the link's creator.
  There is one form on every instance (§ 14), so a moderator always states the facts and the provision or clause.
- **The facts are sealed once, and per affected member only their key**. The moderator's browser seals the facts under a
  fresh 32-byte `K_facts`, bound to the case and to the statement, whose id that browser mints
  (`moderation-statement-facts` [caseId, statementId]; [ADR 0072](0072-a-seal-binds-its-object.md) Decision 3), and
  wraps `K_facts` to each affected member's identity (`moderation-facts-key` [caseId, statementId, accountId]): a fixed
  size per member whatever the facts' length, where a copy of the facts per member would grow with them. That identity
  is the one the server serves, unpinned, since the moderation surface holds no pin store: the one exception [ADR
  0028](0028-identity-authenticity-tofu-verification.md) names to its check. The wrap binds the statement as well as the
  member because the member addresses it by both (ADR 0072 Decision 1). The log keeps its own copy sealed to the
  moderation key (`moderation-note` [epoch, caseId]) rather than a wrap of `K_facts`: it stays with the case's records
  for their whole retention (§ 11) and opens as every other note does, independent of the members' copy, and one more
  copy per measure costs nothing beside the wraps. All wraps travel in the measure's one request, taken whole or
  refused: at most **6,000 members**, what the server's request limit holds beside every other field of the request at
  its largest, past which a step with facts is refused by name, never cut. Only paging the upload would remove that
  bound.
- **The Server Owner's own steps carry a statement too** where they are taken over content — a member's sign-in
  block (Art. 17(1)(d)), a tenant taken offline or deleted (Art. 17(1)(c)) — in the same template and form, entered
  with the step in admin-web or `veyq-admin` (a step from a case's proposal always; a step without a case, when the
  Server Owner says it is over content); a tenant's deletion over content is recorded as a step of its own,
  which stopping the deletion lifts and the tenant's purge ends. Its recipients are the blocked member, and the
  tenant's owners for a tenant; a blocked member cannot sign in and a tenant offline cannot be entered, so it goes **by mail at the account
  address, facts included** — the Server Owner's own words, which the server sees as it sends them, sealed to the
  moderation key for the log — and the objection goes to the instance contact (facts
  by mail rather than a restricted second session kind for one case; a tenant's owners only, since facts mailed to
  every member could expose one member's content to all). While the instance names no contact (§ 14), that objection
  has no route but the courts the statement names: accepted, so a Server Owner's step does
  not offer the in-app objection a moderator's measure has. The mail goes once: the server keeps no clear copy to
  retry.
  **So the step reports what the mail reached**: its answer says whether the
  mail reached everyone, and names the address of each one it did not reach — the instance mail is not set up, or the
  send failed; a step with nobody to tell (a tenant without an owner, an account erased before the send) says so, and
  one whose recipients could not be read after the commit says it cannot tell. It is an outcome, not counts: the
  client derives nothing, and "cannot tell" has no honest count. admin-web keeps the Server Owner's own words in view
  beside it, so they tell those through the instance contact while the words are still on their screen, and warns
  before the step while no instance mail is set up; `veyq-admin` prints it and warns on stderr, exiting 0. A step over
  content is never refused for want of mail: what must go does not wait on the mail. The report is the answer's alone;
  what is stored stays each recipient's mail time.
- **The reporter's decision carries their redress** (DSA Art. 16(5)): one fixed bilingual passage — that they may
  report again with more information, the instance contact, that they may go to court, and that, where the DSA reaches
  this instance, they may complain to the Digital Services Coordinator of the Member State where they live or are
  established (DSA Art. 53; in Germany the Bundesnetzagentur, the authority § 12(1) DDG names under DSA Art. 49(1)).
  The qualifier is the passage's own, not a mode's: veyq cannot know whether the DSA reaches an instance (§ 14), and
  a right that only the DSA grants is never promised unconditionally. An empty instance contact renders as the gap.
- **Deferring the statement is an authority's switch, never the moderator's discretion** (for a moderator's own
  measure Art. 17 DSA has none; an authority's order is outside Art. 17 altogether, Art. 17(5), and TCO Art. 11(3)
  lets the authority forbid the notice for six weeks, renewable once by six).
  It needs the authority's reference (sealed) and an end date, at most six weeks per request; at that date the
  moderation surface reminds and the statement goes out unless a new request renews it.
- **Objection in the app**, for the affected only, on the placeholder and the statement: free text sealed to the
  moderation key, reopening the case; one per measure, then the courts. A guest uses the instance contact. A
  reporter has no objection to a dismissal; they may report again with more.
- **Another moderator reviews the objection**, and with one moderator the decider does, saying so
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 5). The flow is
  [uc.moderation.object-to-a-measure](../features/moderation/use-cases/uc.moderation.object-to-a-measure.md).

### 8. Every sealed field records its last editor

The last editor of each field group — name, meta, note, icon — is stored as an opaque `user:{id}` or
`share:{identityId}`, the class [ADR 0034](0034-media-uploader-buckets.md) and
[ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) already disclose, for the same
reason (a sealed attribution could name anyone). The server knows it; whether members see it is a product question
outside this ADR. A hold freezes the reported revision with its editor. This also tightens
[ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6: a guest may abandon
an empty placeholder only if its name was last written by that guest's identity, and never one a live link is rooted
on. The editor is the authenticated caller of the write, never a request field, and every write of the ciphertext counts — a re-seal by a move or a restore as much as a new text, since the server cannot tell them apart. Clearing a field clears its editor. A field only its own row's subject writes (a share identity's name, a member's profile) needs no column. The model, the columns and the tests that pin each writer are
[en.platform.field-editor](../features/platform/enablers/en.platform.field-editor.md).

### 9. A text or icon field seals under a key of its own

`node-name`, `node-meta`, `media-meta`, `media-exif`, `bucket-name`, `share-note`, `share-name`, `share-memory-name`,
`share-identity-name`, `member-name` and `member-avatar` seal under a `K_field` derived from their parent's key with a
salt drawn fresh **per write** and carried inside the field's own envelope. A report hands over `K_field`: it opens that
one revision of that one field, and the operator verifies by opening the held ciphertext, whose AAD binds the object or
whose key is unique to it ([ADR 0072](0072-a-seal-binds-its-object.md) Decisions 1 and 2; the three share fields bind
only their purpose, and `K_share` is one share's). `media-meta` and `media-exif` are fields although § 1 hands a medium
over by its `K_blob`s: they belong under the Memory's key, so without a key of their own a moderator would see a picture
without its date or place; which keys a medium report carries is the report's own decision. `group-name` and
`space-icon` are not fields: they derive their key per (scope, epoch), so a key opens every revision of that epoch, and
they stay so because a pending invitee is handed `K_name`, only the scope's members read either, and a group's name is
not on § 1's list. A medium's `blob-meta` is covered by its `K_blob`. AES-GCM is not key-committing: only a field's own
writer could craft a ciphertext that opens differently under two keys, and § 8 attributes it to them. The model, the
envelope and the tests are [en.platform.field-key](../features/platform/enablers/en.platform.field-key.md).

### 10. The guest-write record

The retention setting's form and its no-extension rule are [ADR 0092](0092-logs-are-operational-and-name-no-person.md)
§ 2's; the cap of 30 days and its why are this section's.

- **One core table, one row per guest write that enters a guest as last editor (§ 8)** — upload commit, identity
  creation, folder creation, rename, meta: the client's address, its source port where known, the server time in
  milliseconds UTC, the share identity and the object. No User-Agent; nothing a member writes or anyone reads. The port
  and the exact time are kept because behind carrier-grade NAT only address, port and time together name a subscriber
  ([ADR 0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md)).
- **The record never takes an address a client can choose**: a forged address in evidence names an innocent third
  party. It takes the address of veyq's one core resolver (ADR 0094 decision 3), and records nothing where that yields
  none. On Compose the address is strong by construction; on Helm only as strong as an enforced NetworkPolicy (0094
  decision 6), and the disclosure and the Run docs say so.
- **Its basis is Art. 6(1)(f) GDPR**: a host has no duty to keep addresses and may keep them briefly, for a stated
  purpose, disclosed — the legitimate interest in tracing the misuse of a guest link once it is reported (§ Legal
  basis, 4). The Server Owner is the controller; the information duty is theirs, not the tenant owner's.
- **No switch: the record is kept wherever a guest may write.** A row exists only where a link lets a guest write, so
  the guest-write switches (`core.public-shares.contribute` and `.drop-box`, at the instance and in each tenant) already
  decide where it is kept, and a Server Owner who wants no guest address kept switches guest writes off. A switch of its
  own would let an operator drop the trace exactly where a write link reached strangers — what an operator-blind Server
  Owner cannot see — and a default per mode made the mode a second source of truth for it
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 14).
- **Retention is N days, at most 30, 7 by default**, an instance setting (0092 § 2). Seven days is the supervisory
  practice and roughly what an access provider keeps, past which a host's address resolves to nobody; a value above
  seven needs a reason the operator writes into their record of processing. The cap is what bounds the legitimate
  interest (§ Rejected alternatives), and no guest is kept longer than the page told them (0092 § 2).
- **Backups leave the rows out** ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)): what is lost
  is at most 30 days old and expiring anyway, and what was reported lives in the hold tables, which are backed up.
- **Disclosure (GDPR Art. 13, at collection), from the Server Owner, never from a tenant's terms**: one first-layer
  sentence on every guest write page, with N as it stands at the write, linking the record passage of the instance
  privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8, the one home of every Art. 13 passage).
  **The right to object (Art. 21) is stated separately** (Art. 21(4)); an objection erases the rows so far without
  stopping later writes from being recorded. The questions the sentence touches (§ Legal basis, Still open 4 and 10)
  are not pursued.
- The table, the writes, the purge, the objection, the disclosure and the operator's own backups are
  [en.moderation.guest-write-record](../features/moderation/enablers/en.moderation.guest-write-record.md); the period
  beside every other is [cc.privacy.retention](../crosscutting/cc.privacy.retention.md).

### 11. The moderation log, and the instance contact

- **An append-only table** of case events and of instance events ([ADR
  0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 4): structured fields in the
  clear (event, time, moderator account, ids, step), every free text sealed to the moderation key, no content and no
  key. A log line names a case id and nothing more (0092 § 1). Not tamper-evident: the operator owns the database, and
  the log is for accountability to the affected and within a team, not a control.
- **A case's events are kept to 31 December of the third year after the case closes** — the civil limitation period
  from year's end, and the one for the DSA's fines (§ Legal basis, 3) — dismissed cases included, since a dismissal is
  what an injured party may later contest. An instance event is kept the same way from the later of the event and its
  moderator's removal. Longer only while proceedings are pending, as a logged extension.
- **No case closes itself** — that would be a decision no person took. A case closes when its last hold has ended and
  no objection is open; an undecided one stays in the queue, and the moderators are reminded at 30, 75 and 90 days
  (DSA Art. 16(6), "timely"; [uc.moderation.review-a-case](../features/moderation/use-cases/uc.moderation.review-a-case.md)).
- **Every moderation record goes with its case's log, in one purge** (GDPR Art. 5(1)(e)), the report shares — which
  wrap `K_report` — among them. **Never while a measure of it is in force**: deleting the row would lift a block nobody
  lifted (§ 6), and a standing measure needs its statement for as long as it can be contested (DSA Art. 17, GDPR
  Art. 17(3)(e)); nor while a proposal of it waits for the Server Owner. An order that opened no case and a Server
  Owner's step on no case (§ 7) follow the same three-year rule from their entry or lift.
- **The reporter's contact** goes six months after the decision is told to them (DSA Art. 16(5)) — where it could not
  be told, six months after the decision — and never while a case of the report is open; the "receipt sent" and
  "decision sent" events stay as the proof.
- **An instance contact**, set in admin-web and the CLI: the single point of contact for users and authorities
  (DSA Art. 11–12) and the TCO contact point for removal orders (TCO Art. 15), with the languages it answers in, a
  notification address confirmed by a code before it receives anything, and the address the disclosures and statements
  name. It is public, in the operator section of the terms. It is never required and nothing warns about it (§ 14): an
  empty contact renders as "not stated by this operator" wherever a text names it. The flow is
  [uc.admin.operator-details](../features/admin/use-cases/uc.admin.operator-details.md).
- The events, their dates and the purge as built are
  [en.moderation.log](../features/moderation/enablers/en.moderation.log.md); the periods beside every other are
  [cc.privacy.retention](../crosscutting/cc.privacy.retention.md).

### 12. An authority's order acts on ids, within the hour, without a key and without a report

- **A removal order names a URL**, which the server cannot read and for which no report share exists. So the order
  path is separate from the report flow, entered by a moderator in the moderation surface with the key proof, or by
  the Server Owner in admin-web **and** in `veyq-admin` without a key
  ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6): in goes the
  share URL or share id, the authority's reference and the time of receipt; at once, with no review of its own (the authority has
  reviewed), the server **blocks the share and the content it reaches** by id (§ 6) and opens a case with a **hold
  without a report** whose end the order sets (§ 4). Out comes the text for the feedback form (TCO Annex II, the
  time of the block in UTC to the second) and a draft for the form that reports an obstacle (Annex III).
- **One hour from receipt at the contact point** (TCO Art. 3(3)), with no exemption for a small host. veyq
  cannot make an operator read their mail; the Run docs recommend forwarding the instance contact to a phone, and
  say plainly that an operator who cannot keep the hour turns public links off (`core.public-shares`).
- **A preservation order or a request for information** (the e-Evidence Regulation, §§ 100j–100k StPO) opens the
  same hold without a block. Information handed over is an event in the moderation log with the reference sealed.
- **The notice to the uploader** (TCO Art. 11; DSA Art. 17 does not reach an authority's order, Art. 17(5)) follows § 7, including its deferral on the authority's
  request. **A guest uploader is told too, on a removal order** (TCO Art. 11(1) asks the host to make the information
  available to the content provider, with no condition of a known contact, unlike DSA Art. 17(2)): an identity whose
  upload a removal order blocked — whose client still holds that identity — is answered, on the link it came through,
  even while that link is blocked: removed on an authority's order of `<date>`; the reasons or a copy of the order on
  request from `<instance contact>` (Art. 11(2)). Nothing else is served to it. A running deferral (Art. 11(3))
  holds this back exactly as it holds the statement. For a moderator's measure the same read answers its statement (§ 7):
  the clear fields, without the facts.
- The flow is [uc.moderation.authority-order](../features/moderation/use-cases/uc.moderation.authority-order.md).

### 13. Art. 18 reports, and child sexual abuse material

- **Where an Art. 18 report goes**: in Germany to the BKA as the central office (§ 13 DDG), through its portal
  after registering by mail (the Run docs name it and say to register **before** an emergency); without a
  registration, a criminal complaint with the police; in acute danger, 110. Terrorist content already reported
  under TCO Art. 14(5) needs no second report. The moderation surface exports the clear fields the BKA form asks for — case id,
  times in UTC, the uploader's account or share identity, the address from a held guest-write record, the category —
  and **never a content file**. Handing over is an event in the moderation log (§ 11).
- **Child sexual abuse material gets its own flow.** The mail goes out at once (§ 2); the moderation surface shows, before
  the report opens, "do not download, forward or screenshot; review only as far as needed, block the content, inform
  the police or the BKA"; and it **proposes "Block the content" as the first measure**, because once known,
  serving it on is a making-available of the host's own. A human still decides (§ 2). The hold ends at the
  handover, at the latest 90 days after the report (§ 4), so it serves the reporting duty and nothing else (§ 184b(5)
  StGB): recording the handover ends it, and the moderation surface says so before it is recorded. A file or a
  screenshot never goes by mail or to a hotline. The flow is
  [uc.moderation.report-to-an-authority](../features/moderation/use-cases/uc.moderation.report-to-an-authority.md).

### 14. No operating mode: every instance carries the full texts, and the Server Owner fills in what applies

Every instance serves **one** form of each fixed text: the full operator section of the terms (ADR 0035 decisions
6–7), the full statement of reasons (§ 7) and the full privacy notice (ADR 0035 decision 8). **Nothing is required
and nothing warns**: every value the Server Owner may set — the instance contact (§ 11), the provider identification
(ADR 0035 decision 7), a privacy contact — is optional, and an empty one renders as "not stated by this operator"
wherever a text names it. Which duties reach an instance turns on facts veyq cannot see — who the
members are, whether a tenant owner posted a link publicly — so the Server Owner fills in the values once they apply,
as on any other self-hosted service; the gap stays visible to whoever reads the text until they do.

**What the Server Owner is told, as information and not as a choice**: at first-run setup, in admin-web's operator
settings and in the Run docs. The GDPR's household exemption covers only a natural person's purely personal or family
activity, and it ends once content is made reachable for an unrestricted number of people — a link shared publicly
(CJEU C-101/01 *Lindqvist*, C-345/17 *Buivids*); whether the DSA and the TCO Regulation reach an unpaid private
instance is legally uncertain (§ Legal basis, 1); an operator who shares links publicly or runs veyq for a club or a company
is probably not exempt, and then owes at least a name and a postal address (§ 5 DDG, § 18(1) MStV) and the controller's
contact details (Art. 13(1)(a) GDPR). It covers every tenant on the instance: the Server Owner is the host for all of
them (ADR 0099 § 1), and cannot see whether a tenant owner posted a link publicly, so a friend's tenant stays private
only as long as its links do. *This is your own assessment, not veyq's.*

**Why no mode.** A mode *private* or *club, organisation or company*, chosen at setup, would decide only how long the
texts are: the guest-write record has no switch (§ 10), and a warning about empty values has no reader an operator would
not ignore. Its reduced texts would leave out what veyq does on every instance anyway (the report path, the order path,
the Art. 18 export are built everywhere and refused nowhere, "it costs nothing until someone reports"). So the full
texts describe every instance more truly, and one form of each is one state to test instead of two. A sentence in them
that grants a right only the DSA gives says *where the DSA reaches this instance* in its own words (§ 7's reporter
redress).

## Consequences

- The operator can act on one share, one item or one uploader instead of a whole tenant, and reads only what was
  reported: this mechanism opens nothing unreported. Against an operator who serves the client it holds no more than
  [ADR 0026](0026-client-trust-tiers.md) says. The T&S target in the security model becomes buildable as written.
- Costs: a new share kind (medium-scoped and field-scoped), hold tables and the sweep's skip, a moderation mode in
  the share viewer, src/web/'s moderation surface with the queue, admin-web's and the CLI's Server-Owner steps, the
  moderator role and key ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)),
  an editor column per field group, per-field keys in the crypto suite with new vectors, the guest-write record, the
  moderation log, and a reader for each new stored time: the purge for records, holds and the log (each hold's and
  each case's own end).
- Further costs from the operator premise: the order path in the moderation surface, admin-web and the CLI with a
  hold that has no report and an end the order sets (§ 12); per-trigger hold ends and logged extensions (§ 4); the deferral switch (§ 7);
  the report dialog's fields and categories (§ 1); the CSAM flow and the BKA export (§ 13); the backup dump without
  the record's rows, which changes what [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)'s
  database leg holds (§ 10); and in [ADR 0035](0035-server-terms-of-service.md) the fixed operator section and the
  provider identification, each in one form (§ 14).
- **Transparency reports: the operator's duty, not built** (§ Legal basis, 8). An operator may owe three — DSA
  Art. 15 unless a micro or small enterprise (whether a non-economic club is one is Still open 8), TCO Art. 7(2) for a
  year they acted against terrorist content, § 3(2) TerrOIBG under German jurisdiction — and veyq compiles none. The
  [Run area](../../site/src/content/docs/run/moderation.md#transparency-reports) says
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
  hour has to turn public links off. A household instance may be outside the DSA, the TCO Regulation and the GDPR;
  the Run docs show which instances are (a table by operator kind, and that one link posted publicly can
  leave the household exemption), which values each kind of operator owes (§ 14), and that veyq meets a hosting
  service's duties on every instance once those values are set.
- **Docs:** the Run area's [moderation](../../site/src/content/docs/run/moderation.md) page carries an emergency
  page (110, the BKA portal registered ahead, the BKA's TCO contact, what never to do); the terms template's clause 9
  points to the operator section ([ADR 0035](0035-server-terms-of-service.md) decision 6), and its clauses 2 and 7
  say what the host keeps about a guest write.

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
- **For the guest-write record**: hashing the address (an IPv4 hash falls to 2³² guesses) or truncating
  it (a /24 loses the evidence); sealing it to a hold key (the operator sees every address live at their own proxy);
  columns on the identity and upload tables (each write overwrites the last, and the purge would touch two
  applications); a tenant owner's switch (the operator answers for the server); an access log (0092).
- **Deferring the statement at the moderator's discretion**, "in a criminal matter". Art. 17 DSA knows no such
  exception; only an authority can ask for silence (§ 7).
- **One 90-day end for every hold.** Too long once a case is dismissed, too short after a measure (objection,
  proceedings) and against a TCO order's six months.
- **Keeping the guest-write record as long as the access providers may soon have to** (three months in the German
  bill). That bill does not reach a host, and the 1-to-30-day cap is what bounds the legitimate interest.
- **An operating mode, *private* or *club, organisation or company*** (§ 14 says why): it ended as a second state for every fixed text that only shortened them, and a preset that let a
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

The research behind this ADR — the law, its primary sources and the reading taken — is the
[legal register](../crosscutting/legal-register.md): research, not legal advice.

**veyq does not have the texts it ships legally reviewed**: the operator section of the terms and
the privacy notice ([ADR 0035](0035-server-terms-of-service.md) decisions 6 and 8), the statement of reasons and its
redress (§ 7), the guest-write disclosure (§ 10), the order forms (§ 12) and the child-sexual-abuse warning (§ 13).
veyq is software a Server Owner runs, and a host's duties are theirs, not veyq's authors' (Context), so having the
texts reviewed is not veyq's responsibility for now: a Server Owner may have it done for their instance, and a review
comes if veyq moves toward a hosted service of its own. So no text says it is a draft or carries a banner; each is
simply the text veyq ships.

What the register concluded, and where this ADR applies it:

1. **Which law reaches an instance** (L1) — Context, § 14.
2. **The terms** (L2) — [ADR 0035](0035-server-terms-of-service.md) decisions 6–8.
3. **Hold and log durations** (L3) — §§ 4, 11.
4. **The guest-write record** (L4) — § 10.
5. **Art. 18 and child sexual abuse material** (L5) — § 13.
6. **Terrorist content** (L6) — § 12.
7. **IP retention in Germany** (L7) — § 10, and Rejected alternatives.
8. **Transparency reports** (L8) — Consequences.

### Still open

Questions the law leaves open for an operator who runs veyq for a household, a club or a company. What is built is
**our reading, not a reviewed answer**, and nothing they name is built on an assumption either way (no hold past 90
days, no authority link). A question holds back a CR or a release only where what is built would be unlawful on a
plausible reading and a cheap alternative on the safe side exists; then the safe side is built. The others are not
pursued further. They stay named here because code and docs cite them by number, and because an answer that
differs, should one come, is a legal duty that wins over what is built (`CLAUDE.md`).

1. § 184b(5) StGB beyond the 90 days, and for a non-commercial operator.
2. A time-bound authority link to a hold snapshot.
3. Whether the German IP-retention bill has passed.
4. What the recorded source port is worth behind an operator's own NAT.
5. § 184b(5) StGB for a club's volunteer moderators.
6. Who is the controller of a member's account data.
7. The breached-password check and the exchange rates on by default.
8. Whether a non-economic club is an "enterprise" for DSA Art. 15(2).
9. Whether TCO Art. 3(7) excuses a private operator who cannot be reached within the hour.
10. The layered Art. 13 information on a guest page, and Art. 14 for people a report names.
11. Reported content inside a backup capture.
12. Whether erasing a switched-off application's content needs a basis in the terms.
13. Whether the notice of that erasure is adequate.
14. Whether switching an application off is a moderation decision.
