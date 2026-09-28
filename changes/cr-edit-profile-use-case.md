---
id: cr-edit-profile-use-case
title: The profile panel gets the use case it never had, a mounted test, and a real name that can be removed
status: planned
satisfies: [en.spaces.member-identity]
adrs: ["0023", "0072", "0022"]
invariants: [operator-blind E2EE]
---

## Open for the owner

Refined from inbox #212 on 2026-09-28. **Nothing below is answered yet.**

1. **Where does the use case live?**
   - (a) `uc.spaces.edit-profile`, next to the enabler it realises.
   - (b) `uc.account.edit-profile`, next to the code directory (`web/src/features/account/profile/`).

   **Recommendation: (a).** Everything the panel edits is per-tenant member identity: the username, the sealed
   real name and the key that seals it. [en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md)
   owns it, ADR 0023 decides it, and the tests already cover it under `uc.spaces.add-member`. The account
   feature's own scope line is *"account lifecycle and authentication"*. ADR 0023 removed the account-level
   display name on purpose: the account carries only email and keys. The code directory is an accident of where
   the settings rubric was first written, and code paths do not decide doc homes.
2. **Two as-built gaps in the panel. Fix them here, or write the use case around them?** Both were measured on
   `64c1f65`:
   - **A real name cannot be removed.** When both name fields are empty, `sealedName` is `undefined`, the field
     is omitted, and the server keeps the old name (`sed -n 49,59p web/src/features/account/profile/ProfilePanel.vue`;
     the contract says *"Omit to leave the current name unchanged"*, `contracts/api/account.openapi.yaml`
     `ProfileUpdateRequest.sealedName`). So a member who wants their real name out of a tenant's directory cannot
     do it.
   - **Without a grant, the name fields are live but are thrown away.** With no `K_tenant`, the inputs still
     render and accept typing (`ProfilePanel.vue` template: the two `InputText`s carry no `disabled`). Save then
     drops what was typed. The only sign is the hint `profile.noNameYet`.

   **Recommendation: fix both, web only, in Phase 1.** Removal is a privacy goal the use case should state
   outright. The fix is to seal whatever the fields hold, the empty name included, whenever the key is held.
   That needs no contract change: `sealName` of an empty name is a valid blob, and the directory already renders
   an empty name as `@username` (`grep -n "fullName(member.userId) ||" web/src/features/spaces/TenantMembersPanel.vue`).
   The operator learns nothing new: blob length already tells it roughly how long a name is, and a member who
   never set a name is `null` on the same row. The dead inputs get `:disabled="!nameKey"`. The alternative is to
   write *"a name, once set, can only be replaced"* into a use case, and that is a sentence nobody would sign.

## Goal

Inbox #212. *Settings → Your profile* has been built since cr-0083. It edits your per-tenant username and your
real name, sealed under `K_tenant`. No use case owns it: the panel's header cites `uc.account.profile`, which never
existed, and the enabler says its value reaches users through *"profile use-cases"* that were never written.
After this CR, `uc.spaces.edit-profile` states the flow, a coverage file ties it to tests, and a mounted
panel test covers the flow. Until now only the sealing crypto underneath had tests. The two gaps in Open
question 2 are fixed as well.

## Context & constraints

Measured on `64c1f65` (2026-09-28), each line re-runnable:

- **The panel and where it is offered.** `web/src/features/account/profile/ProfilePanel.vue` (the header comment
  cites `uc.account.profile`). It is offered only while a tenant space is active:
  `grep -n "'profile'" web/src/views/SettingsHubView.vue` → the rubric entry is gated on `activeSpace.tenantId`.
- **Nobody owns it.** `grep -rn "uc.account.profile" docs/ web/src` → only the code comment. The dangling id
  is also row 7 of `changes/cr-doc-citations-resolve-from-code.md`'s table. That CR's Q3 repoints the comment at
  `en.spaces.member-identity` and leaves the missing use case to this row.
