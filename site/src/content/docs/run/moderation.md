---
title: Moderation
description: What a Server Owner and the moderators can do about abuse on an instance they cannot read, and what is not built.
sources:
  - docs/features/moderation/use-cases/uc.moderation.review-a-case.md
  - docs/features/moderation/use-cases/uc.moderation.take-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.authority-order.md
  - docs/features/moderation/use-cases/uc.moderation.report-to-an-authority.md
  - docs/features/moderation/enablers/en.moderation.log.md
  - docs/features/admin/use-cases/uc.admin.suspend-tenant.md
  - docs/features/admin/use-cases/uc.admin.delete-tenant.md
  - docs/features/admin/use-cases/uc.admin.switch-capabilities.md
  - docs/decisions/0066-tenant-suspension.md
  - docs/crosscutting/security-model.md
  - docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md
  - docs/decisions/0032-capability-discovery-version-gating.md
  - docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md
  - docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md
  - docs/features/moderation/use-cases/uc.moderation.appoint-moderators.md
  - docs/features/admin/use-cases/uc.admin.operator-details.md
  - docs/features/moderation/use-cases/uc.moderation.report.md
  - docs/features/moderation/enablers/en.moderation.hold.md
  - docs/features/moderation/enablers/en.moderation.guest-write-record.md
---

