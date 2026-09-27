---
id: cr-trust-state-never-goes-backwards
title: A device never accepts older trust state than it has held — pins, device register, and each scope's epoch
status: planned
satisfies: [uc.spaces.verify-identity, uc.spaces.manage-group-members, uc.account.sessions, uc.account.onboard-device]
adrs: ["0028", "0024", "0072", "0059"]
invariants: [operator-blind E2EE]
---

## Goal

The client's trust state lives on the server, and the client takes whichever copy it is served. The pin store
reads as the served copy even when that copy was wiped or rolled back, and a store that does not open reads as
**empty**. So the server can turn every detected key change back into a first use. Worse, a rolled-back pin can
reinstate a contact's retired key as a silent match. Because the client re-seals what it read, it then writes the
rollback back itself. The device register has the same shape. And no client remembers an epoch: a server can
hand a member an older grant and have new content sealed under a key a removed member holds.

This CR gives each device a local high-water mark for each piece of trust state, refuses anything below it, and
refuses a store that does not open ([ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md)
Decision 7, [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) Decision 6's floor).

## Context & constraints

- **The rules:** ADR 0028 Decision 7, including what it deliberately does not see, and ADR 0024 Decision 6.
- **Authentic labels first.** A floor compares against nothing trustworthy unless the version or epoch it reads
  is bound into the seal ([ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md) Decision 5).
- **Order:**
  - **After** `cr-seal-binds-its-object`.
  - **After** `cr-pairing-authenticated-by-the-qr`. A linked device inherits its marks inside the envelope, and
    only an authenticated envelope may set them.
  - **After** `cr-removal-rotates-in-one-request` (done 2026-09-27). It added the layer-1 half of the same
    write-epoch check that the floor extends: `toOpenedScope` in `web/src/features/spaces/scopes.ts` refuses a
    scope whose `grantedEpoch < currentEpoch` (`AwaitingCurrentKey`), and only `commitRotation` opens past it.
- **Web only.** The marks never leave the device, and the envelope is an opaque string on the wire, so there is
  no contract change. If one seems needed, it goes back to refinement.
- **Measured against** `main` @ `a494436` on 2026-09-24, all in `web/src/features/account/`:
  - `identityPins.ts`: `unsealPins` returns `{}` for a null blob and for a blob that fails to open;
    `mutateAndSave` re-seals the served copy; `resealPinsUnderNewMasterKey` re-seals whatever it is served.
  - `deviceRegister.ts` has the same three shapes.
  - No trust state is held locally. `grep -rn 'veyq-trust\|indexedDB\|localStorage' web/src --exclude-dir=generated`
    finds key material and preferences only.
- **Out of scope:** Memory favorites also decode an unreadable blob as empty
  ([ADR 0068](../docs/decisions/0068-favorites-two-confidentiality-levels.md) says so). That is a degradation,
  not trust state.

## Decisions

- **The marks.** Per device and per account: the pin-store version, the device-register version, and one epoch
  per scope. They are monotonic, raised by every successful open or write, never synced (ADR 0028 Decision 7),
  and kept in IndexedDB beside the existing local stores. The implementer picks the store (`veyq-trust` by name).
  - A mark is not a secret, so it survives sign-out. Losing one reopens the named residual; it breaks nothing.
  - Delete the marks only where the device's other saved data for that account is deliberately removed.
- **Read.** Each case has its own named refusal:
  - a served version below the mark → refused (`trust_state_rolled_back`);
  - a missing store once a mark exists → refused, same code;
  - a blob that does not open → refused (`trust_state_unreadable`), never read as empty. On a device with no
    mark, a *missing* store is empty and legitimate, but an unopenable one is still refused.

  A refusal blocks what needs the store: the worker declines or pauses a wrap to a foreign key, and the UI names
  the refusal through the nearest existing notice. No new UI.
- **Write.** The open that precedes every write is subject to the same checks. After a successful write the mark
  is the written version. The master-key rotation's re-seal of the pin store and the register goes through the
  same open.
- **The epoch floor.** A grant whose epoch is below the scope's mark puts the scope in the same *awaiting the
  current key* state `cr-removal-rotates-in-one-request` introduced. **As built, that state is not read-only:**
  the scope does not open at all (it reuses the awaiting-access presentation, as that CR decided), so older
  content does not read either. Re-decide at refine whether the floor wants that, or a read-only open that the
  layer-1 check would then have to adopt too. The mark rises whenever the device holds a newer epoch: a grant it opened, or a rotation it
  made.
- **The pairing envelope carries the marks** of the linking device. The new device takes the maximum of those
  and any it already holds, and only after the envelope's MAC has verified.

## Phases

- [ ] 1 web — the marks, the read and write checks, fail-closed, the epoch floor, the envelope field.
      Locators:
      - `grep -rnE 'unsealPins|mutateAndSave|resealPinsUnderNewMasterKey|loadStore|checkAndPin|acceptKeyChange' web/src`
      - `grep -rnE 'readRegister|writeRegister|rememberThisDevice|deviceRegister' web/src`
      - `grep -rnE 'grantedEpoch|writeKey\(|deriveEpochKeys|refreshActiveScope' web/src`

      All three include tests.

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - **Wipe.** The device opened pin store v3, and the server then serves `{ blob: null, version: 0 }`. Today
        the result is `{}` and `checkAndPin` pins the served key as `firstUse`. After the fix it is
        `trust_state_rolled_back` and nothing is wrapped. Control: a device with no mark and a null store →
        empty, accepted.
      - **Unopenable.** Garbage bytes as the blob. Today `{}`; after the fix `trust_state_unreadable`. Control:
        a valid blob → opens.
      - **Rollback reinstating a retired key.** The device holds v3, whose pin for a contact is their new key.
        The server serves v2, where the pin is the old key, and presents the old key. Today `checkAndPin`
        returns `match`. After the fix the read is refused. Control: v3 → the old key reads `changed`.
      - **Epoch.** The device held epoch `n+1` for a scope and is then served its `n` grant. Today it writes
        under `E_n`. After the fix the scope is read-only. Control: `n+1` → writes.
      - **Register.** A rollback below the mark → refused. Control: at the mark → accepted.
      - **Linking.** A device linked by one whose pin mark is 5, then served v4 on its first read → refused.
        Control: v5 → accepted.

      Every negative anchors on its specific refusal code, not on "it threw". Each bite fixes its lever (the
      served payload) in the test, not in a shared fixture a later phase could change.
      · Done when: `npm run check` (web) green, and `python3 scripts/run_web_e2e.py` green.
- [ ] 2 docs + coverage:
      - `uc.spaces.verify-identity` gains *"A device refuses a pin store older than one it has held, a missing
        store after it held one, and a store that does not open — none is read as empty"*.
      - `uc.account.sessions` gains the same criterion for the device register.
      - `uc.spaces.manage-group-members` gains *"No device seals under an epoch below the highest it has held for
        that scope"*.
      - `uc.account.onboard-device` gains *"A linked device starts from its linker's trust-state marks"*.
      - Edit the "Not yet built" sentence in `en.account.identity-pinning` § Sync & merge and in ADR 0028
        § What is still open, and the floor half of ADR 0024 § What is still open.
      - Flip the rows in
        [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Both phases done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- All six bites were seen red on the untouched tree.
- `grep -rnE 'return \{\}|pins: \{\}' web/src/features/account/identityPins.ts` shows no path that turns an
  unopenable blob into the empty store.

**Not proven:** a device with no mark trusts the first state it is served, and a newer state kept from a device
that never held it goes unnoticed (a fork). Both are named in ADR 0028 Decision 7, and no test pretends
otherwise.

## Retrospective
