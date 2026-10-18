---
title: "Moderate an instance"
sources:
  - docs/features/moderation/use-cases/uc.moderation.serve-as-moderator.md
  - docs/features/moderation/enablers/en.moderation.log.md
  - docs/features/moderation/use-cases/uc.moderation.review-a-case.md
  - docs/features/moderation/use-cases/uc.moderation.take-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md
  - docs/features/moderation/use-cases/uc.moderation.authority-order.md
  - docs/features/moderation/use-cases/uc.moderation.report-to-an-authority.md
---

The person who runs a veyq server can ask you to **moderate** it: to look at what people report, in every space on
that server, and to decide what happens with it ([Report something](/veyq/help/report-something/)). Reports arrive
sealed to the key you hold, what they name is kept for you, and you are mailed when a new case comes in. First you
take up the role, so that you hold that key; then you review cases and decide.

## Accept

1. Sign in on the address of a space you belong to, and open `/moderation` there, for example
   `https://photos.example.com/moderation`. The page signs you in itself.
2. If you were appointed, the page says what moderating means:
   - you will see what was reported, and that can include child sexual abuse material;
   - every time you open a report, the **moderation log** records it with your account.
3. Choose **Accept and moderate**. If you were not appointed, the page says so and offers nothing.

## Confirm your address

Accepting sends a code to your account's email address. Enter it under **Code** and choose **Confirm address**.
Notices about new cases reach you only at a confirmed address. If the code did not arrive or no longer works, choose
**Send a new code**. When you change your account's address, you confirm the new one the same way.

## Open the moderation key

1. On `/moderation`, enter your password and your Secret Key, and choose **Open the moderation key**. The page asks
   for both every time: a trusted device does not open moderation.
2. The page opens only the moderation key. Nothing else of your account stays open on this page, so what you will
   look at there cannot reach your own spaces.
3. If you are new, you do not hold the key yet. It reaches you when a moderator who holds it next opens this page; the
   page says so.
4. If you hold the key and someone else is waiting for it, your page passes it on, after checking that person's key
   is the one you have seen before. If their account key changed, it is not passed on, and the page says so: compare
   their safety number first ([Verify a person](/veyq/account/verify-a-person/)).
5. Then the page shows **Reports**, the cases of every space on the server.

If the page says it **stopped** because the key the server publishes is not the one you hold, do not go on, and tell
the person who runs the server.

## Review a case

1. **Reports** lists each case with its category, what kind of item it is, when it was reported and how often, the
   address it was reported on, its state and until when it is kept. The urgent categories come first. **Show closed cases** shows
   the closed ones.
2. Choose **Open** on a case. You see each report — its reason, the contact the reporter left and the context, opened on
   your device with the moderation key — and the case's measures, its proposals, its hold and its log. The contact is
   deleted six months after the reporter was told the decision.
3. Choose **Review what was reported**. It opens on the address of the reported space, where you open the moderation
   key again. You then see what was reported as a guest of a link would see it, read-only, with the reports beside it:
   as it was when it was reported, even if it was changed or deleted since, and even if it is blocked now. The log
   records that you opened it.

A report sealed to a key you do not hold says it cannot be opened. If no report of a case opens with the keys you
hold, what it reported stays closed too.

## Decide

Under **Decide**, write a **Note** if you want one — it goes with your next step, sealed so that only moderators can
read it — and choose a step. Each says what it reaches before you confirm it.

- **Dismiss the case** — the report was unfounded. The case closes, and what was kept for it is released at once.
- **Block link** — every guest of that link then sees a link that does not exist. Its creator can neither re-activate
  nor renew it while the block stands.
- **Block the content** — nobody can open it any more, members included. Everyone sees **Blocked by the operator** in
  its place. Nothing is deleted.
- **Block guest uploader** — the guest can add nothing more, and the link they came through is blocked with them.
- **Bar this reporter** — further reports from that member, or through that link, are refused. The reports already
  made stay.
- **Propose** blocking a member's sign-in, or taking everything on the reported address offline. Only the person who runs the
  server takes these steps; the case shows your proposal and what became of it.
