---
id: cr-admission-is-a-key-holder-act
title: Only a key-holder can admit a member, and every grant says who sealed it
status: planned
satisfies: [uc.spaces.add-member, uc.spaces.add-group-member, uc.spaces.switch-spaces, uc.spaces.manage-group-members]
adrs: ["0020", "0028", "0024", "0014", "0072"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

Today a tenant or group owner's **session** is enough to get a scope's plaintext, and the session needs only
the password. The attacker invites an address they control, registers with their own keys, and the next
online member's client TOFU-pins that never-seen key and wraps the scope key to it with no human involved.
The same missing piece lets a malicious server hand an account a personal-scope grant sealed with a key it
chose. This CR makes admission a key-holder act and makes every grant carry its granter's signature
([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md) move 6,
[ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) Decision 6).
- Every membership that is to receive a grant carries an **admission tag** that only a holder of the scope's
  current epoch key can compute, and no client wraps a scope key to anyone whose tag does not verify.
- A client opens a grant only if the granter's signature verifies: under its own key for the personal scope,
  and under the granter's pinned key for a shared one.

Automatic delivery stays.

## Context & constraints

- **The rules:** ADR 0020 move 6 (the tag: who makes it, who checks it, what it does not bind and why) and
  ADR 0028 Decision 6 (the grant signature and the two verification rules). Also
  [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (rotation carries admissions forward) and
  [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) (the group add).
- **Builds on `cr-seal-binds-its-object`,** which must be done first. The grant this signs over is the one
  bound to `(scopeId, epoch)`, and both the tag and the signed statement use that CR's canonical encoding.
  Its "a grant states its epoch" change is what makes the tag's epoch check meaningful. By that order it also
  lands after `cr-scope-access-gate` (#157), whose gate decides what a *pending* member may do. This CR adds
  what a pending member needs before anyone wraps to it.
- **Its sibling is `cr-cold-credential-proves-the-secret-key`,** which closes #159's second door, a session
  installing its own identity through `rotateAccountKeys`. The two are independent. #159 is closed only when
  both are done.
- **The server verifies nothing new.** It cannot compute a tag (no epoch key), and a blind server does not
  adjudicate a signature (ADR 0028 § Consequences). It stores, relays, and requires presence. The one check it
  makes is completeness: a rotation must re-tag every open admission of the scope.
- **Every existing membership lacks a tag.** Nothing is migrated. Before V1 the owner resets the development
  instance; tell them, and do not wipe their database yourself.
- **Residuals this CR does not close** (ADR 0020 § Consequences, stated there): a malicious operator redeeming
  a real tenant admission with its own key, and a departing member colluding with the operator at rotation
  (inbox #161 (b)).

## Decisions

- **The tag.** `HMAC-SHA256(HKDF(E_epoch, info = "veyq/v1/admission-tag"), encode({scopeId, epoch, admissionId, userId?}))`,
  with the canonical encoding from `cr-seal-binds-its-object`. `userId` is present for a group add (the target
  has an account) and empty for a tenant invite. There is one vector for each case.
- **The admission id** is client-minted (UUIDv4), per ADR 0072 Decision 3.
- **Who writes a tag:**
  - **Tenant invite.** `inviteTenantMember` carries `{admissionId, admissionEpoch, admissionTag}`. Both accept
    paths copy them onto the new membership (`grep -n 'fun accept\|fun registerAndAccept' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantAcceptHandler.kt`).
    A reissued invite keeps its admission.
  - **Group add**, both the direct and the consent path. `addGroupMember` carries the same three fields, bound
    to the resolved target's `userId`.
  - **Birth.** The tenant owner at cold birth and a group's creator self-tag their own membership, so a later
    rotation has a tag to carry. A personal scope needs none, because nobody else ever wraps to it and its
    rotation is self-served.
  - **Rotation.** `rotateScope` carries a new tag for **every open admission** of the scope — granted and
    pending memberships and open tenant invites — computed under the new epoch. The rotator first verifies each
    old tag under the old epoch. One that fails is not carried: its member is not re-granted, and it is
    surfaced. The server refuses a rotation that leaves any open admission of the scope without a new tag.
    `rekeyAlone` re-tags the caller's own membership.
- **Who checks a tag:**
  - **The grant-delivery worker.** `GrantDeliveryTarget` carries the three fields. The worker recomputes the
    tag under **its own current epoch key**. On a mismatch, or on an epoch that is not its current one, it
    declines through the existing seam with a new reason, `admission_unverified`, and the existing flag
    surfaces it the way `identity_changed` already is. No new UI: reuse the changed-key review's list and
    status.
  - **The rotation re-grant**, as above.
- **The grant signature.** It uses the identity Ed25519 key over
  `encode({"grant", scopeId, scopeKind, epoch, recipientUserId, sha256(recipientIdentityPublic), sha256(wrappedGrant)})`.
  Add `identitySign`/`identityVerify` helpers to `@veyq/crypto`, domain-separated from `authSign` by the
  statement's label, with a vector.
  - **Every grant producer signs:** the tenant birth, `ensurePersonalScope`, `createGroup`, the delivery
    worker, the rotation re-grant and `rekeyAlone`.
  - **Every grant row stores and serves** `granterUserId` and `grantSignature`. `ScopeSummary` and `MyTenant`
    also serve the granter's identity public key, so the recipient needs no directory call.
- **The recipient's check** sits in the one unwrap path and in `openMyTenantIcon`:
  - `PERSONAL`: the granter must be the caller, verified under the caller's own signing key from the vault.
  - Shared: `checkAndPin(granterUserId, granterIdentityPublicKey)`. A first-seen key is pinned; a changed key
    means refuse and surface it through the existing changed-key notice.
  - A failed check means the key inside is never used. The scope reads as *awaiting access*, not as an error
    page.

## Phases

- [ ] 1 contracts (crypto) — the tag, the grant statement, `identitySign`/`identityVerify`, and their vectors.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [ ] 2 contracts (api) — the admission fields on `inviteTenantMember`, `addGroupMember`,
      `GrantDeliveryTarget`, `ScopeMember` and the rotation request; the signature fields on every grant write
      (`createPersonalScope`, `createGroup`, the owner-birth accept, `completeTask` for grant delivery, the
      rotation's grant entries) and every grant read (`ScopeSummary`, `MyTenant`).
      Locator: `grep -n 'wrappedGrant\|MemberInviteRequest:\|AddGroupMemberRequest:\|GrantDeliveryTarget:\|ScopeMember:' contracts/api/*.yaml`.
      · Done when: `npm run generate:api` + `npm run check` (web) green. The backend is red until phase 3,
      which is expected.
- [ ] 3 backend — migration, persistence, copy-on-accept, serving, and the rotation completeness refusal.
      Locators:
      `grep -rnE 'fun (inviteMember|addGroupMember|accept|registerAndAccept|rotate|complete|claim)\b' backend/src/main/kotlin/com/veyq/app/features/{admin/tenant,spaces,tasks}`
      and, for the tests that create memberships or grants,
      `grep -rlE 'wrappedGrant|addGroupMember|inviteTenantMember' backend/src/test`.

      **Bite (write first):** a rotation that omits the new tag for one pending membership → refused, nothing
      written. Control: all present → applied. The rotation shape changes, so also run
      `grep -n 'RotateScopeRequest' scripts/*.py` and update any guard that pins it.
      · Done when: `./gradlew check` green.
- [ ] 4 web — producers sign and tag; the worker, the rotation and the unwrap path verify.
      Locators:
      - `grep -rnE "wrapScopeKeyToMember\(|SCOPE_GRANT\b|'scope-grant'|scope-grant" web/src`, which after the
        previous CR includes the union's variant;
      - `grep -rnE 'inviteTenantMember|addGroupMember\(|rotateScope\(|rekeyAlone|rotateGroupForRemoval' web/src`.

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - The worker, handed a pending member whose tag is garbage, wraps today. After the change it declines
        with `admission_unverified` and writes no grant. Control: a valid tag → delivered.
      - A tag computed at epoch *n* reaching a worker at *n+1* → declined. Control: a tag at *n+1* → delivered.
      - A personal-scope grant sealed and signed by a foreign identity opens today. After the change it is
        refused and the key is not used. Control: self-signed → opens.
      - A group's genuine grant served as the caller's personal scope → refused, on the signed kind. Control:
        the same grant in its group row → opens.
      - A shared grant whose granter key differs from that granter's pin → refused. Control: matching →
        opens.
      - Rotation: a member whose old tag fails is not re-granted. Control: a valid one is re-granted and
        re-tagged.

      Every negative anchors on its specific refusal (the decline reason, the refusal type), not on "it threw".
      · Done when: `npm run check` (web) green.
- [ ] 5 e2e — **#159 end to end**, in the `run_web_e2e.py` harness against a live backend. A session opened
      with only the owner's password (no Secret Key) mints an invite whose tag it cannot compute, so it
      carries any bytes. An attacker account registers on it, and a genuine member's delivery worker then
      runs.
      - Assert that the attacker's membership holds **no** grant and the job was declined `admission_unverified`.
      - Control, in the same test: the owner's real invite is delivered.

      · Done when: `python3 scripts/run_web_e2e.py` green, and its log shows this suite executed rather than
      skipped (`scripts/validate_e2e_discovery.py` is the tell).
- [ ] 6 docs + coverage — one criterion each; no criterion appears under two use-cases:
      - `uc.spaces.add-member`: *"A tenant member receives the tenant key only if their admission carries a
        tag made with the tenant's current epoch key; an invite from a session without the vault is never
        delivered to"* (phase 4 and 5 tests);
      - `uc.spaces.add-group-member`: the same for a group add (its own tests);
      - `uc.spaces.switch-spaces`: *"A grant opens only if its granter's signature verifies — the personal
        scope's only under the caller's own key"*;
      - `uc.spaces.manage-group-members`: *"A removal's rotation carries forward only admissions whose tag
        verifies, and re-tags each under the new epoch"*.

      Sweep for sentences this made false:
      `grep -rn 'no second human\|wrappedGrant\|admission' docs/features docs/crosscutting --include=*.md`.
      Check the wiki promise this CR makes true (`wiki/en/who-gets-into-your-space.md`, the "behind your back"
      line, and its DE mirror): it should now read true without an edit. Flip the row "gets a scope's key
      through an owner's session alone" in [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve) — but first
      check the flip holds against the table's own subject, an **active operator**, and not only against a
      caller who holds a session: #159's other row ("installs an identity of its own") could not be flipped
      by `cr-cold-credential-proves-the-secret-key`, because a server-side check binds every caller but the
      operator, who writes the directory itself (ADR 0060 § Consequences).
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- All six phase-4 bites were seen red on the untouched tree, and the phase-5 e2e ran rather than skipped.
- No production path wraps a scope key to anyone other than the caller without a tag check. Measure it with
  the phase-4 first locator: every grant producer that wraps to a foreign key sits behind the verification.

**Not proven:** that an operator cannot redeem a genuine tenant admission with its own key, or that a
departing member cannot tag a sockpuppet at the epoch their removal leaves (ADR 0020 § Consequences). Both
are named there, and the second is inbox #161 (b)'s.

## Retrospective