- **What the tests cover today.**
  - Web: `grep -rln "ProfilePanel" web/src --include='*.test.ts'` → nothing. No test mounts the panel.
    `web/src/features/account/profile/profile.test.ts` tests only `sealName`/`unsealName`: round-trip,
    trimming, an empty blob gives `null`, the wrong key gives `null`, and another member's row is refused.
  - Backend: `grep -n "fun " backend/src/test/kotlin/com/veyq/app/features/admin/tenant/MemberIdentitySliceTest.kt`
    → `profileOpRoundTripsUsernameAndSealedName` and `changingToATakenUsernameIs409` exercise
    `PUT /api/account/profile`.
  - Coverage: both backend tests and `profile.test.ts` are cited under `uc.spaces.add-member`'s criterion
    `member-identity` (`grep -n "profile" docs/coverage/features/spaces/use-cases/uc.spaces.add-member.yaml`).
    That criterion's text also carries the edit half: *"… otherwise set/edited via the profile op after the member
    holds their grant"*.
- **A taken username saves nothing.** `AccountProfileHandler.update` returns `UsernameTaken` before
  `profiles.update(...)` and before the consent write (`sed -n 38,55p backend/src/main/kotlin/com/veyq/app/features/account/profile/AccountProfileHandler.kt`).
  So E1 below is atomic: the name and the consent preference stay unchanged too.
- **The consent checkbox in the same panel** is [uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md)
  step 1: account-global and owned there. The new use case points at it and does not restate it.
- **Stale neighbours this touches.** `docs/features/settings/_index.md` § Note still lists *"display name"* as a
  later account concern (`grep -n "display name" docs/features/settings/_index.md`). ADR 0023 removed it.
  `en.spaces.member-identity` § Type and its closing note speak of *"profile use-cases"* in the plural, and of
  a *"person/profile view"* that is still planned (ADR 0015). Only the edit half becomes real here.
- **Wiring guard.** `scripts/validate_feature_wiring.py` requires the new file to be linked from
  `docs/features/spaces/_index.md`. It also requires that, once the enabler's **Serves** names it, the use case
  links back to the enabler.

## Decisions

1. **Home: `uc.spaces.edit-profile`** (Open question 1 (a)).
2. **Seal what the fields hold whenever `K_tenant` is held** (Open question 2). In `ProfilePanel.vue`, `sealedName`
   is `nameKey ? sealName(nameKey.key, nameKey.address, { firstName, lastName }) : undefined`. The
   emptiness test goes. An empty name is then a sealed empty name, which the directory already shows as
   `@username`. No contract, backend or `memberName.ts` change.
3. **No key, no name inputs:** both name `InputText`s get `:disabled="!nameKey"`, and the `profile.noNameYet` hint
   stays as the explanation.
4. **`uc.spaces.add-member`'s criterion `member-identity`** keeps its refs. Its text loses the edit clause
   *"otherwise set/edited via the profile op after the member holds their grant"* and gains *"otherwise set later
   from the profile ([uc.spaces.edit-profile])"*, so the edit behaviour is stated once.
5. **The header comment of `ProfilePanel.vue`** cites `uc.spaces.edit-profile`. If
   `cr-doc-citations-resolve-from-code` lands first, it will already have repointed the comment to
   `en.spaces.member-identity`. This CR then replaces that id with the use case, and its guard keeps the new id
   honest. If this CR lands first, that CR's table loses row 7.

### The use case, verbatim (Phase 2 writes it as `docs/features/spaces/use-cases/uc.spaces.edit-profile.md`)

Verbatim except for the link targets. They are written relative to `changes/`, so that `validate_doc_links.py`
(which does not skip fences) resolves them here. When Phase 2 moves the text, it rebases every link:
`../docs/features/spaces/use-cases/` becomes empty, `../docs/features/spaces/` becomes `../`,
`../docs/features/account/` becomes `../../account/`, and `../docs/decisions/` becomes `../../../decisions/`.
The same guard then refuses a link it got wrong.