- **Extend the hold**, while something is kept: choose a **New end**, after the current one and at most six months from
  now, the **Kind of reason** — an authority's request or order, a report to an authority in progress, an objection, a
  lawyer's letter or a lawsuit — and give a **Reason**. The reason is required, and sealed like a note; its kind is
  not.

A case nobody decides never closes on its own: 30, 75 and 90 days after its first report every moderator is mailed a
reminder that names only its category and age, and after 90 days the list of cases marks it **Undecided for over 90
days**.

A block goes with a **Statement of reasons** to the people it affects. Choose the **Ground**, name **The law or the
clause it relies on** — a short reference such as "§ 86a StGB" or "Space terms, clause 4", shown as you write it — and
write **What you found**, which says why the content falls under it: on most servers both are required; on a private one
they are optional. What you found reaches each of them sealed, so only they can read it — and the moderation log, sealed
like a note — never a mail. The statement names the server, never you, and says the block applies wherever the server is
reached until it is lifted. A reporter who left an address is told the decision, and what they can do next.

A block decides the case, and what was kept for it is then kept for six months. **Lift** on a measure — yours or
another moderator's — serves what it blocked again, unchanged. Lifting the last measure in force releases what was kept
and closes the case.

## Objections

Someone affected by a block can object once. The case then shows the objection under **Objections**, opened for you,
and is back in the list of cases, marked **Objection waiting**. Choose **Uphold the measure** or **Lift the measure**.
If you took the measure yourself and another moderator holds the key, the case says that another moderator must
decide; if you are the only moderator, you decide it yourself, and the answer says so.

## An authority

**An authority's order.** Above the list of cases, **Enter an authority's order** opens a form: the **Kind of order**,
the **Link or link id the order names** — paste it as the order names it; only its id leaves your device — **The
authority's reference**, sealed so that only moderators can read it, and when it was **Received at the contact point**.
A removal order blocks the link and everything it gives access to at once, for everyone, after you confirm; you then
see when, to the second, and the text for the authority's feedback form to **Copy**. If no such link exists here,
nothing is blocked and you get a draft saying so. Every order is kept with its case, and goes with it (below);
**Orders recorded** lists them.

**Telling the affected later.** Only an authority can ask that the affected not be told yet. Then, under **Decide**,
tick **An authority asked that the affected not be told yet**, choose **Tell them on** — at most six weeks ahead — and
enter the authority's request. The statement waits until that day, and the list of cases reminds you of it. **Renew
the deferral** takes the authority's renewal once.

**Reporting to an authority.** In a case, **Prepare the fields for the report** shows what the server holds about it —
the case, its category, every time in UTC, the measures, who uploaded it and, for a guest, the address and time kept —
as text to **Copy** into the authority's form. Never download, forward or take a picture of what was reported.
**Record that it was reported to an authority** keeps that, with the authority's reference, in the case's log — so
copy the fields first: for child sexual abuse material it also ends what is kept of the case.

**Child sexual abuse material.** Before such a case opens, a full page says what never to do and what to do first.
**Back to the queue** leaves it closed; **I understand — open the case** opens it, with **Block the content** offered
as the first step. Such content is kept only until you record that it was reported to an authority, and never more than
90 days after the report — blocking it does not keep it longer. Extend it only while your report to the authority is
still in progress, or because the authority asks: only those two kinds of reason are offered, and the server refuses any
other.

## What is logged, and for how long

The moderation log records when you were appointed and accepted, each time you pass the key on, and when you step
down: who, when and for whom, never a key and never content. It is kept until 31 December of the third year after you
stop moderating, then deleted. In a case it records each time you open it for review, and each step and lift with who
took it and when; your notes and reasons only sealed. A case — its log, its reports and everything decided in it — is
kept until 31 December of the third year after the case closed, then deleted, but never while one of its blocks is
still in force. A reporter's address goes six months after they were told the decision.

## Step down

Choose **Step down as moderator**. Your role and your copy of the key end at once. You cannot step down while you are
the only moderator who holds the key; another moderator must hold it first. Someone who does not hold the key yet
asks the person who runs the server to remove them.