There is no central moderation service, because there is no central service. And an instance's operator cannot
read what its members keep: private content is unscannable by design. Moderation on a veyq instance therefore
works on what someone reports: exactly that opens to the moderators, and every step they take acts on its ids
([security model § Sharing & share-boundary Trust & Safety](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#sharing--share-boundary-trust--safety)).

## What a report does

Anyone who sees something — a member of a space or a guest of a link — can choose **Report** on it
([Report something](/veyq/help/report-something/)). What happens on your server
([uc.moderation.report](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.report.md)):

- **It arrives sealed.** The reporter's device seals their text, their address and the keys to exactly what they
  reported to the current moderation key. The database holds the category, the time, the reported items' ids and who
  reported (an account, or the link a guest came through) — nothing it can open.
- **One case per item.** A further report of the same item joins its open case and raises its category if it is more
  urgent.
- **It is kept.** The rows of what was reported are copied, and its stored objects are pinned: the reclaim sweep leaves
  them alone, so a delete after the report takes the item away from everyone but not from the moderators — for 90
  days after the report ([en.moderation.hold](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.hold.md)).
  You store those bytes, and a backup includes them; the tenant's quota falls as its members expect. A space's erasure
  leaves the report and the copy.
- **Moderators are mailed** the category and "new case", with no id and no link: child sexual abuse material, terrorist
  content and a threat to life at once, the rest in one mail a day. The instance contact's notification address is
  mailed too once confirmed. A reporter who left an address gets a receipt.
- **Nothing is blocked.** The item stays where it was until a person decides.

A report a member sends counts against a cap of open reports per account (`veyq.moderation.report.open-cap`, 100); a
guest has no account, so a guest's reports are bounded only per address and per link (`veyq.moderation.report.per-share-max-requests`).
Someone rotating addresses can still send many; each costs you a moderator's look and the held bytes for 90 days.

## Review a case

The moderators — you among them — review reports on `/moderation`, on the address of any space they sign in on
([uc.moderation.review-a-case](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.review-a-case.md)).
Every list and every step there carries a signature with the moderation key, so a moderator's session alone opens
none of it.

- **The queue** lists the cases of every tenant in the fields the server holds in the clear: the category, the kind of
  item, when it was first and last reported, how many reports, the tenant's address, the state (open, decided,
  closed) and when its hold ends. The urgent categories come first, then the oldest. **Show closed cases** lists the
  closed ones.
- **A case** shows each report's reason, contact and context — opened in the moderator's browser with the moderation
  key — and the case's measures, proposals, hold and log.
- **Review what was reported** opens `https://<tenant address>/s/<case id>#moderation`, on the reported tenant's
  address; when that tenant is gone or offline, on the address the moderator is on. The page unlocks the moderation key
  again and keeps nothing else of the moderator's account open, fetches the held copy and shows it the way a guest of a
  link sees it, read-only, with the reports beside it. It shows what was held when it was reported: after the link, the
  item or the whole space were deleted, and also what a measure blocks. Each fetch is an *opened* event in the log. A
  link's password plays no part, and once the hold has ended there is nothing left to review.

## Decide

In a case, any moderator takes these steps; each takes an optional note, sealed in their browser so that only
moderators can read it ([uc.moderation.take-a-measure](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.take-a-measure.md)):

- **Dismiss the case** — closes it without a measure. Its hold ends at once: what was kept only for the review is
  released, and the store reclaims what was deleted meanwhile.
- **Block a link** — the reported link, a link a guest reported through, or a link that reaches what was reported.
  Every guest of it then gets the answer of an unknown link. Its creator can neither re-activate nor renew it while the
  block stands.
- **Block the content** — the reported folder, Memory, file or photo. Nobody can open it any more, members included:
  every listing keeps it as **Blocked by the operator**, with no reason, and every byte of it, and of everything below
  it, is refused with `451`. Whoever could delete it still can; it cannot be restored from the trash, and a file's
  content cannot be replaced, while the block stands. An upload link has no content to block.
- **Block a guest uploader** — a guest the held copy names as an uploader. It can add nothing more, and the link it came
  through is blocked with it, since a guest could otherwise name itself anew on the same link. The link's creator may
  renew it, under a new address: that is a member's new decision.
- **Bar this reporter** — further reports from that member, or through that link, are refused until the bar is lifted.
  The reports already made stay. A bar decides nothing about the content.
- **Extend the hold** — while something is held: a new end after the current one and at most six months from now, with
  a reason, which is required and sealed like a note.
- **Propose** one of your steps — blocking a member's sign-in, or taking the tenant offline (below).

**Every block goes with its statement of reasons** ([uc.moderation.object-to-a-measure](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md),
[ADR 0093 § 7](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)): the
moderator picks the **Ground** — one of the report categories, the law or the terms — and writes **What you found**. The
browser seals the facts twice: to the moderation key, for the log, and to each member the statement reaches, so the
server stores neither in the clear. It reaches the creator of a blocked link (or of the link a blocked guest came
through), the uploaders the held copy names, and on a content block every owner of the space: each reads it under
**Blocked by the operator**, and the instance mail tells their account address that it is there — never the facts. A
guest is told nothing, having no account. A reporter who left an address is told the decision, from that address, which
the moderator's browser sends once and the server mails without keeping. The statement names the instance, never the
moderator. Without instance mail ([Configuration](/veyq/run/configuration/)) the statements are in veyq all the same.

A measure decides the case, and its hold then ends six months after its statement is sent (after the decision, where
nobody could be told). **Lift** on a measure — any
moderator's — serves its target again, unchanged; lifting the case's last measure in force ends its hold and closes
the case. Blocking deletes nothing. A step on something the case does not know, or one already in force, is refused.
A block reaches the reported item only: a copy a member made of it is a new item.

## A member's sign-in, and a tenant: your steps

Blocking a member's sign-in and taking a tenant offline are yours alone. They are operations of the admin console's
channel, so a moderator's page cannot reach them. The moderators propose them in a case; **Server → Moderation** lists
the open **Proposals**, each with its case, its category and its target:

- **Block sign-in** — every session of the account ends at once, and every sign-in is refused, with the same answer as
  a wrong one, until you lift the block under **Sign-in blocks**. Nothing of the account is deleted. Your own account
  cannot be blocked. Blocking an account settles every open proposal to block it as taken.
- **Open the tenant to suspend it** — takes you to the tenant, where **Take offline** settles its open proposals as
  taken ([below](#take-a-tenant-offline)).
- **Decline** — nothing is done; the case shows the proposal as declined, and the log records it.

The same with the CLI:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin moderation proposals
```

`moderation proposals` prints each open proposal (its id, case, category, step and target: a member's address, or a
tenant's id and address) and every sign-in block in force. Then, in the same `exec`:

- `veyq-admin account block --address <address> [--case <caseId>]` blocks a sign-in, with the case it answers when
  there is one;
- `veyq-admin account unblock --address <address>` lifts it;
- `veyq-admin moderation decline --proposal <id>` declines a proposal;
- `veyq-admin tenant suspend --tenant-id <id>` takes a tenant offline (below).

## Take a tenant offline

**Take offline** in the admin console, or the CLI:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin tenant suspend --tenant-id <id>
```

What it does ([uc.admin.suspend-tenant](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.suspend-tenant.md),
[ADR 0066](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0066-tenant-suspension.md)):

- Every member is refused with `403 tenant_suspended`, reads included, on any domain and with a valid session.
  Someone who is not a member gets the same `404` as before.
- Every public link answers as a revoked link, and no background job of the tenant runs.
- The tenant's routing is withdrawn as a second layer.
- Nothing inside is changed or deleted: no key, grant, content or session. **Bring back online** (or
  `veyq-admin tenant reactivate --tenant-id <id>`) restores everything as it was.
- It applies to an active tenant only, and it locks out your own membership in that tenant too.

You still cannot read anything private, which is the design working as intended.

To close a tenant for good, **Delete tenant** takes it offline the same way and erases it 30 days later
([First-run setup and the admin console](/veyq/run/first-run/#tenants)); a deletion settles the tenant's open proposals
as taken, as a suspension does. Its holds and this page's records stay: they hang on cases, not tenants.

## What else you have

- **A storage quota per tenant** — the most a tenant may keep on your store
  ([Plan your instance](/veyq/run/plan/#storage-and-quotas)).
- **Revoking a tenant** that was created but never accepted (a pending one) deletes it. There is no operation that
  deletes an active tenant.
- **Switching a feature off for the whole instance**, for example public links or devices that unlock without a
  password (below).

## Switch a feature off

You can switch a feature off for the whole instance, for example public links, drop boxes or devices that unlock
without a password, in the admin console or with `veyq-admin capabilities`. What each switch does to new and to
existing data, and how to switch it, is on [Capabilities](/veyq/run/capabilities/).

## Objections

A member a statement reaches can **object once** per measure, in veyq, while the measure stands: free text, sealed so
that only moderators can read it. The objection reopens the case — a closed one is decided again and back in the queue
— and the moderators are mailed that one arrived. In the case it shows under **Objections**, opened.

**Another moderator decides it** ([ADR 0099 § 5](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)):
**Uphold the measure** or **Lift the measure**. The server refuses the decision from the moderator who took the measure
while another moderator holds the key, and the log records who decided. On an instance with **one moderator** — often
you alone — the objection does not wait for a second one: you decide it, the log marks it as your own review, and the
answer to the member says so. Either way the member reads the answer under the statement and is mailed that it is
there; after an upheld measure, only the courts remain. This binds a team, not you: you own the server and the roster.

## An authority's order

An authority's order acts on ids, needs no key and opens nothing ([uc.moderation.authority-order](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.authority-order.md),
[ADR 0093 § 12](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
A moderator enters it on the moderation page (**Enter an authority's order**, above the queue); you enter it under
**Server → Moderation**, or over SSH:

```sh
veyq-admin order remove   --share '<the link as the order names it>' --reference '<the authority’s reference>' [--received-at <ISO time>] [--defer-until <ISO time>]
veyq-admin order preserve --share '<link or id>' --reference '<reference>' [--received-at <ISO time>]
veyq-admin order inform   --share '<link or id>' --reference '<reference>' [--received-at <ISO time>] [--hold-days <days>]
```

- **What goes in.** The kind, the link (or its id), the authority's reference and when the order reached your contact
  point. Paste the link as the order names it: only its id leaves your browser or shell — the part after `#` is the
  link's key, and an order needs none. The reference is sealed to the moderation key before it is sent, so the server
  cannot read it; enter it again when you copy a form.
- **A removal order** (terrorist content, TCO Regulation Art. 3) blocks the link and everything it gives access to, for
  everyone, members included, **at once** and without a review of its own: the authority has reviewed. A block already
  in force counts. Out comes the text for the authority's feedback form (Annex II) — the time of the block in UTC to the
  second — to copy. A link this instance does not know is recorded all the same, nothing is blocked, and you get a
  draft of the obstacle form (Annex III).
- **A preservation order** or **a request for information** blocks nothing; what the link gives access to is kept.
- **How long it is kept.** Each order opens a case of its own, with no report, holding what the link reaches: six
  months after the block for a removal order, 60 days after receipt for a preservation order, the days a request names
  (six months when it names none). No extension, lift or later decision ends it sooner; an extension with the
  authority's further request makes it longer.
- **The affected are told**, as after any block, with a statement of reasons that names an order as its cause. An
  authority may forbid that for up to six weeks (TCO Art. 11(3)): enter the date with the order, and the statement is
  held until then. A moderator may enter the authority's renewal once, for at most six more weeks; at the end it goes
  out on its own, and the queue reminds of it until then. Deferring is the authority's decision, never a moderator's.
- **Information handed over** in answer to an order is recorded in the case's log with its reference (below).

**The hour is yours to keep.** A removal order has to be acted on within one hour of reaching your contact point, with
no exemption for a small host. veyq cannot make you read your mail: forward the instance contact to a phone. If you
cannot keep the hour, turn public links off (`veyq-admin capabilities off core.public-shares`, or **Server →
Capabilities**).

## Report to an authority

A suspicion of an offence against life or safety — child sexual abuse included — is reported to an authority
(DSA Art. 18; [uc.moderation.report-to-an-authority](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.report-to-an-authority.md),
[ADR 0093 § 13](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
In a case, **Prepare the fields for the report** lays out what the instance holds in the clear: the case, its category,
every time in UTC, the measures, the uploaders the held copy names — a member's account id, a guest's identity id — and
every guest-write record it held, with the address, the source port where one was kept, and the time. It is a text
block to copy into the authority's form: never a file, never content. **Record that it was reported to an authority**
(or that information was given) writes the handover into the case's log with the reference, sealed.

**Child sexual abuse material.** Before such a case opens, the moderation page shows a full-page warning, and its
decision panel proposes **Block the content** first. A human still decides.

## In an emergency

> **Draft — not legal advice.** This section, the order forms and the warning above are drafts until a qualified legal
> review ([ADR 0093 § Legal basis](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md#legal-basis)).

- **Acute danger to someone's life or safety: call 110** (the police; 112 for an ambulance or the fire service).
- **Register with the BKA's reporting portal before you need it** (`u-entrance.bka.de`, registration by mail): it is
  where Art. 18 reports go in Germany. Without a registration, file a criminal complaint with the police.
- **Removal orders** come from the BKA; its TCO contact is `TCO@bka.bund.de`. A terrorist-content report you made under
  TCO Art. 14(5) needs no second report.
- **Never download, forward or screenshot reported content, and never mail a file of it** — not to an authority, not to
  a hotline. Copy only the fields above.
- **Child sexual abuse material: contact the police at once, and keep nothing beyond the hold.** Whether keeping a hold
  until the handover is covered for a non-commercial operator (§ 184b(5) StGB) is open; a handover recorded in veyq
  changes no hold, and no link for the police to a held copy exists.
- **Forward the instance contact to a phone**, and turn public links off if you cannot act within the hour.

### Which law reaches your instance

> **Draft — your own assessment, not legal advice** (ADR 0093 § Legal basis, 1).

| Who runs it | GDPR | DSA Art. 16–18 (notice, statement, reports) | TCO Regulation (removal orders) |
|---|---|---|---|
| A family or circle of friends, no link posted publicly | the household exemption may cover it; uncertain for the one who provides the means for others (recital 18) | uncertain for an unpaid service | uncertain: it reaches dissemination to the public |
| The same, with a link posted publicly | probably reached (*Lindqvist*, *Buivids*) | uncertain | probably reached |
| A club or an association | reached | likely, where members pay fees | reached for public links |
| A company, or a paid host | reached | reached | reached |

Criminal law and the hosting privilege's "once known, act" reach every operator in either case
([§ 7 DDG](https://www.gesetze-im-internet.de/ddg/__7.html)). The operating mode below is how you record your own
answer.

## The operating mode

At first-run setup you choose how the instance is run, and you can change it under **Server → Operating mode** or
with `veyq-admin mode set` ([uc.admin.operator-details](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.operator-details.md), [ADR 0093 § 14](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)):

- **Private (family, circle of friends)** — veyq leaves off what only the GDPR, the Digital Services Act and the EU
  regulation on terrorist content ask of a host: the contact and the provider identification become optional and
  nothing warns about them. Reporting, blocking and the moderators stay on in either mode: criminal law applies to
  every operator, and an operator who learns of illegal content has to act.
- **Club, organisation or company** — the contact and the provider identification are expected: the console warns
  while public links are on and no contact is set, and while the identification lacks a name, an address for service
  or an email. An instance where no mode was ever chosen runs as this one.

The mode also sets the **form of a statement of reasons**, read each time one is shown or mailed: in the second mode it
is the full form, and a block needs its facts; in *private* it is reduced — the measure and its reach, the ground and
the objection, the facts only where the moderator wrote them, and without whether a report or an order led to it, that
no automated means decided, and the courts. Who receives it, the objection and its review do not change with the mode.

**It is your own assessment, not veyq's, and not legal advice.** The choice comes with a notice that says so: the
GDPR's household exemption covers only a purely personal or family activity and ends once a link is shared publicly
(CJEU *Lindqvist*, C-101/01; *Buivids*, C-345/17), and whether the Digital Services Act and the terrorist-content
regulation reach an unpaid private instance is legally uncertain. If you share links publicly, or run veyq for a club,
an organisation or a company, you are probably not exempt. Choosing *private* needs a tick in the console, and
`--accept-notice` with `veyq-admin mode set private`; the server does not record either.

**A mode is a preset.** Choosing one sets the switches it names to its values, after the console and the CLI have
shown which ones would change; each stays an ordinary switch you can set on its own
([Capabilities](/veyq/run/capabilities/)), and choosing the mode again sets them again. A tenant's own switches are
never touched. Nothing is required and nothing is refused in either mode: a warning is a warning.

## What a guest's write keeps

When a guest of a link adds or names something — names itself, creates, renames or moves a folder or file, adds a
photo or a file — veyq keeps the guest's IP address, its source port and the time to the millisecond, with the guest
identity and what was written, for **N days** ([en.moderation.guest-write-record](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.guest-write-record.md), [ADR 0093 § 10](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
Nothing a member does and nothing anyone only looks at is kept, and never a User-Agent or content. It is evidence: if
what a guest added is reported, the report's hold copies these rows, and a moderator can hand them to an authority.
Everything else expires.

- **The switch** is the capability *Record guests' writes* (`core.public-shares.guest-write-record`,
  [Capabilities](/veyq/run/capabilities/)). Its default follows the operating mode: **off** in *private*, **on** in
  *club, organisation or company*; you can set it on its own afterwards. Off, nothing new is kept and the next cleanup
  (hourly, and at every start) deletes what was; what a report's hold copied stays for the hold's time.
- **N** is set under **Server → Moderation**, or with `veyq-admin guest-write-record set <days>`: 7 by default, 1 to
  30. Seven days is the supervisory practice; a longer time needs a reason you write into your record of processing. A
  shorter N reaches every kept row at the next cleanup, a longer one only rows written from then on, so no guest is
  kept longer than the page told them.
- **Guests are told** before their first write, on the page itself, with the N in effect and the right to object as a
  sentence of its own. You are the controller, not a space's owner; the full information belongs in the operator
  section of the terms, which comes with a later change.
- **The address is the one the network vouches for**, never one the client wrote ([Networking](/veyq/run/networking/)).
  On Compose it is strong by construction; on Kubernetes it is only as strong as an enforced NetworkPolicy. The source
  port is known where your edge states it (on Compose, the recipe's Traefik always does); otherwise only the address is
  kept, and an address without a port may name no single subscriber behind carrier-grade NAT.
- **Keep the host's clock on NTP.** A port is only worth something with an exact time: a carrier hands it to the next
  subscriber quickly, and a clock minutes off names the wrong one.
- **Backups.** `veyqctl backup` (the instance backup) dumps the table's schema and none of its rows, so a capture never
  keeps a guest past N. If you back up otherwise — volume snapshots, a dump of your own — those copies hold the rows:
  keep such a backup at most 30 days (call it B), open it only to restore it, and let the restored instance start once
  (its cleanup then deletes what is past N at boot). A guest's data can then live N + B days, and what you tell guests
  has to say so.

## Who runs the instance

**Server → Operator** (or `veyq-admin operator set`) holds what a host publishes about itself; every field may stay
empty:

- **The instance contact** — the single point of contact for users and authorities, and for removal orders on
  terrorist content, with the **languages it answers in**;
- **the address statements of reasons name** — may be the same as the contact;
- **the provider identification** — **Name**, **Address for service**, **Email**, and for a club or a company who
  represents it;
- **the data-protection supervisory authority**.

These are public: the app reads them before sign-in on every address of the instance, and shows the identification,
where it is set, behind **Legal notice** on the sign-in and share pages. The **notification address** is not public:
new moderation cases will be mailed there. Saving it mails a code to it through the instance mail, once Server → Mail
is set up and confirmed (the panel says so until then); you type the code into the same panel, and nothing is mailed
to the address before. A changed address asks again. **Clear all** removes every value and keeps the mode.

## Moderators

You decide who acts on reports for the whole instance
([uc.moderation.appoint-moderators](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.appoint-moderators.md),
[ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)).
A moderator is an existing account, and every moderator holds the same **moderation key**, which reports will be
sealed to. The server stores the key's public half and a copy of the key sealed to each moderator's account; it can
open none of them.

**From first-run setup on.** Setting the instance up also makes the first moderation key: the admin console seals it
to the account it has just created for you and forgets it. You are the first moderator, and reporting is on from the
first day. **Server → Moderation** shows the key's epoch and the roster.

**Appoint someone.** Enter the address of an existing account and choose **Appoint**, or:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec \
  -e VEYQ_ADMIN_EMAIL=you@example.com -e VEYQ_ADMIN_PASSWORD \
  admin veyq-admin moderator appoint --address deputy@example.com
```

Someone who has no account yet is first invited into a space. The appointment stays **pending** until that person
opens `/moderation` on a space's address they sign in on, reads what accepting means and accepts. Accepting mails them
a code through the instance mail (Server → Mail must be set up) to confirm their address for notices. The key reaches
them from a moderator who holds it, the next time that moderator opens `/moderation`; the roster then shows they hold
it. `veyq-admin moderator list` prints the roster.

**Remove someone.** **Remove** on their row, or `veyq-admin moderator remove --address <address>`. Their access and
their copy of every key end at once, and the console then makes a new key for everyone who stays, so reports from then
on are sealed to a key they never held. A removal that would leave nobody holding the key is refused.

**Rotate the key by hand.** Choose a reason (by hand, suspected compromise, identity rotation, removal) and **Rotate
the key**, or `veyq-admin moderation rotate --reason compromise`. Reports already made stay sealed to the key they
were made under; only later ones use the new key.

**When a moderator steps down.** A moderator can step down in `/moderation`, and you can step down too while another
moderator holds the key. Their access ends at once, but until you rotate, reports are still sealed to the key they
held. The panel then says **rotation due**, and the instance mail tells you once. Rotate when you see it. The same
happens when a moderator replaces their account keys; they get the key again from another moderator.

**What is logged.** Every appointment, acceptance, handing on of the key, removal, step-down and new key is an entry
in the moderation log: who, when and for whom, never a key. Entries are kept until 31 December of the third year after
the moderator concerned left the roster, then deleted. A case's log holds each report's arrival, each opening for
review, each step and each lift, each proposal and what became of it, an extension and the end of the hold: who and
when, a measure's kind and target, and the notes and reasons only sealed. It is kept until 31 December of the third
year after the case closed.

**What this does not protect against.** You own the server and the roster. A server that hands out a key of its own
choosing could read later reports; moderators' pages compare the key the server publishes with the one they hold and
stop when it differs, but a reporter cannot check it. Moderation keeps a team accountable to each other; it is not a
control against the operator.

## Not built

- **A statement for your own steps.** A member's sign-in block and a tenant taken offline or deleted carry no statement
  of reasons yet: you take them on the admin channel, without the moderation key, and a blocked member cannot sign in to read one.
  Tell them through the instance contact.
- **A child-sexual-abuse case's hold ending at the handover** (ADR 0093 § 4): it waits on counsel (§ 184b(5) StGB, see
  [In an emergency](#in-an-emergency)); until then such a hold ends as any other case's, and a handover changes none.
- **A time-bound link for the police to a held copy** — not built, and not to be built without the police's or a
  specialist's answer: it would be a making-available.
- **A report of a single field, or of everything by one uploader.**

These are on the security model's target, decided in
[ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
and [ADR 0099](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md);
see the [Security roadmap](/veyq/security/roadmap/).

**How far this is proven.** Suspension, reactivation, the feature switches, the review, every step above, the statement
and the objection, an authority's order, its deferral and the report to an authority are covered by the backend's, the
web app's and the console's tests of their use-cases, and lane L1 walks a block, its statement, an objection, another
moderator's decision, a removal order from each door and the report's fields in a browser; no CI run has executed that
lane yet.
