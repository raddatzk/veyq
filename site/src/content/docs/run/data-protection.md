---
title: Data protection
description: The duties veyq cannot do for you as the controller — a breach notification, your record of processing, contracts with your providers, and an access request — with what an instance processes and where it keeps it.
sources:
  - docs/decisions/0092-logs-are-operational-and-name-no-person.md
  - docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md
  - docs/features/admin/use-cases/uc.admin.privacy-notice.md
  - docs/features/expenses/use-cases/uc.expenses.exchange-rates.md
  - docs/crosscutting/cc.privacy.retention.md
  - docs/crosscutting/legal-register.md
---

> **Research, not legal advice.** What follows names the duties of the GDPR an instance's operator carries and what
> veyq processes for them to fulfil them. Whether and how they reach your instance — a household may be outside the
> GDPR ([which law](/run/moderation/#which-law-reaches-your-instance)) — is your own assessment, and a qualified
> review is the place to settle it.

You, the Server Owner, are the **controller** of what your instance processes: you decide to run it, for whom and
with which providers ([ADR 0093 § 10](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
On an instance with several space-groups, whether each group's owner is a joint controller with you for its
members' accounts (Art. 26) is open
([ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md#still-open), Still open 6); veyq does not pursue the question and treats you as the controller.
What members keep — photos, names, notes — is end-to-end encrypted: the server stores it as ciphertext it cannot open,
and so do you. What follows is the rest: what the server needs in the clear to run, and what leaves it.

What you owe the people themselves when their data is collected (Art. 13) veyq writes for you: the instance's **privacy
notice**, public at `/privacy` on every space's address and linked wherever data is collected, states each processing
below from what your instance is set to, filled with your details and the recipients you name
([The privacy notice](/run/moderation/#the-privacy-notice)). Keep its tables here and your settings there in step:
a recipient you add to your record belongs in the notice too.

## A data breach

If someone gets at your server, its database, a backup or a provider's copy of them:

- **Tell your supervisory authority within 72 hours** of becoming aware of it (GDPR Art. 33), unless the breach is
  unlikely to result in a risk to people — say what happened, which data and how many people, the likely
  consequences and what you did. A report may come in steps; a late one says why it is late.
- **Tell the people affected without undue delay** when the risk to them is high (Art. 34) — for example when the
  email addresses and the guest-write record's IP addresses leaked. Content that left only as ciphertext, with no key
  beside it, is what Art. 34(3)(a) calls unintelligible; the clear data below is not.
- **Write every breach down**, reported or not: the facts, the effects and what you did (Art. 33(5)).

What a thief reads in the clear is the clear part of the table below; what they read when they also hold the
config-secret key is the sealed SMTP and store passwords on top
([Hardening](/run/hardening/#keep-the-config-secret-key-apart)). Members' content stays ciphertext either way;
replace the server's secrets and have members change their passwords if you cannot rule out that a client was
tampered with.

## Your record of processing

Art. 30 asks for a record of every processing; an instance's rows are below — copy them into your own record and fill
in your basis and your recipients. Every row names where veyq keeps the data, so you can check it.

| Processing | Data, and where | Purpose | Kept |
|---|---|---|---|
| Content | members' Memories, files and Pots, their names and every file **sealed** on the device (`veyq_node`, its records and the store's objects) | what members keep and share | until a member deletes it — the trash keeps it 30 days (`veyq.node.trash.retention`) — or its space, its account or its tenant is erased; an application left switched off in a tenant is erased there on a day its members are told, 30 days after it went off (`veyq.application.off.retention`, [Capabilities](/run/capabilities/#what-acts-at-once-and-what-only-on-new-data)) |
| Accounts | the email address in the clear, whether it was confirmed, the username of each space-group, public keys; the name and the profile picture **sealed** (`veyq_user`, `veyq_tenant_profile`) | an account, its sign-in and its mail | until the account is erased — by its member in the settings or by you (`veyq-admin account erase`): sign-in stops at once, and 14 days later (`veyq.account.erasure.window`) the row and everything that names it go ([Accounts](/run/first-run/#accounts)) |
| Sign-in | session records with their times — **no IP address, no User-Agent** (`veyq_session_family` and its tokens); a sign-in challenge naming the address for five minutes | staying signed in, ending sessions | a token 15 minutes (access) or 30 days (refresh), then purged hourly |
| Cookies | `veyq_refresh` (30 days) or `veyq_refresh_session` (until the browser closes): HttpOnly, SameSite=Strict, sent only to `/api/token/refresh` | keeping a member signed in, which they chose | as stated; signing out clears both |
| Address mails | the address a confirmation, an address change, a recovery or an invitation goes to (`veyq_email_confirmation`, `veyq_email_change`, `veyq_account_recovery`, `veyq_tenant_invite`) | proving an address, recovering access, inviting | 24 hours valid, then 7 days (recovery 1 hour, then 7 days; an invitation 7 days, then 30) |
| Terms accepted | account, space-group, revision and time (`veyq_tenant_terms_acceptance`) | evidence of which terms a member agreed to | as long as the account and the space-group; erasing either deletes them, unless moderation records still kept name the account — a case, also one already closed, or a step (taking a space-group offline or deleting it over content names its owners) — then until those records go |
| Guest-write record | a link guest's IP address, source port, time and the `Origin` its browser sent, its guest identity and what it wrote (`veyq_guest_write_record`) | tracing the misuse of a link once it is reported (Art. 6(1)(f)) | N days, 7 by default — [What a guest's write keeps](/run/moderation/#what-a-guests-write-keeps); kept wherever a guest may write |
| Moderation | reports (category, time, the reporter's account or link in the clear; reason, contact and context **sealed** to the moderation key), cases, holds (copies of the reported rows, guest-write rows with IP addresses included), the moderation log, statements, objections, authority orders, blocks (`veyq_moderation_*`, `veyq_hold*`, `veyq_authority_order`) | the DSA's and the TCO Regulation's notice-and-action duties (Art. 6(1)(c)) and defending against misuse | a hold by its own end; a case whole — reports, measures, statements, objections, orders, its log — on 31 December of the third year after it closed, never while one of its blocks is in force ([Moderation](/run/moderation/#moderators)) |
| A reporter's contact | the address a receipt and the decision go to: mailed from memory, kept only **sealed** to the moderation key, apart from the rest of the report | telling a reporter (DSA Art. 16(4) and (5)) | six months after the reporter was told the decision |
| Moderators | the appointed accounts and each one's confirmed notification address (`veyq_moderator`) | telling moderators of a new case | as long as the appointment; the row stays after a removal |
| Your own details | the instance contact, the provider identification, the supervisory authority, a data-protection officer, your notification address (`veyq_operator_details`, `veyq_instance_mail_settings`), and the recipients the privacy notice names, each a name, a kind and a country code (`veyq_operator_recipient`) | what a host publishes, and its own mail | until you change them |
| Logs | pseudonymous ids (account, tenant, scope, share) and no person: no address, no User-Agent, no email address, no request's URI or text; the admin console's access log keeps no reader's address | operating and securing the service | by size, not time: the period's criterion is the rotation size and a container's recreation, which every upgrade does — [Operations](/run/operations/#logs) |
| Rate-limit buckets | the client address, and for recovery and invitation mails the email address, as a bucket's key — in memory only | defending against abuse | until the bucket has refilled after its last request, at most a minute more; gone at a restart |
| Backups | everything in the database except the guest-write record's rows, plus the store's index | restoring the instance | at most B days, 30 until you set it; a restore erases again what was erased after the capture ([Backup and restore](/run/backup-and-restore/)); a backup made otherwise holds the record too, and you keep it no longer than B |

**What leaves the server, and to whom:**

| Recipient | What it receives | Turned off by | In the privacy notice |
|---|---|---|---|
| Your mail provider (SMTP) | each mail's recipient address, the space-group's or instance's sender address, and its text — an invitation's link, a code, a notice, a statement without its facts | not setting mail up; then nothing is mailed | a recipient you name, kind *relays the mail* |
| Your object store (S3) | ciphertext only, under random keys — but **members' and guests' browsers fetch from it directly**, so the store's provider sees their IP addresses and request headers | — (the bundled store keeps it on your host) | an external one: a recipient you name, kind *stores the encrypted files* |
| GitHub | your server's IP address, once a day, for the release list ([Operations](/run/operations/#new-releases)) | the release check's own switch | not named |
| Let's Encrypt | your ACME email and every domain the instance serves (the domains also appear in public certificate logs) | your own certificates (Kubernetes: no `gateway.tls.acme`) | not named |
| The breached-password service (default `api.pwnedpasswords.com`) | from the **browser**: the first five characters of a new password's SHA-1, and the browser's IP address | the capability `core.breached-password` | named by the notice itself, by the address it calls |
| The exchange-rate service (Expenses; default Frankfurter, `api.frankfurter.dev`, serving the ECB's euro reference rates) | from the **browser**: the device's date, at most once a day, with the browser's IP address and, in its `Origin` header, your instance's address — no currency, no amount | `expenses.exchange-rates` (yours, or a tenant owner's for their space) | a passage of its own (`exchange-rates`), naming the address it calls and the ECB as the rates' source |
| The place-name service and the map tiles (Memories) | from the **browser**: a place's coordinates and the language; the map area viewed; the browser's IP address | `memories.places`, `memories.map` | named by the notice itself, by the address each calls |

A recipient you name takes one of five kinds, which the notice words in its own language on each version: *hosts the
server*, *stores the encrypted files*, *relays the mail*, *keeps the backups*, or *other* — the one that takes a short
description of yours (at most 120 characters), shown as you typed it and in quotes on both the English and the German
version. The country you pick from a list, and the notice names it in each version's language — *Deutschland* on the
German version, *Germany* on the English one; with the CLI you give its two-letter ISO 3166-1 code (`DE`). Your host and
wherever your backups are kept belong in the list too ([below](#contracts-with-your-providers)).

The browser-side services receive data from members' browsers, not from your server, but you chose them: the privacy
notice names each by the address you set while it is on, or switch them off ([Capabilities](/run/capabilities/)).

## Contracts with your providers

A provider that processes personal data **on your behalf** is a processor, and you need a contract with it that meets
GDPR Art. 28 — most hosting, storage and mail providers offer a standard data processing agreement. On a veyq
instance that is usually:

- **the host** of your server or cluster (a VPS, a dedicated server, a managed Kubernetes) — it can reach everything
  on the machine, the clear data above included;
- **an external S3 store**, if you use one instead of the bundled store — ciphertext, and your users' IP addresses
  as they fetch from it;
- **the SMTP relay** — every mail's recipient and text;
- **a log shipper, monitoring or backup storage** run by someone else — what you send it.

Not processors of yours: the services members' browsers reach on their own (above), GitHub's release list, and Let's
Encrypt; you still name them where they apply.

## An access request

Anyone may ask what you hold about them (Art. 15), and you answer within a month (Art. 12(3)). Content is not
yours to hand over: it is end-to-end encrypted, and the member reads and downloads it in the app. What you can hand
over is the server-side metadata — find it by the person's account id, which `veyq_user` names next to the email
address:

```sh
cd /opt/veyq
release=<running release>               # state.json's "release"
recipe="releases/${release}/docker-compose.prod.yml"
docker compose -f "$recipe" --env-file .env exec mariadb \
  mariadb -u root -p -D veyq -e "SELECT id, email, email_confirmed_at, server_role FROM veyq_user WHERE email = 'someone@example.org'"

# Kubernetes, with the bundled database:
kubectl -n veyq exec -it veyq-mariadb-0 -- mariadb -u root -p -D veyq
```

Then, for that id:

- **the account**: `veyq_user`, `veyq_tenant_profile` (the username; the name is sealed), `veyq_membership`
  (`user_id`) — which spaces, since when;
- **sign-in**: `veyq_session_family` (`user_id`: when each session started and was last used);
- **address mails**: `veyq_email_confirmation`, `veyq_email_change`, `veyq_account_recovery` (`user_id`), and
  `veyq_tenant_invite` by the address (`owner_email`);
- **terms**: `veyq_tenant_terms_acceptance` (`user_id`);
- **moderation**: `veyq_moderation_report` (`reporter_account`), `veyq_moderation_objection` (`objector_account`),
  `veyq_moderation_block` (`target_id`), `veyq_moderation_statement_recipient` (`recipient_id`),
  `veyq_moderation_event` (`actor_id`, `target_id`), and `veyq_moderator` (`account_id`) for a moderator — the clear
  fields; the sealed ones are ciphertext you cannot open;
- **who edited what**: the editor and uploader columns of `veyq_node`, `veyq_node_attachment` and `veyq_media`
  (`*_editor_user_id`, `uploader_user_id`, `created_by`) name the account, beside sealed content.

**A link's guest** has no account. Its rows hang on its guest identity's id: `veyq_guest_write_record`
(`share_identity_id`) and, for what a hold copied, the rows of `veyq_hold_row` whose `source_table` is
`veyq_guest_write_record` and whose payload names that id. While writes are recorded, the link's page shows the guest
its id beside its name, to copy, and the privacy notice asks a guest who objects to give it.

**An objection to the guest-write record** (Art. 21) is answered with `veyq-admin guest-write-record forget
<identity-id>` — [What a guest's write keeps](/run/moderation/#what-a-guests-write-keeps).

## How far this is proven

Every table, column, cookie and retention named here was read from the migrations and the code of this release. The
guest-write record's objection is covered by the backend's slice tests and the CLI's tests; nothing else on this page
is an action veyq performs.