```markdown
# UC: Edit your profile in a space

- **ID:** uc.spaces.edit-profile
- **Primary actor:** A member of a tenant space, editing their own identity there.
- **Goal:** Change the handle other members find you by, and the real name they see, in one tenant space,
  without the operator ever reading the name.
- **Preconditions:** Signed in and unlocked, with a tenant space active (the *Your profile* rubric of the settings
  hub is offered only there). Setting or removing the real name additionally needs the member's tenant grant
  (`K_tenant`); the username does not.
- **Postconditions (success):** The member's per-tenant profile row carries the new `username` (plaintext,
  unique in this tenant) and a real-name blob sealed under `K_tenant` at the address (this tenant's scope, the
  member's user id). Every member of the tenant sees the change in the directory; nothing changes in any other
  tenant.

## Main flow

1. The member opens *Settings → Your profile* while a tenant space is active.
2. The client loads the member's profile in that tenant: the username, the sealed-name blob and the account-global
   consent preference.
3. The client opens `K_tenant` from the member's own tenant grant and unseals the name on the device, at the
   address (tenant scope, own user id).
4. The member edits the username, the first name and/or the last name.
5. On save, the client seals the first and last name under `K_tenant` at that address. The name may be empty,
   and an empty name is sealed like any other. The client sends the username (trimmed, lower-cased), the sealed
   blob and the consent preference.
6. The server re-checks that the username is free in this tenant, stores the handle and the blob verbatim, and
   returns the profile. The panel confirms the save.

## Alternate / exception flows

- **E1 — username taken in this tenant:** `409 username_taken`. The panel says the name is taken and nothing is
  saved: not the handle, the name or the preference. The same handle may be free in another tenant.
- **E2 — no grant yet** (an *awaiting access* member): there is no `K_tenant` on the device, so the name fields
  are disabled and a hint says why. The username alone can still be saved. Once the grant lands, the name can be
  set here ([en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md) E2).
- **A1 — remove your real name:** empty both name fields and save. An empty name is sealed, and the directory
  shows only `@username` from then on.
- **E3 — the profile does not load:** the panel shows a load error instead of the form. A refused session ends
  the way every refused session ends ([uc.account.sign-out](../docs/features/account/use-cases/uc.account.sign-out.md) E1).

## Quality notes

- **Operator-blind where it can be.** The username is plaintext by design: the server enforces its uniqueness and
  serves it in the directory ([ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)). The real name
  is ciphertext the server stores verbatim and never opens.
- **A name is bound to its row.** It is sealed at (tenant scope, user id)
  ([ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md)), so a server that serves one member's blob on another
  member's row makes it fail to open. It cannot pass it off as that member's name.
- **Per tenant, by construction.** A handle or name changed here changes nothing in another tenant. There is no
  global handle to correlate across tenants.
- **The consent checkbox** on the same panel is [uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md)
  step 1. It is account-global, not per-tenant.

## What this means for you / Was das für dich bedeutet

**EN —** In each shared space you have a username that others use to find you, and optionally your real name.
You can change both under *Settings → Your profile*, and you can remove your real name again at any time.
Your real name is locked with the space's key before it leaves your device, so the people in the space can
read it but the server cannot. A change in one space does not touch any other space you belong to.

**DE —** In jedem geteilten Bereich hast du einen Benutzernamen, über den andere dich finden, und optional
deinen echten Namen. Beides änderst du unter *Einstellungen → Dein Profil*, und deinen echten Namen kannst du
jederzeit wieder entfernen. Dein echter Name wird mit dem Schlüssel des Bereichs verschlossen, bevor er dein
Gerät verlässt: Die Mitglieder des Bereichs können ihn lesen, der Server nicht. Eine Änderung in einem Bereich
berührt keinen anderen Bereich, in dem du bist.

## Related

- Feature: [Spaces (scopes)](../docs/features/spaces/_index.md)
- Enabler: [en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md) (the per-tenant username and
  sealed name this edits)
- Decisions: [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md) (per-tenant identity),
  [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md) (the seal address),
  [ADR 0022](../docs/decisions/0022-settings-information-architecture.md) (the settings hub the rubric lives in)
- Related use-cases: [add-member](../docs/features/spaces/use-cases/uc.spaces.add-member.md) (where the username is first chosen, and the directory
  that shows it), [consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md) (the preference on the same panel)
```

Measured at refine: the ADR file names (`ls docs/decisions/0072-* docs/decisions/0022-*`), and the UI labels
*Settings → Your profile* / *Einstellungen → Dein Profil* (`grep -n "title: 'Your profile'\|title: 'Dein Profil'\|title: 'Settings'\|title: 'Einstellungen'" web/src/i18n/index.ts`).

## Phases

