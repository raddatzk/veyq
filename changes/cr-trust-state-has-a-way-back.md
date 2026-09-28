---
id: cr-trust-state-has-a-way-back
title: A refused pin store or device register has a way back — the rotation stops producing one, and the user can start one over
status: planned
satisfies: [uc.spaces.verify-identity, uc.account.sessions, uc.account.rotate-keys]
adrs: ["0028", "0060", "0072", "0026"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Open for the owner

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Scope: does this CR also move the rotation's re-seal into the rotation's one transaction?** (Decision A.)
   It adds a contracts and a backend phase to what the inbox row framed as a web reset.
   **Recommendation: yes.** The rotation is the one honest writer on the client that leaves either document
   unopenable (Inventory), and uc.account.rotate-keys step 4 *already says* the two documents are re-sealed as
   part of the account half, before the publish — the code is what diverged. A reset alone would turn every
   failed re-seal into a user-confirmed wipe of their contacts' pins; a re-seal inside the publish means a failed
   one fails the rotation instead, before anything is irreversible. A later "retry the re-seal" is not an
   alternative: once `rotateKeys` returns, the old Master Key is zeroed and every wrap that held it is rewritten
   or deleted, so no device can ever open the stranded store again.
2. **Where does the reset live?** **Recommendation:** one place, the settings hub
   (`web/src/views/SettingsHubView.vue`, beside key rotation and *Devices and sessions*), shown **only while this
   device refuses a document**; each existing refusal notice gains a link to it. One mounted dialog is what the
   import restriction in Decision C can pin; a reset button inside five notices is five places a later edit can
   wire to something automatic. Alternative: inline in each notice.
3. **How much friction?** **Recommendation:** a confirm dialog that names the cost in plain words (every contact
   starts over as first-seen, every verification is gone, a key change that happened meanwhile will not be
   noticed) and a required acknowledgement checkbox; **no** password or Secret Key. A credential defends against
   neither adversary here: the operator cannot click, and a client the operator serves needs no function to
   write an empty store. Alternative: require the cold credential, as rotation does.
4. **A rotation started while this device refuses a document.** **Recommendation:** refuse before anything is
   written (a fourth `RotationRefusal`, `trustStateRefused`) and point at the reset. Alternative: let the
   rotation carry a deliberate "start over" for that document — a second reset path, which is what Decision C
   exists to prevent.

## Goal

ADR 0028 Decision 7 refuses a sealed pin store or device register that is older than one this device has held,
missing after it held one, or that does not open — instead of reading it as empty, which was the rollback the
server could use to turn a detected key change into a silent first use. Nothing replaces a refused document: no
pin can be read or written, every wrap to a foreign key pauses, a shared scope does not open, and the register
stops naming rows, forever (inbox #192). This CR closes that in two moves: **(A)** the account-key rotation — the
realistic cause — re-seals both documents inside its own atomic publish, so it can no longer leave them under a
retired key; **(B)** for whatever else leaves a document refused, the user can **start that document over** on
this device, deliberately and knowingly, through one confirmed action that no server response can reach.

## Context & constraints

- **The rules:** [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) Decision 7 and
  § What is still open; [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) Decision 1 (one mode,
  every field mandatory) and § Consequences (*"must be re-sealed"*);
  [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md) (the version is bound into each seal);
  [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) (what "the server cannot trigger it" can mean on a
  client the operator serves).
- **Use-cases:** [uc.account.rotate-keys](../docs/features/account/use-cases/uc.account.rotate-keys.md) step 4
  and E7, [uc.spaces.verify-identity](../docs/features/spaces/use-cases/uc.spaces.verify-identity.md),
  [uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md) §E4; enablers
  [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md),
  [en.account.device-register](../docs/features/account/enablers/en.account.device-register.md).
- **`changes/README.md` rules this CR is the bill for:** § *turning a degradation into a refusal inventories every
  path that reached the degradation* (written by the CR that left #192), § *a server that insists on completeness
  needs a way to leave something out on purpose*, § *a device-local defence against the server is filed under a
  key the server cannot name*.
- **Measured against** `main` @ `faf9ef4` on 2026-09-28:
  - **The honest writers of the two documents — two angles.**
    - Keyed on the wire write:
      `grep -rn 'putIdentityPins\|putDeviceRegister' web/src admin-web/src --include='*.ts' --include='*.vue' | grep -v generated | grep -v '\.test\.'`
      → four sites: `identityPins.ts` `mutateAndSave` and `resealPinsUnderNewMasterKey`, `deviceRegister.ts`
      `mutateAndSave` and `resealRegisterUnderNewMasterKey`. The two `mutateAndSave` writes open through
      `openAccountDocument` and seal under the vault's current key, so they cannot produce an unopenable document
      short of a bug. The two re-seals are the producers.
    - Keyed on what makes a document unopenable — a Master Key change:
      `grep -rn 'randomBytes(32)' web/src --include='*.ts' | grep -v '\.test\.'` → the only Master Key minted
      after account birth is `rotation/rotateKeys.ts:95`. The two angles agree: **the rotation is the one honest
      client-side producer.**
  - measured (`sed -n 149,203p web/src/features/account/rotation/rotateKeys.ts`): the re-seals run *after*
    `rotateAccountKeys` has published, inside `resealBestEffort`, each in its own `try { } catch { }`; a failure is
    swallowed and the rotation returns its per-scope result as if nothing happened. `oldMasterKey.fill(0)` runs
    in the `finally`.
  - measured (`sed -n 36,40p docs/features/account/use-cases/uc.account.rotate-keys.md`): step 4 *Re-wrap the
    account* lists "the identity-pin store and the device register are re-sealed under `MK'`" among the things
    replaced **before** step 5 *Publish*; `RotateAccountKeysRequest`'s description says "Everything the account
    half of a rotation replaces, in one body, applied atomically or not at all"
    (`grep -n 'RotateAccountKeysRequest:' -A5 contracts/api/account.openapi.yaml`). The code is the outlier.
  - measured (`grep -n 'fun rotate' -A40 backend/src/main/kotlin/com/veyq/app/features/account/rotation/RotationHandler.kt`):
    the rotation runs in one `QuarkusTransaction.requiringNew()`; `IdentityPinsStore.put` and
    `DeviceRegisterStore.put` are `@Transactional` compare-and-set writes returning `Written | Stale`
    (`grep -n 'fun put' backend/src/main/kotlin/com/veyq/app/features/account/{identitypins/IdentityPinsStore.kt,deviceregister/DeviceRegisterStore.kt}`),
    so they can join the rotation's transaction as they are.
  - measured (`sed -n 3,12p scripts/validate_rotation_shape.py`): the guard pins `RotateAccountKeysRequest` to
    exactly five properties, all required. Decision A changes that set — the guard is edited in the same phase.
  - **Honest producers the client cannot remove** (remembered as mechanisms, not observed incidents): an operator
    restoring a database backup (served version below the marks → `trust_state_rolled_back` on every device that
    held a newer one), and a stored blob corrupted at rest (`trust_state_unreadable`). Both are why (B) is still
    needed after (A).
  - **Where a refusal surfaces today** —
    `grep -rn 'refusalMessageKey\|TrustStateRefused' web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.' | grep -v 'account/trustMarks.ts'`:
    `app/activeSpace.ts` (→ `views/MemoryHomeView.vue`), `features/spaces/useMemberDirectory.ts`
    (→ `GroupMembersPanel.vue`, `TenantMembersPanel.vue`), `features/spaces/ChangedKeyReviewDialog.vue`,
    `features/account/sessions/SessionsPanel.vue`, and the grant-delivery worker's pause
    (`features/tasks/grantDeliveryWorker.ts`).
  - measured (`sed -n 139,159p web/src/features/account/trustMarks.ts`): `openAccountDocument` is the single read
    rule; marks only ever rise (`raiseMarks` merges as a maximum). There is no operation that sets a mark lower.
- **Out of scope:** Memory favorites' re-seal (`resealFavoritesUnderNewMasterKey`) stays best-effort after the
  publish. An unreadable favorites set degrades to empty by
  [ADR 0068](../docs/decisions/0068-favorites-two-confidentiality-levels.md), it is not trust state, and it is one
  blob per scope — folding it into the rotation request would grow the request by the caller's scope count for
  no security gain.

## Decisions

### A — The rotation re-seals both documents inside its one transaction

- `RotateAccountKeysRequest` gains two **required** properties, `identityPins` and `deviceRegister`, each a `$ref`
  to the existing `IdentityPinsUpdate` / `DeviceRegisterUpdate` (`{ blob, version }`: the blob sealed under
  `MK'` for `version + 1`, guarded by the `version` the client read). Required, not optional, per ADR 0060
  Decision 1's rule that an optional field is a mode spelled by omission — and per § *a server that insists on
  completeness*: there is no "absent" state to confuse with "forgotten". An account with no document yet sends
  the **empty** document sealed for version 1 against `version: 0`, which the store's born-empty rule already
  accepts.
- The server writes both through the existing stores **inside** the rotation transaction, after the account
  rewrite. A stale version on either rolls the **whole** rotation back and answers `409 stale_version`: nothing
  published, nothing to repair. The client re-reads, re-seals and resubmits with the same `MK'` and the same
  signature (bounded like the documents' own writes, `MAX_WRITE_ATTEMPTS` in `identityPins.ts` / `deviceRegister.ts`).
- **Not bound into the Secret-Key proof.** The proof defends against a session holder without the Secret Key,
  and that holder can already write both documents through their own endpoints with the session alone; binding
  them would change the conformance-pinned `rotationProofStatement` for no defended case. Stated in the
  contract's description so the next reader does not "fix" it.
- **Web:** `rotateKeys` opens both documents under the **old** key through `openAccountDocument` (the marks
  apply, so a rotation cannot launder a rollback into the new key), seals them under `MK'`, and sends them in the
  request; `resealBestEffort` keeps only favorites. After a published rotation it raises the new slot's marks to
  `version + 1` for both. A document this device refuses at that open → `RotationRefused('trustStateRefused')`
  before any write (Open question 4).
- **ADR 0060 amendment (phase 0):** § Consequences' *"must be re-sealed"* becomes *"are re-sealed inside the same
  transaction as the rewrite: a rotation that cannot re-seal them does not happen"*, and Decision 1's field set
  names the two new mandatory fields.

### B — Starting a refused document over

- **What it does.** For one document (`'pins' | 'register'`) this device currently refuses: fetch the served
  copy; **if it now opens under the marks, stop** (another device fixed it — never wipe a readable document);
  otherwise seal the **empty** document under the current Master Key for `served.version + 1` and PUT it against
  `served.version`. On `409 stale_version`, re-fetch and re-decide, bounded. On success, the mark for that
  document is **set** to the written version.
- **Setting the mark is the one place a mark goes down**, and it is sound only there: the mark protects this
  device from a *served* older copy, and the copy it now accepts is the one it just wrote itself. It is needed
  because for `trust_state_rolled_back` the served version can sit below the mark, and the store's
  compare-and-set only ever writes `served.version + 1` — a reset that merely raised would refuse its own write.
  The operation (`resetMark`) is not exported beyond the reset module.
- **Other devices: some heal by reading, some must reset too.** For `trust_state_unreadable` — the case the
  rotation used to produce, and corruption at rest — every other device's mark sits at or below the served
  version, so the reset's `served.version + 1` is above it and their next read accepts it with no action. For
  `trust_state_rolled_back` after a backup restore, every device that held a newer version still has a mark above
  the reset's version, refuses it as rolled back, and is offered the reset itself; its reset writes the empty
  document again one version up, dropping what the first device re-pinned in between. That is a second
  re-first-use, not a lockout, and it is the honest cost of a device being unable to tell a restore from an
  attack. Considered and rejected: letting that second device *adopt* the served copy because it opens — that is
  exactly accepting an older copy, the rollback Decision 7 refuses.
- **What it costs, and the dialog says so:** pins — every contact is first-seen again, every verification is
  gone, and a key that changed meanwhile will be accepted as new; register — every device is unnamed until it
  next writes its own row (en.account.device-register § write moments), and the wrap joins behind ADR 0058's
  escalation are gone until then.
- **Offered for both refusal codes.** A server can forge either at will (garbage bytes read as
  `trust_state_unreadable`), so withholding the reset from `rolled_back` would buy nothing against an attacker and
  strand the honest backup restore.

### C — Nothing but the user's confirmation reaches the reset

The reset re-opens exactly what Decision 7 closed — an empty store — so the whole defence is *who can cause it*.

- **The server can cause the refusal, never the write.** No code path from a response, a refusal, the worker, or
  boot calls the reset. The only caller is the confirm dialog's confirm handler.
- **Pinned structurally:** an ESLint `no-restricted-imports` block (the `veyq/one-sign-out` pattern in
  `web/eslint.config.js`) forbids importing the reset module from every file except the one dialog component and
  its test. **Its limit, written beside it:** it pins who *imports* the function, not that the dialog only calls
  it on a click — the dialog's own test owns that.
- **The residual, stated honestly (ADR 0028, security-model):** a server that keeps a document refused can wait
  for the user to press reset. What changed is that the downgrade is now **visible and consented** — the user
  was told it is happening and what it costs — where before Decision 7 it was silent. On a client the operator
  serves, none of this holds against that operator (ADR 0026): it could ship a client that writes the empty store
  itself.

## Phases

- [ ] 0 docs — ADR amendments, before any code:
      - ADR 0060 § Consequences and Decision 1's field list (Decision A above).
      - ADR 0028 Decision 7 gains a bullet: *"A refused document has one way back: the user starts it over on a
        device, knowingly, through one confirmed action; that write is the only thing that lowers a mark, and no
        server response reaches it. The server can therefore withhold a document until the user resets it — a
        downgrade that is now consented, not silent."* § What is still open drops the *no way back* bullet.
      · Done when: `python3 scripts/validate_adr_citations.py` and `python3 scripts/validate_doc_links.py` green.
- [ ] 1 contracts — `RotateAccountKeysRequest` gains `identityPins` and `deviceRegister` (`$ref` to the existing
      update schemas), both in `required`; the description records "not bound into the proof" and the new 409.
      `rotateAccountKeys` documents `409 stale_version`. `scripts/validate_rotation_shape.py`'s pinned set and
      docstring grow to seven.
      **Guard bite:** drop `deviceRegister` from `required` → the guard goes red; restore by snapshot.
      **Expected red (named, per § phases only above real size):** the request shape changes, so the typed
      `rotateAccountKeysRequest` literal in `web/src/features/account/rotation/rotateKeys.ts` fails
      `npm run typecheck` until phase 3 — run it once to see that exact line; the existing tag's `…Api` makes
      `AccountRotationResource` fail to compile until phase 2.
      · Done when: `npm run generate:api` and `python3 scripts/validate_rotation_shape.py`,
      `python3 scripts/validate_contracts_common.py`, `python3 scripts/validate_request_bounds.py` green.
- [ ] 2 backend — `RotationHandler.rotate` takes the two updates and writes them through `IdentityPinsStore.put` and
      `DeviceRegisterStore.put` inside its transaction, after `applyRotation`; `Stale` → a new
      `RotationOutcome.TrustStateStale` → `409 stale_version`, whole transaction rolled back.
      Locator: `grep -rn 'applyRotation\|RotationOutcome' backend/src/main/kotlin`.
      **Bites** in `RotationSliceTest` (lever: the `version` field in the request, fixed per test):
      - stale pins version → `409`, and the identity, both cold wraps, the pin row and the register row are all
        exactly as before (the account half rolled back with it — the case that makes this a transaction and not
        two writes). Control: matching versions → `200`, pin and register rows at `version + 1` holding the
        request's blobs.
      - stale register version → same, independently (each document is its own lever).
      - account with no rows, both at `version: 0` → rows created at version 1.
      · Done when: `./gradlew check` green.
- [ ] 3 web — A, B and C.
      Locators: `grep -rn 'resealBestEffort\|resealPinsUnderNewMasterKey\|resealRegisterUnderNewMasterKey' web/src`;
      `grep -rn 'openAccountDocument\|raiseMarks' web/src/features/account`;
      the refusal sites under *Where a refusal surfaces today*.
      **Order: red first** — the rotation defect is live.
      **Bites:**
      - **Rotation strands the store (live defect — red today).** In `rotateKeys.test.ts`, the pins PUT that
        follows the publish answers 500. Today the rotation resolves and a later `loadStore()` under `MK'` throws
        `trust_state_unreadable`. After the fix there is no such PUT: the rotate request's `identityPins.blob`
        opens under `MK'` at `version + 1` (assert by opening it, not by its presence), and the same for
        `deviceRegister`. Lever: the served `{ blob, version }` fixed in the test.
      - **A failed re-seal fails the rotation, before the publish.** The rotate call answers `409 stale_version`
        twice then succeeds → one publish, re-sealed against the re-read version. Answering 409 past the bound →
        the rotation rejects and the vault still holds the old key. Control: first attempt succeeds → one call.
      - **No laundering.** The device's pin mark is 5 and the server serves pins v4 at rotation start →
        `RotationRefused('trustStateRefused')`, and the wire recorder saw **no** write. Control: v5 → proceeds.
      - **Reset, pins, `unreadable`:** garbage blob at v3, mark 3 → reset writes an empty store at v4 against
        v3, mark becomes 4, and `checkAndPin` then answers `firstUse` for a contact. Control: a blob that opens →
        the reset writes nothing.
      - **Reset, pins, `rolled_back`:** mark 7, served valid v4 → reset writes v5 and **sets** the mark to 5;
        the next read of v5 opens. Without the set (swap `resetMark` for `raiseMarks`) the next read refuses —
        this is the bite that proves the mark must go down there.
      - **Reset, register:** the same two cases through `deviceRegister`.
      - **The server cannot trigger it.** Serve each refusal (both codes, both documents) across every read path
        in the inventory — `loadStore`/`loadRegister`, `checkAndPin`, `readRegisterView`, the grant-delivery worker's claim,
        `activeSpace` open — and assert the wire recorder saw no PUT to either document. Lever: the served
        payload; this is the negative, and it anchors on the recorded PUT count, not on "it threw".
      - **Import restriction bites:** add `import { resetAccountDocument } from …` to `SessionsPanel.vue` →
        `npm run lint` red; restore by snapshot.
      - **The dialog:** confirm is disabled until the acknowledgement is ticked; cancel writes nothing; the
        dialog names the document's specific cost (real i18n catalog via `realI18n()`, EN and DE keys).
      · Done when: `npm run check` (web) green, and each bite above seen red where stated.
- [ ] 4 docs + coverage:
      - uc.account.rotate-keys: step 4 now matches the code; new exception flow *"a trust document this device
        refuses — the rotation refuses before writing and points at the reset"*; the rotate-keys coverage YAML
        gains `trust-documents-resealed-in-the-publish` (backend `RotationSliceTest`, web `rotateKeys.test.ts`).
      - uc.spaces.verify-identity and uc.account.sessions each gain a criterion *"A refused document can be
        started over only by the user, on a device, after a confirmation that names the cost; no server response
        reaches it"* with the web refs.
      - en.account.identity-pinning § sync (the "refused" paragraph) and en.account.device-register § quality
        notes (*"it does not repair itself"*) name the way back.
      - [security-model](../docs/crosscutting/security-model.md) — the row *"wipes or rolls back the pin store or
        the device register"* (`grep -n 'wipes or rolls back the pin store' docs/crosscutting/security-model.md`)
        gains the consented-reset residual from Decision C.
      - The EN/DE *What this means for you* paragraphs of both use-cases say what starting over costs.
      · Done when: `python3 scripts/validate_coverage.py` green and the full guard glob green.

## Definition of done

- Baseline, run **before the first edit**:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` (measured at refine on
  `faf9ef4`: no output — every guard green), `cd web && npm run check; echo rc=$?` and
  `cd backend && ./gradlew check; echo rc=$?` — the last two **not measured at refine** (no `node_modules`, no
  build in the refine worktree), so the implementer records both exit codes first.
- Every phase's bites seen red where stated; the rotation-stranding bite red on the untouched tree.
- `grep -rn 'resealPinsUnderNewMasterKey\|resealRegisterUnderNewMasterKey' web/src` shows no call inside
  `resealBestEffort` (they are either gone or called before the publish).
- `grep -rn 'resetMark' web/src --include='*.ts' | grep -v '\.test\.'` lists only the reset module.
- The import restriction is the promoted permanent guard for Decision C; the rotation-shape guard carries A.

**Invariants held:** operator-blind — the server receives two more ciphertext blobs it already stores, in a
request it already receives, and learns nothing it did not (it always knew a rotation touched them); marks only
rise, except the one confirmed reset; ADR 0060 Decision 1 — the rotation still has exactly one mode and every
field is mandatory.

**Not proven:** that a user will not press reset under a server that withholds the document on purpose — the
residual is consented, not closed (Decision C). And the no-mark and fork cases of ADR 0028 Decision 7 are
untouched.

## Retrospective
