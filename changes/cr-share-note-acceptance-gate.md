---
id: cr-share-note-acceptance-gate
title: A share's note can ask to be accepted before the share opens, remembered on the visitor's device only
status: planned
satisfies: [uc.sharing.share-note]
adrs: ["0036", "0035"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

When the curator ticks **"must be accepted before opening"** beside a share's note, a visitor of that link sees the
note and **Accept** as a gate. The gate comes after the password and the tenant's terms, and before anything of the
share is drawn, for every role. Accepting is remembered on the visitor's device, for that share and that note's text,
so an edited note asks again. The switch is sealed inside the note, and nothing new reaches the server. Owner
decision 2026-10-14 (inbox #422). Web only, because the note is already an opaque `sealedNote` on the wire.

## Context & constraints

- [uc.sharing.share-note](../docs/features/sharing/use-cases/uc.sharing.share-note.md), main flow 1–2 and the new
  alternate flows. [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md) point 2 covers the
  switch inside the seal, the device-only acceptance, and the rejected server gate.
- [uc.sharing.share-accept](../docs/features/sharing/use-cases/uc.sharing.share-accept.md): the tenant terms gate,
  which is the model to follow. It is client-side, records nothing, and sits in `views/ShareView.vue`'s order
  (password, terms, content). The note gate is the third step.
- The note's plaintext today is a JSON `{locale: markdown}` map sealed under `K_share`, AAD `share-note`
  (`core/sharing/manageShares.ts` `sealNoteMap`, `resolvePublicShare.ts` `openNoteMap`).
- web/CLAUDE.md: the shell's gates (password, terms) are among the named guest-only exceptions. The note gate is one
  more of them, in the shell and not in a body. A guest-mode mount must prove no authenticated call.
- **Invariant:** operator-blind. The switch is inside the seal, so the operator cannot tell which links ask. The
  acceptance never leaves the device.

## Inventory (commands)

- The seal and open of the note: `grep -rn "sealNoteMap\|openNoteMap\|SHARE_NOTE\|hasLocaleText" web/src -l`.
- The gates' order and the terms flag: `web/src/views/ShareView.vue` (search for the terms gate and its localStorage
  key).
- Where the curator edits the note: `grep -rln "noteSection" web/src`. This covers the folder share dialog and the
  Memory share dialog.
- Where the note is shown today: `ShareTreePage.vue` (banner) and the drop pages (`*ShareBody.vue`).

## Decisions

1. **Plaintext shape.**
   - **Writing:** a note with the switch on is written as `{"texts": {locale: markdown}, "mustAccept": true}`. A note
     with the switch off is written as today's flat map, so nothing changes for existing links.
   - **Reading:** both shapes are accepted. A flat map means `mustAccept` is false. Anything else (an unknown shape,
     a non-boolean `mustAccept`) is treated like a note that does not open today, never as a gate the visitor
     cannot pass.
2. **The switch.** It is a checkbox under the note editor, enabled only while the note has text, off by default. Its
   value is read back on edit from the opened note.
3. **The gate.**
   - **Placement:** in `ShareView`, after password and terms, for READ, READ_WRITE and WRITE_ONLY alike. It shows the
     rendered note (the same safe markdown renderer) and **Accept**. There is no "decline" button: closing the tab is
     the decline.
   - **Once accepted:** the banner is not shown again above the content for that visitor. They have read it.
4. **Remembering.**
   - **The key:** `localStorage`, keyed by share id and a hash of the opened note plaintext. Using the hash, not the
     plaintext, keeps the note text out of storage. An edited note therefore asks again.
   - **When storage fails:** if storage is unavailable or throws, the gate asks on every load. It never skips the gate
     because nothing could be remembered.
   - **What is stored:** no account id and nothing else.

## Phases

- [ ] 1 web: Decisions 1–4.
  - Unit tests cover:
    - both plaintext shapes round-trip;
    - an old flat note never gates;
    - a malformed shape does not trap the visitor;
    - the gate's order after terms;
    - Accept is remembered and an edited note asks again;
    - storage that throws asks every time;
    - the guest-mode mount makes no authenticated call.
  - Each negative is bitten.
  - Done when `npm run check` is green in `web/` and a link with the switch on has been opened in a real browser,
    gated, accepted and reloaded (the web e2e suite, which runs its own stack; the owner's dev database is not to be
    touched).
- [ ] 2 docs + coverage: `uc.sharing.share-note`'s coverage YAML gains the gate's criteria, and the wiki's sharing
  pages (EN and DE) say what the switch does and that acceptance stays on the visitor's device. Inbox #422 is marked
  done.
  - Done when `python3 scripts/validate_coverage.py` and the trust-chain guards are green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser run is recorded.
- Coverage is updated.

## Retrospective

_(filled at implementation)_
