# ADR 0099 — Moderators share one instance moderation key, and review without a vault

- Status: Accepted
- Built: yes
- Date: 2026-10-17
- Model: [Moderation](../features/moderation/_index.md)

## Context

A report is sealed to a key that whoever reviews it must hold (ADR 0093 §§ 2–3). Sealed to the Server Owner's own
identity key, three things would follow:

- **Only the Server Owner could moderate.** A club or a company runs an instance (0093 § Context) and its operator is
  away sometimes; the TCO hour (0093 § 12) does not wait. A deputy would need the Server Owner's account.
- **Review would open the whole vault.** The identity private key opens every scope grant the account holds, and it
  would be unlocked on the page that renders reported, by definition suspect, content.
- **A measure would be reviewed on objection by whoever took it**, with nobody else to do it.

What there is to build on:

- **Roles.** An account's server role holds one value, `SERVER_OWNER` or `MEMBER`, surfaced as a role on the session;
  the Server-Owner gate answers only on the admin channel ([ADR 0065](0065-admin-ui-cli-separate-service.md)). A
  tenant owner is an `OWNER` membership in the tenant scope. There is no third account role.
- **A key wrapped to many accounts, with epochs and rotation, already exists** — the scope key: one grant per member
  (`sealTo` purpose `scope-grant` [scopeId, epoch]), wrapped by a key-holder to
  another member ([ADR 0020](0020-grant-distribution-invite-wrap.md)), checked against the recipient's pinned
  identity ([ADR 0028](0028-identity-authenticity-tofu-verification.md)), and rotated forward into a new epoch on a
  removal ([ADR 0024](0024-scope-key-rotation.md)). A moderation key is the same problem with fewer holders.
- **admin-web holds no vault** and is reachable on localhost by default (ADR 0065); it already does one piece of
  client-side crypto, the cold account birth at first-run setup.
- **Instance mail** reaches the Server Owner's address only, for instance notices only, and each new kind of notice
  needs its own decision ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
  decisions 3 and 6).

## Decision

### 1. A moderator is an existing account the Server Owner appoints, for the whole instance

- **Instance-wide only.** The duties of 0093 fall on the Server Owner as the host, and 0093 § 2 keeps a report from
  the tenant owner on purpose (they may be the uploader). A moderator therefore acts for the Server Owner, across
  every tenant, and is appointed only by them.
- **An existing account.** The Server Owner names one by its address in admin-web or `veyq-admin moderator appoint`.
  An account comes into being only through a tenant invite or setup, so a deputy without one is first invited into a
  tenant; a moderator invite that births an account with no tenant is out of scope.
- **The appointee accepts.** An appointment is pending until the appointee accepts it in src/web/'s moderation surface
  (decision 6), after a text that says what they will see (child sexual abuse material included) and that every
  opening is logged. Accepting is also what the first key delivery waits for (decision 3).
- **Removal** is the Server Owner's (admin-web, CLI) or the moderator's own (they step down in the moderation
  surface). Either rotates the key (decision 4).
- **What the server stores** — opaque, never a private key: a row per moderator (account id, appointed by, appointed
  at, accepted at, removed at), surfaced as a `MODERATOR` role on the session. Not a value of `server_role`: that
  column holds one role, and the Server Owner is a moderator too (decision 5). Per epoch (decision 2) the public
  bundle, and per moderator and epoch the sealed grant.

### 2. One instance moderation key, in epochs, granted to each moderator