- [ ] 1 web: a mounted `ProfilePanel.test.ts` (happy-dom, `./profile` mocked at its exported functions), then
      Decisions 2 and 3.
      Locator: `grep -n "sealedName\|InputText" web/src/features/account/profile/ProfilePanel.vue`.

      **Order: red first for the two fixes.** Both are live defects, so their cases are written and seen red on
      the untouched panel. The other cases pin behaviour that is already correct, so each gets a lever instead.

      **Cases and levers** (each lever is snapshot-and-restore, never `git checkout`, and its red is recorded in
      the commit body):
      - **Saves the handle trimmed and lower-cased, with the sealed name.** `updateMyProfile` is called with
        `username: 'kevin'` from the input `'  Kevin '`, and with a `sealedName` that `unsealName` opens under
        the test key **at the member's own address** to the typed name. Lever: drop `.toLowerCase()` → red.
        A second lever seals at the wrong address (swap `userId`) → red. This is why the assertion unseals the
        blob instead of comparing it to a string.
      - **E1: a 409 `username_taken` shows the taken message.** Lever: map every code to `saveFailed` → red.
      - **E2: no key means username only.** `openOwnNameKey` resolves `null`, so `sealedName` is `undefined` in
        the call and both name inputs are `disabled`. The `disabled` half is **red today**, because the inputs are
        live. Lever for the other half: seal with a stub key when `nameKey` is null → red.
      - **A1: emptying both fields sends a sealed empty name.** `sealedName` is defined and unseals to
        `{ firstName: '', lastName: '' }`. **Red today**, because the field is omitted.
      - **Loads and shows the stored name.** The fields hold the unsealed name after mount. Lever: skip the
        unseal → red.
      · Done when: `npm run check` (web) is green, and every red above was seen where stated.
- [ ] 2 docs + coverage:
      - `docs/features/spaces/use-cases/uc.spaces.edit-profile.md`: the text above, after the two checks it
        names.
      - `docs/coverage/features/spaces/use-cases/uc.spaces.edit-profile.yaml` gets four criteria:
        - `edit-username`: backend `MemberIdentitySliceTest.profileOpRoundTripsUsernameAndSealedName`,
          `MemberIdentitySliceTest.changingToATakenUsernameIs409`; web `src/features/account/profile/ProfilePanel.test.ts`.
        - `name-sealed-on-device-at-its-address`: backend
          `MemberIdentitySliceTest.profileOpRoundTripsUsernameAndSealedName` (stored verbatim); web
          `src/features/account/profile/profile.test.ts`, `src/features/account/profile/ProfilePanel.test.ts`.
        - `no-grant-username-only`: web `ProfilePanel.test.ts`.
        - `remove-real-name`: web `ProfilePanel.test.ts`.
      - `docs/features/spaces/_index.md`: a line under *Use-cases*, **built (web)**.
      - `en.spaces.member-identity`: **Serves** gains the use case. § Type and the closing note name it
        instead of *"profile use-cases"*. The planned person/profile view stays planned.
      - `docs/features/settings/_index.md` § Note: *"display name"* becomes a pointer to
        `uc.spaces.edit-profile`, since the name moved to a per-tenant identity (ADR 0023).
      - Decision 4: `uc.spaces.add-member.yaml` criterion `member-identity`, text only.
      - Decision 5: the `ProfilePanel.vue` header comment.
      · Done when: `python3 scripts/validate_feature_format.py`, `validate_feature_wiring.py`,
        `validate_coverage.py` and `validate_doc_links.py` are green, and so is the full guard glob.

## Definition of done

- Baseline before the first edit: `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`
  (measured at refine on `64c1f65`: no output). Also record `cd web && npm run check; echo rc=$?` first. It was
  **not measured at refine**, and a red there is not this CR's.
- The two *red today* cases were seen red on the untouched panel. Every lever was seen red and restored.
- `grep -rn "uc.account.profile" web/src docs` returns nothing.
- No new guard. The use case is what was missing, and the existing guards check its shape and wiring.

**Invariants held:** the real name still leaves the device only as ciphertext under `K_tenant` at its own
address. No new field reaches the server, and the contract is unchanged.

**Not proven:** that a real browser renders the disabled inputs as disabled. happy-dom stands in for the
browser here. The owner's live check: open the rubric as an awaiting-access member.

## Retrospective
