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
  - docs/features/admin/use-cases/uc.admin.privacy-notice.md
  - docs/decisions/0035-server-terms-of-service.md
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

- **It arrives sealed.** The reporter's device seals their text, their address — on its own, so it can be deleted
  alone — and the keys to exactly what they reported to the current moderation key. The database holds the category, the time, the reported items' ids and who
  reported (an account, or the link a guest came through) — nothing it can open.
- **One case per item.** A further report of the same item joins its open case and raises its category if it is more
  urgent.
- **It is kept.** The rows of what was reported are copied, and its stored objects are pinned: the reclaim sweep leaves
  them alone, so a delete after the report takes the item away from everyone but not from the moderators — for 90
  days after the report, and for child sexual abuse material never longer than that unless extended (below) ([en.moderation.hold](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.hold.md)).
  You store those bytes, and a backup includes them; the tenant's quota falls as its members expect. A space's erasure
  leaves the report and the copy.
- **Moderators are mailed** the category and "new case", with no id and no link: child sexual abuse material, terrorist
  content and a threat to life at once, the rest in one mail a day. The instance contact's notification address is
  mailed too once confirmed. A reporter who left an address gets a receipt, and the case's log records that it went.
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
  key; the contact is deleted six months after the decision was told — and the case's measures, proposals, hold and
  log.
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
- **Block everything from this uploader** — a member or a guest the held copy names as an uploader, listed with how
  many items they uploaded to the case's space (Memories' media, Depot's files; a cover or a banner is not counted) — a
  count of the attribution the server already holds, never content. Every one of those items is blocked as **Block the
  content** blocks, and the case's hold widens to them, by its own end; what the uploader adds after the decision is
  not blocked. It ends with the uploader, or when its last item is erased. The moderator decides on what the case's keys
  open: a reporter cannot hand over a guest's other uploads.
- **Block a guest uploader** — a guest the held copy names as an uploader. It can add nothing more, and the link it came
  through is blocked with it, since a guest could otherwise name itself anew on the same link. The link's creator may
  renew it, under a new address: that is a member's new decision.
- **Bar this reporter** — further reports from that member, or through that link, are refused until the bar is lifted.
  The reports already made stay. A bar decides nothing about the content.
- **Extend the hold** — while something is held: a new end after the current one and at most six months from now, with
  a reason, which is required and sealed like a note, and its kind in the clear. On a child-sexual-abuse case only an
  authority's request or a report to an authority in progress extends it; the server refuses any other kind.
- **Propose** one of your steps — blocking a member's sign-in, or taking the tenant offline (below).

**Every block goes with its statement of reasons** ([uc.moderation.object-to-a-measure](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md),
[ADR 0093 § 7](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)): the
moderator picks the **Ground** — one of the report categories, the law or the terms —, names **The law or the clause it
relies on** (a short reference in the clear, such as "§ 86a StGB" or "Space terms, clause 4") and writes **What you
found**, which explains why the content falls under it. Both are required, on every instance. The statement
also says that the measure applies wherever the instance is reached and lasts until it is lifted. The
browser seals the facts to the moderation key, for the log, and once more under a key of their own, which it seals to
each member the statement reaches, so the server stores neither in the clear. One block's statement reaches at most
6,000 members; past that the block is refused rather than told to only some of them. It reaches the creator of a blocked link (or of the link a blocked guest came
through), the uploaders the held copy names, and on a content block every owner of the space: each reads it under
**Blocked by the operator**, and the instance mail tells their account address that it is there — never the facts. A
guest is told nothing of a moderator's block, having no account (an authority's removal order is different, below). A
reporter who left an address is told the decision, from that address, which
the moderator's browser sends once and the server mails without keeping, with what they may do next: report again with
more information, write to the instance contact, go to court, and — where the Digital Services Act applies to your
instance, as the sentence itself says — complain to the Digital Services Coordinator of the country they live in (DSA
Art. 16(5), Art. 53); the sealed address itself is deleted six months
later — or six months after the decision, where it could not be told — and the log keeps that the decision was sent. The statement names the instance, never the
moderator. Without instance mail ([Configuration](/veyq/run/configuration/)) the statements are in veyq all the same.