- **The key.** Per epoch `n`, a 32-byte random `M_n`; from it, by HKDF with labels of their own, an X-Wing key
  (reports, objections and the log's free text are sealed to its public half) and an Ed25519 key (the proof below).
  The server publishes the bundle's public half per epoch; a reporter seals to the current one.
- **A grant per moderator and epoch:** `sealTo` purpose `moderation-grant` [epoch] to the moderator's identity public
  key, the shape of a scope grant. No chain link between epochs: rotations are rare, so a moderator simply holds a
  grant for every epoch still in use, and a holder delivering to a newcomer seals each of them.
- **Proof of the key.** 0093 § 1 serves a report share "only against proof of the moderation key". Every moderation
  call — fetching a report share, every decision — carries a signature with the epoch's Ed25519 key over a statement
  `moderation-proof` (server challenge, operation, case id), checked against the stored public half, **and** needs a
  session with the `MODERATOR` role. The role names who acted (the log's moderator account); the proof makes a stolen
  session without the key worth nothing.
- **Why one shared key, not one per moderator:** a reporter, mostly a guest with nothing pinned, seals once to one
  published key, and a report's size and sealing cost do not grow with the team; a new deputy reads the open cases at
  once, from the grants a holder delivers, where per-moderator sealing would leave every case before their
  appointment closed to them until someone re-sealed it; and the shape is the scope key's, so the suite, the delivery
  and the review of a changed identity are reused, not invented. What per-moderator keys would buy is no rotation on
  a removal — but a removed moderator keeps what they could already open in either design, and a rotation of a
  handful of grants is cheap. Separation of duties (decision 5) and attribution rest on the session, not on whose
  key opened a report, in both designs: against an honest server they are equal, against a malicious one neither
  holds.

### 3. The Server Owner mints in admin-web; a holder delivers in src/web/

- **Minting needs no vault.** admin-web draws `M_n`, seals it to every current moderator's identity public key,
  uploads the public half and the grants, and forgets it — the same class of work as the account birth it already
  does at setup. It never opens a grant. **Epoch 1 is minted by first-run setup itself**: the setup
  client draws `M_1` and seals it to the identity born in that same setup, and the server stores it with the Server
  Owner's account in one transaction — so reporting is on from the first day, with the Server Owner as the first
  moderator, and no operation turns moderation on later. Later epochs are minted on the admin channel as below. An
  instance without an epoch keeps the report action off, and admin-web says so.
- **A new moderator receives the key from a holder.** Once they accept, the next holder to open the moderation
  surface finds a pending delivery and their client seals every live epoch to the newcomer's identity — after the
  anchor check of ADR 0028 Decision 2, as a scope grant is: the delivery serves the newcomer's key-chain head beside
  their key, and the server serves one accepted moderator's key chain to another, because two moderators may share no
  tenant (the roster is the instance's, so they already know each other). A key the chain does not reach, or a retired
  key served beside a newer head, is not delivered to. admin-web cannot deliver: it does not hold `M`.
- **Moderators watch the published key.** A moderator's client compares the public half the server publishes with
  the one it derives from the `M_n` it opened, on every unlock, and refuses to go on on a mismatch. A reporter cannot
  check it (a guest has no pins), and need not beyond what [ADR 0026](0026-client-trust-tiers.md) already says: the
  reporter's client is operator-served, and the operator is the moderation authority, so a substituted key gains an
  active operator only what the served code already could.
- **A moderation grant is unsigned**, unlike a scope grant ([ADR 0028](0028-identity-authenticity-tofu-verification.md)
  decision 6): admin-web mints without a vault and so has no identity signing key. A server that substitutes a grant
  must also substitute the published bundle to read anything, which this section already grants an active operator,
  the moderation authority itself; the published-key comparison above is the check that matters.
- **Passkeys, once they exist,** add a wrap of `M` per moderator passkey, so the moderation surface opens the key
  without the identity at all. The identity grant stays as the delivery path.

### 4. Rotation: a new epoch for the remaining moderators; old reports stay sealed to their epoch

- **When:** a moderator is removed or steps down, a moderator's identity rotation
  ([ADR 0060](0060-key-rotation-after-compromise.md) — the grants that identity opened are treated as exposed), a
  suspected compromise, or by hand. admin-web (or `veyq-admin moderation rotate`, which carries the same suite) mints
  `M_{n+1}` for the remaining moderators (decision 3); reports from then on seal to it.
- **A holder who secures their account keeps the key.** Their grants sealed to the retired identity are deleted, and
  in the same publish their own client, which holds every `M_n` at that moment through the identity it retires,
  re-seals each live epoch to the new identity — staged before the publish as its self-grants are. The server takes a
  holder's publish only with every live epoch the account holds staged, re-sealed or declared, and reads that set under
  the current epoch's lock, so a delivery to them either is in it or meets the new identity. A sole holder — the Server
  Owner right after first-run setup — has nobody to deliver the key again, and without this the securing would lose
  every report and hold sealed to it. An epoch the device cannot open, or whose key the published bundle disowns, is
  declared: its grant goes, as it was already closed to them, and another holder delivers it at the new identity. The
  re-sealed grants are unsigned like every moderation grant (decision 3) and hand the key only to the identity the
  publish installs, so the stage's digest does not cover them.
- **The removed moderator's grants are deleted, for every epoch**, at once (layer 1: the role is gone with the row).
- **Old reports are not re-sealed.** Their epochs stay granted to the remaining moderators and are dropped when
  nothing sealed to them remains (the last case under them has passed the log's retention, 0093 § 11). Re-sealing
  open cases would cost a holder's pass over every case and protect nothing: the removed moderator already held
  `M_n`, and `K_report` inside the seal does not change. What rotation protects is every report after it — the
  forward-only property ADR 0024 states for scopes, stated here the same way.
- **The moderation log** (0093 § 11) gains instance events with no case id: appointed, accepted, delivered (epoch,
  by whom), removed or stepped down, epoch minted (by whom, and a reason category: removal, identity rotation,
  compromise, by hand). Their retention is 0093 § 11's.

### 5. A second moderator reviews an objection; the Server Owner moderates by default

- **The server refuses** a decision on an objection from the account that took the measure while another accepted
  moderator holds the key. The log records the reviewer.
- **With one moderator**, the objection does not wait: the decider reviews it, the log marks the review as by the
  decider, and the answer to the objector says so. Waiting for a second moderator who may never come would leave a
  measure unreviewed indefinitely; the courts stay open either way (0093 § 7).
- **The Server Owner is a moderator from first-run setup on**, and holds the key. They may step
  down while at least one other accepted moderator holds it: their grants go, the key rotates (decision 4), and they
  can reappoint themselves. A step-down or a removal that would leave no holder is refused. An erasure is not: when the
  last holder erases their own account, their client first delivers every live epoch to the Server Owner, who is
  reappointed by that delivery and needs no acceptance — an erasure may not wait on a third person
  ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 9). Erased at the
  Server Owner's door, where no client of the person is there to deliver, the key is lost after a warning, and with it
  what only it opens; admin-web shows an instance whose key one person alone holds. A company's IT admin who runs the instance should not have to see what Legal reviews,
  child sexual abuse material above all; it is a boundary of duty, not of security — the Server Owner owns the roster
  and the server — and the docs say so.
- **Against the operator this is accountability, not a control.** The Server Owner owns the server and the roster;
  the rule binds a team, not the host.

### 6. Review and decisions move to a moderation surface in src/web/

- **Review** stays the share viewer's moderation mode (0093 § 3), opened from the queue. The moderation surface
  signs the moderator in on the tenant host, unlocks the vault **only to open the moderation grants** — no member
  shell, no scope grant fetched — keeps `M` and drops the Master Key and the identity private key before any reported
  content is opened. The page that renders a report holds the moderation key and nothing of the vault.
- **Deciding** — the queue in clear fields, dismissing, the steps of 0093 § 6 within the moderator's reach, the
  statement of reasons and its deferral, objection decisions, hold extensions, the CSAM flow and the BKA export —
  is a moderator operation in the same surface, gated by `MODERATOR` plus the key proof, on the public listener.
- **Why src/web/ and not admin-web.** A deputy cannot reach admin-web: it is localhost-only, and its own-domain mode puts
  the whole Server-Owner API on the internet (ADR 0065 residual iv), which is worse than a narrow role on the public
  one. 0093 rejected moderation in src/web/ because it would put **Server-Owner** operations on the public listener; the
  moderator role carries none of them. What a stolen moderator session could do is bounded: nothing without the key
  (the proof); with it, read what was reported and take a reversible, logged step that another moderator reviews on
  objection.
- **Kept on the admin channel, for the Server Owner only:** the roster, minting and rotation, the instance contact,
  and the steps that reach beyond what was reported — blocking a member's sign-in, taking a tenant offline
  ([ADR 0066](0066-tenant-suspension.md)). A moderator blocks the share, the content, and a guest uploader with the
  link it came through (0093 § 6); the other two steps they propose: the case shows the proposal, and the Server
  Owner takes it. Those two steps reach content and people nobody reported, while blocking the share and the content
  already stops what was reported from being served, so a deputy keeps the TCO hour without them.
- **An authority's order** (0093 § 12) is entered in either surface: by a moderator with the key proof, or by the
  Server Owner in admin-web or `veyq-admin` without a key, as 0093 decided. Two operations on one service, because
  the admin-channel marker names a door per operation (ADR 0065).
- **Where the queue is served:** the moderation surface is a route of src/web/ on every tenant host, reachable only with
  the `MODERATOR` role and the key proof; its queue spans the instance, and a review opens on the reported tenant's
  host: no new host, route or certificate on Compose or Helm, a moderator signs in where they already do, and a
  tenant host serving the instance queue to a moderator shows them nothing they could not open anyway.
- **Limits, stated:** the surface is operator-served web, passive-operator-safe only (ADR 0026), which is moot
  against the operator, the moderation authority itself; and a script flaw on a tenant origin reaches what that
  origin stores for the moderator's account (a trusted-device wrap), so the surface never offers one and the docs
  advise a moderator not to keep one on the hosts where they review.

### 7. Every moderator is notified

- **A new case mails every accepted moderator** at their account address, in the form 0093 § 2 sets, and the
  instance contact's notification address where one is set, through the instance mail of ADR 0097 decision 3: these
  are the recipients of moderation notices, the Server Owner among the moderators.
- **An address receives notices once confirmed** with a one-time code, as ADR 0097 decision 5 does for the Server
  Owner; a moderator types it into the moderation surface, which they can reach. Accepting the appointment asks for
  it.

## Consequences

- **A deputy exists**, a report's review holds only the moderation key, and an objection is reviewed by a second
  person where there is one. Reporters, holds, measures and the log keep 0093's shape.
- **Costs.** The roster and the key operations on the admin channel; the grants, the challenge and every moderation
  operation behind the role and the proof on the public one; the bundle's derivation, the grant and the proof in the
  crypto suite with vectors; the roster, the epochs and the grants stored, the role on the session, the
  separation-of-duties refusal and the instance events; the moderation surface in src/web/; roster, mint, rotate and
  the Server-Owner-only steps in admin-web and `veyq-admin`; instance mail to more than one address.
- **Out of scope:** per-tenant moderators; a vote of several moderators on a measure; the passkey wrap until passkeys
  exist; moderator accounts with no tenant; any signal of a moderator's identity to the affected (statements name the
  host, not the person).
- **[ADR 0065](0065-admin-ui-cli-separate-service.md)'s split rule gains a fifth audience**, the moderator, whose
  gate is not the Server-Owner gate and so stays in src/web/.
- **The flows** are [uc.moderation.appoint-moderators](../features/moderation/use-cases/uc.moderation.appoint-moderators.md),
  [uc.moderation.serve-as-moderator](../features/moderation/use-cases/uc.moderation.serve-as-moderator.md),
  [uc.moderation.report](../features/moderation/use-cases/uc.moderation.report.md),
  [uc.moderation.review-a-case](../features/moderation/use-cases/uc.moderation.review-a-case.md) and
  [uc.moderation.take-a-measure](../features/moderation/use-cases/uc.moderation.take-a-measure.md); the Run docs'
  [moderation page](../../site/src/content/docs/run/moderation.md) describes them for the Server Owner.

## Rejected alternatives

- **Moderators per tenant, appointed by its owner.** The duties are the host's, the tenant owner may be the
  uploader, and 0093 § 2 keeps a report from them; a tenant's own team is a product question for later, with its own
  key and its own ADR.
- **A key per moderator, every report sealed to all.** Covered in decision 2: reports grow with the team and a new
  deputy cannot read open cases, for a removal property neither design keeps.
- **Grants opened in the viewer with the vault**, the moderator's identity key standing in for the moderation key.
  It unlocks the whole vault on the page that renders suspect content, for every moderator.
- **Deputies in admin-web**, by its own-domain mode or a shared tunnel. Exposes the whole Server-Owner API, or hands a
  deputy the operator's host.
- **Re-sealing open cases on a rotation.** Costs a pass over every case and protects nothing the removed moderator
  had not already opened (decision 4).
- **An epoch chain link** (ADR 0024's `symWrap(E_{n+1}, E_n)`). admin-web never holds `M_n`, so it could not write
  the link when it rotates, and a holder would have to later; per-epoch grants cost a few more wraps and need no
  second writer.
- **A server-held moderation key** under the config-secret KEK. Rejected by 0093 for the same reason: the server
  process would open every report.
- **Several moderators approving each measure.** Slows the TCO hour and the CSAM block for a check the objection
  already gives.
- **An objection that waits for a second moderator.** Leaves a measure unreviewed on every one-person instance.
- **The role as a value of `server_role`.** One column, one role; the Server Owner is both.
- **The Server Owner never steps down as a moderator**, since the host moderates: a company's IT admin who runs the
  instance would then have to see what Legal reviews. **Nor at any time**: with nobody else holding it, the key would
  be lost (decision 5).
- **Moderators also block a member's sign-in and take a tenant offline**, every step of 0093 § 6. Those reach content
  and people nobody reported (decision 6).
- **A moderation host the Server Owner sets**, its own route on both targets. A new host, route and certificate for
  what a route on every tenant host already serves (decision 6).
