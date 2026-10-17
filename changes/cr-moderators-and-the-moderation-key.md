---
id: cr-moderators-and-the-moderation-key
title: Moderators the Server Owner appoints, one instance moderation key in epochs, and the moderation surface's shell
status: planned
satisfies: []            # phase 0 writes the use-cases and enablers; see § Use-cases
adrs: ["0099", "0093", "0097", "0065", "0020", "0024", "0026", "0028", "0060", "0072", "0051"]
invariants:
  - operator-blind E2EE (the server stores the bundle's public half and sealed grants, never M_n or a private key)
  - admin-web holds no vault (it mints and forgets; it never opens a grant)
  - the moderation surface holds the moderation key and nothing of the vault once reported content could open
  - self-hostable by default (one-person instances: the Server Owner is the only moderator and holds the key)
---

## Goal

Build who moderates, as [ADR 0099](../docs/decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
decides it, before anything is reported: the Server Owner turns moderation on (epoch 1, sealed to their own identity),
appoints existing accounts by address, removes them or lets them step down, and rotates the **instance moderation key**
in admin-web or `veyq-admin`; an appointee accepts in a new **moderation surface** in `web/`, confirms their address by
a mailed code, receives every live epoch from a holder, and can step down. The server learns a `MODERATOR` role, checks
a **key proof** (an Ed25519 signature with the epoch's key over a server challenge) on every moderation call, and keeps
the **moderation log**'s instance events with their retention. Reports, the queue's content and every decision come
later (`cr-report-and-hold`, `cr-moderation-review-and-measures`); this CR ends with an empty queue that only a
moderator holding the key can open.

## Context & constraints

- Decisions: ADR 0099 §§ 1–5, § 6's first bullet (the surface unlocks only to open the grants) and its limits bullet,
  § 7 (moderators notified, address confirmed by code); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 11 (the log, append-only, structured fields clear, free text sealed; the instance events and their retention);
  [ADR 0097](../docs/decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3 and 5
  (instance mail to accepted moderators; their code typed into the moderation surface);
  [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) lines 91–96 (the moderator as the fifth audience:
  roster and key on the admin channel, the surface in `web/`).
- The patterns this reuses, never re-invents: the scope grant ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md),
  epochs and forward-only rotation [ADR 0024](../docs/decisions/0024-scope-key-rotation.md)), the pinned-identity
  check before sealing to a foreign identity ([ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md)),
  the typed seal ([ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md)), the server-rebuilt signed statement
  (`SecretKeyProof`), the login challenge (single use, short TTL).
- Every new endpoint declares its rate-limit posture ([ADR 0051](../docs/decisions/0051-declarative-rate-limit-posture.md)).
- **Parallel sibling:** `cr-instance-contact-and-provider-identification` (planned) adds the instance contact's
  notification address; the new-case mail that reaches it is `cr-report-and-hold`'s, not this CR's. No file overlap
  except `admin-web/src/views/AdminConsoleView.vue` (each adds one panel entry: the second to land rebases).

### Measured substrate (worktree at `61aa7756`, clean)

| Fact | Locator |
|---|---|
| One role is surfaced, only `SERVER_OWNER`; `MEMBER` is never a role | `grep -n 'addRole' backend/core/src/main/kotlin/me/raddatz/veyq/core/auth/SessionTokenIdentityProvider.kt` |
| Roles are gated by `@RolesAllowed`, unannotated endpoints denied | `grep -rn 'RolesAllowed' backend/core/src/main/kotlin`; `grep -n deny-unannotated backend/core/src/main/resources/application.properties` |
| The admin-channel sweep keys on `SERVER_OWNER` only (a moderator operation must not carry the marker) | `grep -n 'SERVER_OWNER' backend/src/test/kotlin/me/raddatz/veyq/core/adminchannel/AdminChannelPostureSweepTest.kt` |
| No role reaches `web/` today: the surface needs a read of its own | `grep -rn 'serverRole\|SERVER_OWNER' web/src contracts/api` (only `web/src/test/e2eBootstrap.ts`) |
| The server-side statement verifier to copy (length-prefixed fields, pinned by a vector both stacks read) | `grep -n 'fun statement\|fun verify\|domain' backend/core/src/main/kotlin/me/raddatz/veyq/core/crypto/SecretKeyProof.kt` |
| The challenge pattern (32 random bytes, 5-minute TTL, deleted on use under lock, purged by `SessionPurge`) | `grep -n 'CHALLENGE_TTL\|PESSIMISTIC\|fun challenge\|fun verify' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/LoginHandler.kt` |
| `sealTo` purposes, one table, every purpose owes a vector both ways | `grep -n 'SEAL_TO_PURPOSES\|STATEMENT_PURPOSES' packages/crypto/src/seal.ts`; `grep -n 'every purpose in the union has a vector\|every vector names a live purpose' packages/crypto/src/v1.conformance.test.ts` |
| `identitySign`/`identityVerify` are typed to the `grant` statement only | `grep -n 'export const identitySign\|export const identityVerify' packages/crypto/src/v1.ts` |
| Seed → Ed25519 and seed → X-Wing exist (`deriveSecretKeyProofKeypair`, `kemPublic`, the drop key) | `grep -n 'deriveSecretKeyProofKeypair\|export const kemPublic\|generateDropKey' packages/crypto/src/v1.ts packages/crypto/src/primitives.ts` |
| Grant delivery by a key-holder after the pin check (the pattern for a newcomer's delivery) | `grep -n 'checkAndPin\|declineJob\|sealGrant' web/src/features/tasks/grantDeliveryWorker.ts` |
| The vault and its zeroing | `grep -n 'export function\|fill(0)' web/src/app/vault.ts`; the identity open: `grep -n 'identity-priv' web/src/features/account/unlock/unlock.ts` |
| The routes are one static array; applications register none | `grep -n 'path:' web/src/router.ts` |
| admin-web's console panels and its four clients | `grep -n 'serverPanel' admin-web/src/views/AdminConsoleView.vue`; `grep -o 'contracts/api/[a-z_/]*\.openapi\.yaml' admin-web/package.json` |
| Instance mail: code confirmation and the sender | `grep -n 'object InstanceMailConfirmation\|CODE_TTL\|MAX_ATTEMPTS' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/InstanceMailSettingsHandler.kt`; `grep -n 'fun sendCode\|fun mailPending' backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/instance/InstanceNoticeMailer.kt` |
| Scheduled purges run at their first trigger, so "also at startup" is a `@Scheduled(every=…)` | `sed -n 108,116p backend/core/src/main/kotlin/me/raddatz/veyq/core/config/VeyqConfig.kt`; `grep -n '@Scheduled' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/login/SessionPurge.kt` |
| Identity rotation sets a new identity public key (moderation grants of the old one are exposed, ADR 0099 § 4) | `grep -n 'identityPublicKey' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/RotationStore.kt` |
| The pre-release baseline rule (`release: none`: a new migration file, pinned) | `grep -n '^release:' scripts/applied_migrations.sha256` |

## Decisions this CR takes (each with the line that settles it)

1. **Tables** (core, one new migration): `veyq_moderator` (account id, appointed by, appointed at, accepted at, removed
   at, address confirmation columns as `veyq_instance_mail_settings` has them), `veyq_moderation_epoch` (epoch, X-Wing
   public, Ed25519 public, minted by, minted at, reason category), `veyq_moderation_grant` (moderator, epoch, sealed
   grant, delivered by), `veyq_moderation_challenge`, `veyq_moderation_event` (the log: event, time, moderator account,
   case id nullable, ids, sealed free text, `expires_at` once computable). ADR 0099 § 1 "What the server stores" and
   ADR 0093 § 11 name every column's reason. A case id stays null in this CR.
2. **The `MODERATOR` role** is added in `SessionTokenIdentityProvider` for an account with an accepted, unremoved
   `veyq_moderator` row (ADR 0099 § 1 "surfaced as a `MODERATOR` role", § 6 "the role names who acted"). Not a value of
   `server_role` (ADR 0099 § Rejected).
3. **The key proof** is a statement `moderation-proof` (challenge, operation id, case id or empty) signed with the
   **current** epoch's Ed25519 key, rebuilt and verified by the server as `SecretKeyProof` does, and pinned by a new
   vector in `contracts/crypto/vectors/v1.json`. One gate, `ModerationProofGate`, used by every moderation operation
   of this and the later CRs. Current epoch only: admin-web mints every rotation for all remaining moderators at once
   (ADR 0099 § 3), so every accepted holder holds it.
4. **The bundle derivation** is `HKDF(M_n, "", "veyq/v1/moderation/kem")` → X-Wing seed and
   `HKDF(M_n, "", "veyq/v1/moderation/sign")` → Ed25519 seed (ADR 0099 § 2 "by HKDF with labels of their own"; the
   label text is this CR's, pinned by its vector). The grant is `sealTo` purpose `moderation-grant` [epoch].
5. **Server-Owner operations go on `instance.openapi.yaml`** under a new tag `instance-moderation`, admin-channel marked:
   turn moderation on, list the roster with each moderator's identity public key (what admin-web seals to), appoint by
   address, remove, mint an epoch (bundle + one grant per current moderator, refused unless it covers every one).
   admin-web keeps four clients. **Moderator operations** go into a new core spec `moderation.openapi.yaml` on the
   public listener: my appointment (read, accept, step down), confirm my address, my grants, pending deliveries and
   deliver, a challenge; plus one unauthenticated read of the current epoch's public bundle (for every reporter,
   `cr-report-and-hold`). New tags, so the contracts phase ends green on the backend.
6. **Removal by the Server Owner is one client step: remove, then mint** for the remaining (admin-web and CLI run both;
   the server records the removal and deletes the leaver's grants at once, ADR 0099 § 4 "layer 1"). A self step-down
   is Q3.
7. **The surface's unlock** opens the vault, opens every moderation grant, compares each epoch's derived public half
   with the published one and refuses on a mismatch (ADR 0099 § 3), then zeroes the Master Key and the identity private
   key (`vault.ts` `clearVault`) before the queue renders. It fetches no scope grant and mounts no member shell
   (ADR 0099 § 6). Delivering to a newcomer needs the identity private key only for the pin check's signing context —
   if it does, the delivery runs **before** the vault is dropped, in the same unlock (phase 6 measures which).

## Open questions (owner)

- **Q2 — May an existing device wrap on the tenant host unlock the moderation surface?** ADR 0099 § 6 says the surface
  never *offers* a trusted device there, and that a script flaw on a tenant origin reaches what that origin stores.
  - (a) No: the surface asks for the password and the Secret Key on every unlock, whatever the device holds, until
    passkeys bring their own wrap of `M` (ADR 0099 § 3).
  - (b) Yes, where the account already trusts this device for member use.
  - **Recommendation: (a).** A wrap that unlocks the vault on that origin is exactly the residual § 6 states; reusing it
    for the moderation key widens what a flaw reaches from one account's content to every report. The cost is typing
    two secrets per review session, which a moderator does rarely.
- **Q3 — A moderator steps down: who rotates?** ADR 0099 § 1 says a step-down rotates the key, and § 3 that only
  admin-web mints. Between the step-down and the Server Owner's next mint, reports seal to an epoch the leaver holds.
  - (a) Reports keep sealing to the old epoch; admin-web and the next instance mail to the Server Owner say "rotation
    due" (a new instance-notice kind: ADR 0097 decision 6 then gains one line).
  - (b) Report intake pauses (the report action is off, as before epoch 1) until the Server Owner mints.
  - (c) A remaining holder's client mints on its next unlock (needs ADR 0099 § 3 edited: a minter in `web/`).
  - **Recommendation: (a).** The leaver could read every report up to the step-down anyway; the window adds only reports
    made before the owner acts, (b) would switch off the notice path the DSA requires, and (c) adds a second minter for
    a rare event.
- **Q4 — Are moderation grants signed by their granter?** ADR 0028 decision 6 refuses an unsigned scope grant, so a
  server cannot hand an account a key of its own choosing. admin-web mints without a vault, so it has no identity
  signing key; ADR 0099 is silent. **Recommendation: unsigned**, with the residual stated in ADR 0099 § 3 in one
  sentence: a server that substitutes a grant must also substitute the published bundle to read anything, which ADR 0099
  § 3 already grants an active operator (the moderation authority itself). The moderator's check that matters is the
  published-key comparison of decision 7.
- **Q5 — Where do the use-cases live?** None exists (`grep -rli moderat docs/features/*/use-cases` names only mentions).
  **Recommendation:** a new feature `docs/features/moderation/` (core, ADR 0093 § Decision: "node, share, report and
  hold are platform terms"), with `docs/features/admin/_index.md`'s planned "Act on a reported public share" pointing
  there. Alternative: under `admin` (but a moderator and a reporter are not Server Owners).

## Use-cases

Phase 0 writes, each with a `status: planned` coverage file (`docs/coverage/README.md`) — the concept is ADR 0099's, so
nothing is invented: `uc.moderation.appoint-moderators` (the Server Owner turns moderation on, appoints, removes,
rotates), `uc.moderation.serve-as-moderator` (accept, confirm the address, receive the key, deliver it, step down), and
the model enablers `en.moderation.moderation-key` (epochs, grants, bundle, proof) and `en.moderation.log`. Then
`satisfies:` names them. (If the owner prefers the use-cases written before the go, phase 0 is that pass.)

## Phases

- [ ] **0 docs** — the feature `_index.md`, the two use-cases, the two enablers, planned coverage; ADR 0099 § 3 gains
  Q4's sentence, ADR 0097 decision 6 Q3's line if (a). · Done when: `validate_feature_format.py`, `validate_coverage.py`,
  `validate_doc_links.py`, `validate_doc_citations.py`, `validate_changes.py` green.
- [ ] **1 crypto** — `packages/crypto`: the bundle from `M_n` (decision 4), `sealTo` `moderation-grant`, the statement
  `moderation-proof` (sign and verify; widen `identitySign`'s type or add a moderation-specific signer — the statement
  table stays one table), vectors in `v1.json`, the backend's `ModerationProof` verifier with a test that reads the same
  vector. · Done when: `npm run check` in `packages/crypto/`, `npm run mutation -- --force` there with no survivor in
  the new code (its exit code in the commit message, per `web/CLAUDE.md`), `./gradlew check` green.
  **Bites:** change one byte of a label → the vector test is red on both stacks; reorder the statement's fields → red.
- [ ] **2 contracts** — decision 5's two surfaces; every operation classified in `OperatorBlindProbeTest` (the
  moderator operations in a new reason block: "moderator route: MODERATOR role + key proof"). · Done when:
  `npm run generate:api` in `web/` (add the new spec to its `generate:api`) and `admin-web/`, the contracts guards, and
  `./gradlew check` green.
- [ ] **3 backend: roster, key, role, proof** — decision 1's tables and entities; decision 2's role; the proof gate;
  appoint (refused for an unknown address, idempotent for a pending one), accept, remove (grants deleted at once,
  every epoch), step down (refused for the Server Owner while no other accepted holder exists, ADR 0099 § 5), mint
  (refused unless one grant per current accepted moderator), deliver (only by a holder of that epoch, only to an
  accepted moderator, idempotent); the identity-rotation hook (a rotated moderator's grants are deleted and a delivery
  becomes pending, ADR 0099 § 4); posture on each endpoint. · Done when: `./gradlew check` green and
  `python3 scripts/run_backend_races_on_mariadb.py` green (the deliver and mint races change a lock).
  **Bites (each a slice test, each with its positive control):** a stolen session without the key → every moderator
  operation `403` while the same session with the proof passes; a proof over another operation id → refused; a
  replayed challenge → refused; a removed moderator's session → no role, the same proof refused; a mint missing one
  moderator → refused.
- [ ] **4 backend: log and mail** — the instance events (appointed, accepted, delivered, removed or stepped down, epoch
  minted with reason category) written in the same transaction as their cause; their retention (31 December of the
  third year after the moderator's removal) as the purge `EveryStoredTimeHasAReaderTest` asks for; the moderator's
  address confirmation (code mailed on accept through instance mail; ADR 0097 decision 5); `InstanceNoticeMailer` able
  to mail a confirmed moderator (no notice kind uses it yet). · Done when: `./gradlew check` green; the log never
  appears in a log line (`TenantInviteMailerLogTest`'s pattern: the event is written, its sealed text is in no
  captured record). **Bite:** compute the retention from the appointment instead of the removal → its test is red.
- [ ] **5 admin-web + CLI** — Server → Moderation: turn on (mints epoch 1 to the Server Owner's own identity), the
  roster with states, appoint, remove-and-rotate, rotate by hand with a reason category, and the warnings ("no epoch:
  reporting is off"; "rotation due" if Q3 (a)); `veyq-admin moderator appoint|remove|list` and `moderation rotate`
  (the CLI bundles `@veyq/crypto`, per `admin-web/CLAUDE.md`). · Done when: `npm run check` in `admin-web/` green; a
  test that admin-web's mint never calls `openAs` (it cannot: assert the module imports no open function); the
  container build run or its omission stated. **Bite:** a mint that drops the last roster entry → the server's refusal
  surfaces as the panel's error, test red if swallowed.
- [ ] **6 web: the moderation surface's shell** — route `/moderation` on every tenant host (the static array in
  `router.ts`), reached through a read of my appointment (no role reaches `web/` otherwise); accept with the text ADR
  0099 § 1 asks for (child sexual abuse material named, every opening logged), confirm the address, unlock per decision
  7 and Q2, deliver pending grants after the pin check (`checkAndPin`, a changed identity declines as
  `grantDeliveryWorker` does), step down; the queue as an empty state. EN+DE strings. · Done when: `npm run check` in
  `web/` green; a test that after unlock `masterKey()` and `identityPrivate()` throw or return zero (the vault is
  empty) and no scope client was called (`guardedClients`-style record); a browser pass: Server Owner turns moderation
  on in admin-web, appoints a second dev account, it accepts and confirms, the owner's surface delivers, both open the
  empty queue. **Bites:** skip the published-key comparison → a test serving a mismatched bundle stays unlocked (red);
  keep the identity private key → the vault test is red.
- [ ] **7 docs + coverage** — `site/src/content/docs/run/moderation.md` (appointing moderators, rotation, the step-down
  window), a Use page for a moderator (EN+DE, new, at the depth of its neighbours: what accepting means, what is
  logged), `security/what-the-server-sees.md` (the roster, epochs and the log's clear fields),
  `security/roadmap.md:93` (stale: says no ADR exists); coverage refs replace the planned gaps. · Done when:
  `validate_coverage.py`, `validate_wiki_sources.py`, `npm run check` in `site/` green.

## Definition of done

Every phase's Done-when; coverage for the phase-0 use-cases with `backend` and `web` refs; every
`scripts/validate_*.py` except `validate_guard_gaps.py` green (baseline at refine: all green on `61aa7756`). The one
structural invariant — "every moderation operation passes `ModerationProofGate`" — is promoted to a permanent sweep
test beside `RateLimitPostureSweepTest`: every resource method of the moderation spec's generated interfaces is
gated by the role and the proof (bite: un-gate one → red).

Implementation waits for the owner's go.

## Retrospective

_Filled at implementation._