A measure decides the case, and its hold then ends six months after its statement is sent (after the decision, where
nobody could be told). **Lift** on a measure — any
moderator's — serves its target again, unchanged; lifting the case's last measure in force ends its hold and closes
the case. Blocking deletes nothing. A measure also ends when what it stands on is erased — a link deleted, a node or
an item purged from the trash, a guest's identity dropped, a space, an account or a tenant purged: it ends then, lifted by
no one, the log says so, and the case's records run to their date; its hold keeps its own end. A step taken on something
already erased ends at once. A step on something the case does not know, or one already in force, is refused.
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

**A step over content carries a statement of reasons** (DSA Art. 17(1)(c), (d)), in the same template as a moderator's
block: always when you take a moderator's proposal, and otherwise when you say the step is over content. **Block
sign-in** on a proposal opens its **Statement of reasons** — the ground (the case's category to start with), the law or
the clause it relies on, and what you found — and **Block and send the statement** takes it. In the CLI the same
statement goes with `--ground <ground> [--provision <text>] [--facts <text>]`, and `--over-content` marks a block with no
case or proposal as one:

```sh
veyq-admin account block --address <address> --over-content --ground TERMS_BREACH \
  --provision "Space terms, clause 4" --facts "<what you found>"
```

A blocked member cannot sign in to read a statement, so it goes **by the instance mail to the account's address, with
what you found** in your words; the server keeps those words only sealed to the moderation key, for the log. Their
objection goes to the instance contact the statement names, and you lift with **Lift** or `account unblock`. A step
without a case on your own finding says so in the statement. The wording is a draft until a lawyer has reviewed it.

### What the mail reached

The mail goes once: a send that fails is not retried, since the server keeps no copy of your words to send again. So
every step over content — a sign-in block, a tenant taken offline, a tenant deleted — tells you what its mail reached,
and the step stands whatever it says:

- **Before the step**, while no instance mail is set up, the statement's fields say it cannot be mailed. Set it up
  under **Server → Mail** first, or be ready to tell them yourself.
- **After the step**, when the mail did not reach everyone — no instance mail, or the send failed — the console names
  each address it did not reach, in a notice that stays at the top of the Moderation page (a block) or under the
  tenant's name (a tenant) until you close it, with your statement beside it — the ground, the law or clause, what you
  found — and **Copy the statement**. Tell those people through the instance contact, with those words. The notice also
  says when there was nobody to tell (a tenant without an owner, an account erased meanwhile) and when the server
  cannot say whom the mail reached. When it reached everyone, the console adds nothing to the step's usual answer.
- **In the CLI**, `account block`, `tenant suspend` and `tenant delete` print it as `statementDelivery` — `outcome`
  `REACHED`, `NOT_ALL_REACHED` (with the addresses in `notReached`), `NOBODY_TO_TELL` or `UNKNOWN` — and for anything
  but `REACHED` add one `warning:` line on stderr. They still exit 0: the step was taken.

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
- **Over content**, it carries a statement of reasons to **each owner of the tenant**, by the instance mail with what you
  found, as a sign-in block does ([above](#a-members-sign-in-and-a-tenant-your-steps)) — its other members are told
  nothing beyond the space being offline. Under **Take offline or delete** at the end of the tenant's page, tick
  **Because of the tenant's content** and fill in the statement before **Take offline and send the statement**, or add
  `--over-content --ground <ground> [--provision <text>] [--facts <text>]` to `tenant suspend`. A moderator's open
  proposal makes the suspension over content: without a statement it is refused, so the page shows the proposal and
  keeps the box ticked. What the mail reached is said as for a sign-in block ([What the mail reached](#what-the-mail-reached)):
  an owner it did not reach is named under the tenant's name with your statement beside it. A suspension for any other
  reason — unpaid, maintenance — carries none. Bringing the tenant back lifts the record.

You still cannot read anything private, which is the design working as intended.

To close a tenant for good, **Delete tenant** takes it offline the same way and erases it 30 days later
([First-run setup and the admin console](/veyq/run/first-run/#tenants)); a deletion settles the tenant's open proposals
as taken, as a suspension does. **Over content** it carries a statement of reasons to each owner of the tenant exactly
as taking it offline does: the same **Because of the tenant's content** box and statement, above both buttons, or add `--over-content
--ground <ground> [--provision <text>] [--facts <text>]` to `tenant delete`; a moderator's open proposal makes it over
content, and what the mail reached is said the same way ([What the mail reached](#what-the-mail-reached)). **Stop
deletion** lifts that record. Its holds and this page's records stay: they hang on cases, not tenants. A
record still in force on the tenant, or a sign-in block on an account you erase, ends when the purge erases what it
was on, so its statement goes at its date instead of being kept forever.

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
  second — to copy. A link this instance does not know is recorded all the same (kept until 31 December of the third
  year after), nothing is blocked, and you get a draft of the obstacle form (Annex III).
- **A preservation order** or **a request for information** blocks nothing; what the link gives access to is kept.
- **How long it is kept.** Each order opens a case of its own, with no report, holding what the link reaches: six
  months after the block for a removal order, 60 days after receipt for a preservation order, the days a request names
  (six months when it names none). No extension, lift or later decision ends it sooner; an extension with the
  authority's further request makes it longer.
- **The affected are told**, as after any block, with a statement of reasons that names an order as its cause. An
  authority may forbid that for up to six weeks (TCO Art. 11(3)): enter the date with the order, and the statement is
  held until then. A moderator may enter the authority's renewal once, for at most six more weeks; at the end it goes
  out on its own, and the queue reminds of it until then. Deferring is the authority's decision, never a moderator's.
- **A guest who uploaded what a removal order took down is told too** (TCO Art. 11): on the link it came through, from
  the browser that holds its guest identity, even though the link answers everyone else as unknown — when the order
  was received, and that the reasons or a copy of the order are given on request from your instance contact. Answer such
  a request from the order you hold. Nothing is told while the authority's deferral runs, and nothing for a moderator's
  block.
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
(or that information was given) writes the handover into the case's log with the reference, sealed. So copy the fields
**before** you record it: on a child-sexual-abuse case recording the handover ends the hold (below), and the fields then
name no uploader and no address.

**Child sexual abuse material.** Before such a case opens, the moderation page shows a full-page warning, and its
decision panel proposes **Block the content** first. A human still decides. Keeping such content is allowed only while
it serves the report to the authority (§ 184b(5) StGB), so its hold ends **when you record the handover**, and at the
latest **90 days after the report** — blocking it or sending the statement of reasons never keeps it longer. Extend it
only while your report to the authority is still in progress or because the authority asks, with that as the reason;
the handover still ends it. What an authority's order holds stays held for as long as the order says.

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
- **Child sexual abuse material: contact the police at once, and keep nothing beyond the hold.** Recording the
  handover in veyq ends the hold; without one it ends 90 days after the report. Whether keeping it longer, until the
  handover, is covered — and for a non-commercial operator whether even that bounded hold is (§ 184b(5) StGB) — is
  open, and no link for the police to a held copy exists.
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
([§ 7 DDG](https://www.gesetze-im-internet.de/ddg/__7.html)). [What you may owe](#what-you-may-owe) below is how you act
on your own answer.

## Transparency reports

> **Research, not legal advice** ([ADR 0093 § Legal basis, 8](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md#legal-basis)).

Three reports may fall on you as the instance's provider. **veyq compiles none of them:** you count from the cases on
the moderation page, their log and the orders you entered, and publish or send the result yourself.

| Report | Who owes it | What | By when |
|---|---|---|---|
| [DSA Art. 15](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32022R2065) | Every provider the DSA reaches ([which law](#which-law-reaches-your-instance)) **unless it is a micro or small enterprise**: fewer than 50 staff and a turnover or balance sheet of at most EUR 10 million, counting the enterprises it is linked to ([Recommendation 2003/361/EC](https://eur-lex.europa.eu/eli/reco/2003/361/oj)). A company or a large association running veyq for its people is likely reached. Whether a club or a household with no economic activity is an enterprise at all, and so covered by the exemption, is open ([ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md#still-open), Still open 8). | A public, machine-readable report on the orders authorities sent you, the notices (reports) you received and what you did, the moderation you did on your own initiative, and any automated means — on the templates of [Implementing Regulation 2024/2835](https://eur-lex.europa.eu/eli/reg_impl/2024/2835/oj) | Each calendar year, published within two months of its end (by the end of February) |
| [TCO Art. 7(2)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32021R0784) | Every host, **whatever its size**, for a calendar year in which it took action against terrorist content or was required to — a removal order is enough | A public report: your measures, how many items you removed or blocked after orders, orders you could not carry out and why, complaints, reviews and reinstatements (Art. 7(3)) | Before 1 March of the following year |
| [§ 3(2) TerrOIBG](https://www.gesetze-im-internet.de/terroibg/__3.html) | Every host under German jurisdiction | To the Bundesnetzagentur, not published: how many requests from authorities for access to content you preserved after a removal (TCO Art. 21(1)(c), Art. 6) | Each calendar year, by 1 March of the following year |

Whether a year with nothing to count still needs a report is not settled by the text; the Bundesnetzagentur answers
that for § 3(2). Each order you [enter](#an-authoritys-order) opens a case of its own that keeps its kind, when it
reached you and when it blocked — what the TCO counts ask for; its reference is sealed to the moderation key.

## What you may owe

There is no mode to choose ([uc.admin.operator-details](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.operator-details.md), [ADR 0093 § 14](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
Every instance serves one form of each fixed text — the operator section of the terms, the privacy notice, the
statement of reasons — and every value you can set under **Server → Operator** (or `veyq-admin operator set`) is
optional: an empty one reads *not stated by this operator* wherever a text names it, until you fill it in. Nothing
warns and nothing is refused over an empty value, so you can try the instance before you publish it. Reporting,
blocking and the moderators are on in every case: criminal law applies to every operator, and an operator who learns
of illegal content has to act.

What you owe is your own assessment, and first-run setup and the operator settings say so as information:

- The GDPR's household exemption covers only a purely personal or family activity, and it ends once content is
  reachable for an unrestricted number of people — a link shared publicly (CJEU *Lindqvist*, C-101/01; *Buivids*,
  C-345/17). Whether the Digital Services Act and the terrorist-content regulation reach an unpaid private instance is
  legally uncertain ([the table above](#which-law-reaches-your-instance)).
- If you share links publicly, or run veyq for a club, an organisation or a company, you are probably not exempt, and
  then owe at least a name and a postal address ([§ 5 DDG](https://www.gesetze-im-internet.de/ddg/__5.html),
  § 18(1) MStV) and the controller's contact details (Art. 13(1)(a) GDPR) — the provider identification and the
  instance contact.
- It covers every space on the instance: you are the host for all of them, and you cannot see whether a space's owner
  posted a link publicly, so a friend's space stays private only as long as its links do.

Once those values are set, veyq meets a hosting service's duties on your instance; the gaps that remain against the
law are named in [ADR 0035](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0035-server-terms-of-service.md)
decision 6. **This is your own assessment, not veyq's, and not legal advice.**

## What a guest's write keeps

When a guest of a link adds or names something — names itself, creates, renames or moves a folder or file, adds a
photo or a file — veyq keeps the guest's IP address, its source port and the time to the millisecond, with the guest
identity and what was written, for **N days** ([en.moderation.guest-write-record](https://github.com/raddatzk/veyq/blob/main/docs/features/moderation/enablers/en.moderation.guest-write-record.md), [ADR 0093 § 10](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
Nothing a member does and nothing anyone only looks at is kept, and never a User-Agent or content. It is evidence: if
what a guest added is reported, the report's hold copies these rows, and a moderator can hand them to an authority.
Everything else expires.

- **There is no switch for it.** The record is kept wherever a guest may write, so the capabilities that let guests
  write decide where: *Links to look and add* (`core.public-shares.contribute`) and *Drop boxes*
  (`core.public-shares.drop-box`), yours and each space's ([Capabilities](/veyq/run/capabilities/)). Switch those off
  and no guest writes, so nothing is kept. Kept rows go at the cleanup (hourly, and at every start) once older than N;
  what a report's hold copied stays for the hold's time.
- **N** is set under **Server → Moderation**, or with `veyq-admin guest-write-record set <days>`: 7 by default, 1 to
  30. Seven days is the supervisory practice; a longer time needs a reason you write into your record of processing. A
  shorter N reaches every kept row at the next cleanup, a longer one only rows written from then on, so no guest is
  kept longer than the page told them.
- **Guests are told** before their first write, on the page itself, with the N in effect and the right to object as a
  sentence of its own, and a link to the full information in [the privacy notice of the instance](#the-privacy-notice).
  You are the controller, not a space's owner.
- **The address is the one the network vouches for**, never one the client wrote ([Networking](/veyq/run/networking/)).
  On Compose it is strong by construction; on Kubernetes it is only as strong as an enforced NetworkPolicy. The source
  port is known where your edge states it (on Compose, the recipe's Traefik always does); otherwise only the address is
  kept, and an address without a port may name no single subscriber behind carrier-grade NAT.
- **Keep the host's clock on NTP.** A port is only worth something with an exact time: a carrier hands it to the next
  subscriber quickly, and a clock minutes off names the wrong one.
- **Backups.** `veyqctl backup` (the instance backup) dumps the table's schema and none of its rows, so a capture never
  keeps a guest past N. If you back up otherwise — volume snapshots, a dump of your own — those copies hold the rows:
  keep such a backup at most B days — the instance's own backups are deleted at B, 30 until you set it under **Server →
  Moderation → Backups** ([Backup and restore](/veyq/run/backup-and-restore/#how-long-a-backup-is-kept--b)) — open it
  only to restore it, and let the restored instance start once (its cleanup then deletes what is past N at boot). A
  guest's data can then live N + B days, and the privacy notice says so with your B.
- **A guest objects** (GDPR Art. 21(1)): unless you have compelling reasons to keep what was kept, delete it —
  `veyq-admin guest-write-record forget <identity-id>`. It deletes every kept write of that guest identity at once and
  prints how many went and how many a hold keeps: rows a report's or an authority's hold copied **stay until that hold
  ends**, because a report or an order concerns them — tell the guest so. Answer within a month (Art. 12(3)). A later
  write by the same identity is recorded again; a guest who does not want that can view the link but not add to it.
  The command takes the identity's id: a guest finds it on the link's page next to its name while writes are recorded
  (*Your guest id, to name if you object*, with a copy button), and the privacy notice asks a guest who objects to
  give it; a moderation case names it too.

## Who runs the instance

**Server → Operator** (or `veyq-admin operator set`) holds what a host publishes about itself; every field may stay
empty:

- **The instance contact** — the single point of contact for users and authorities, and for removal orders on
  terrorist content, with the **languages it answers in**;
- **the address statements of reasons name** — may be the same as the contact;
- **the provider identification** — **Name**, **Address for service**, **Email**, and for a club or a company who
  represents it;
- **the data-protection supervisory authority**;
- for [the privacy notice](#the-privacy-notice): the **recipients** — everyone who processes data for you, each with
  what they do and the country they do it in — and a **data-protection officer** where you have appointed one. What
  they do you pick from a list the notice words in each of its languages: *hosts the server*, *stores the encrypted
  files*, *relays the mail*, *keeps the backups*, or *other* with a short description of yours, which both language
  versions show as you typed it, in quotes. The country you pick from a list too, and each language version names it
  in its own language. The panel suggests a row of its kind for what the instance itself knows — an
  external store's host, the instance mail relay's host — which you complete with the provider's name and country and
  keep or drop; nothing is published until you save it as a row. With the CLI:
  `veyq-admin operator set --recipients 'Example Hosting GmbH|hosting|DE; Mail AG|mail-relay; SMS AG|other:sends sign-in texts'
  --data-protection-officer 'DSB GmbH, dsb@example.org'` (the kinds `hosting`, `object-storage`, `mail-relay`,
  `backup` and `other:<what they do>`; the country its two-letter ISO 3166-1 code; `--recipients` replaces the whole
  list, `none` empties it).

These are public: the app reads them before sign-in on every address of the instance, and shows the identification
and the contact with its languages, where they are set, behind **Legal notice** on the sign-in and share pages and at
the bottom of the signed-in app, beside **Terms of this instance** and **Privacy**; the recipients and the officer are
public only through the privacy notice. An empty field shows as *Not stated* in the panel, and nothing warns: what you
may owe is the panel's information above the values ([What you may owe](#what-you-may-owe)). The **notification address** is not public:
new moderation cases will be mailed there. Saving it mails a code to it through the instance mail, once Server → Mail
is set up and confirmed (the panel says so until then); you type the code into the same panel, and nothing is mailed
to the address before. A changed address asks again. **Clear all** removes every value.

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
review, each step and each lift, each proposal and what became of it, an extension and the end of the hold, the
receipt and the decision sent to a reporter, a handover: who and when, a measure's kind and target, and the notes and
reasons only sealed. A case closes when nothing of it is held any more and no objection is open. **The case goes
whole** — its log, its reports with what the reporter wrote and the keys they handed over, its measures, statements,
objections and orders — on 31 December of the third year after it closed, but **never while one of its blocks is in
force**: lifting it later counts from the lift. An undecided case stays open until a moderator decides it — no case
closes on its own: 30, 75 and 90 days after its first report the moderators and your confirmed notification address get
a reminder naming only its category and age, and the queue marks it overdue after 90 days. The
reporter's address goes earlier, six months after they were told the decision.

**What this does not protect against.** You own the server and the roster. A server that hands out a key of its own
choosing could read later reports; moderators' pages compare the key the server publishes with the one they hold and
stop when it differs, but a reporter cannot check it. Moderation keeps a team accountable to each other; it is not a
control against the operator.

## The terms of the instance

veyq ships a fixed **operator section** of the terms and puts it after every space's own terms
([ADR 0035](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0035-server-terms-of-service.md) decision 6):
members read it at the terms gate, guests at a link's terms gate, and anyone, without an account, under **Terms of this
instance** (`/operator-terms`, served by `GET /api/operator/terms`). Nobody edits its text, you included: it is yours in
law (you are the host), so a space's owner cannot delete it, and its wording is fixed so nothing a supervisor checks can
be dropped. You fill it in under **Server → Operator** above, and a changed value shows at once.

It has one form on every instance, and says:

- who runs the instance (your identification) — an empty field reads *not stated by this operator*;
- how to report, who reviews, that a report blocks nothing, what is kept and for how long;
- the measures, and that those affected are told why and can object once;
- the statement's further content: the facts, whether a report or an order led to it, that a person decided, the
  ground with the law or clause, where and how long it applies, the courts;
- that only an authority can defer the statement;
- authorities: reports of crimes against life or safety, orders, terrorist content blocked within the hour, your
  contact with its languages;
- a pointer to the guest-write record's information, which is [the privacy notice](#the-privacy-notice)'s.

The values it uses are the instance contact and its languages, the address statements name (else the contact) and the
provider identification.

**When members and guests are asked again.** A space's owner changing their terms, or a veyq release that changes the
section's text, asks every member and guest of that space again. Changing a value or N asks nobody.

**It is a draft.** No lawyer has reviewed the text yet, and every rendering starts by saying so. Before you publish an
instance, have it checked. A known question, also named in ADR 0035 decision 6: whether a space's own terms must be
public before sign-in.

## The privacy notice

veyq also ships a fixed **privacy notice** — the information GDPR Art. 13 asks of you, the controller, for everyone
whose data the instance processes: members, reporters and the guests of links
([ADR 0035](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0035-server-terms-of-service.md) decision 8).
Anyone reads it without an account under **Privacy** at the bottom of every page, at `/privacy` on every space's
address (served by `GET /api/operator/privacy-notice`); the invitation page, the report dialog's contact field and a
guest's first write link the passage that concerns them. Nobody edits its text, you included; nothing accepts it and
reading it records nothing.

It states what **this** instance does, from what it is set to, so keeping it true is keeping those settings true:

- **you**: the provider identification as the controller, the address statements name (else the contact) for data
  protection, a data-protection officer where set, the supervisory authority, and the recipients you named
  ([Who runs the instance](#who-runs-the-instance));
- **one passage per processing**, each with its purpose, legal basis and period: the account and its address, signing
  in and the one cookie, what the browser keeps, the terms a member accepted, reports and moderation, a reporter's
  contact, mail (yours, and a space's own relay as its owner's), where encrypted content is stored (an external store by
  its host), backups with your B, logs, and the account erasure with its window and what outlives it;
- **the guest-write record** with N and N + B and its own right to object, on every instance (it has no switch);
- **passages that follow a switch**, on the space's own address: the breached-password check naming the address it calls — a mirror you set
  is named, HIBP is not (*Breached-password check*), and the services a member's browser calls, each by its address
  (*Place names*, *Maps*; a space's owner can switch these off for their space);
- **the rights**, the complaint to the supervisory authority, and the right to object to everything resting on a
  legitimate interest, set apart.

An empty value reads *not stated by this operator* (the recipients' passage too, while you name none). It is shown on
every instance — a household may owe no notice, but every passage is still true of your instance.

**It is a draft too**, and says so on every rendering. Questions only counsel answers, and that the notice answers as
veyq is built until then: whether each space's owner is a joint controller with you for its members' accounts (the
notice names you alone), whether the breached-password check may run by default (the notice states it as it runs, its
recipient and its switch), whether one sentence plus this link meets Art. 13 on a guest's page, and what Art. 14 asks
towards a person a report names. The record of processing, your contracts with processors, a breach and an access
request are [Data protection](/veyq/run/data-protection/)'s.

## Not built

- **A child-sexual-abuse case's hold running until the handover past 90 days**: it waits on counsel (§ 184b(5) StGB,
  see [In an emergency](#in-an-emergency)); such a hold ends at the handover or after 90 days, whichever comes first.
- **Keeping a case's records longer while proceedings are pending**: there is no extension for the records yet, only
  for a hold.
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
