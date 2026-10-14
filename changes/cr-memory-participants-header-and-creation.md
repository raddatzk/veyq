---
id: cr-memory-participants-header-and-creation
title: Participants are an action in the Memory's header, and the creator can record "I was there" when creating it
status: planned
satisfies: [uc.memory.participants]
adrs: ["0083", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

Two owner decisions (2026-10-14) on a Memory's participants, web only:

1. The participants dialog opens from an **action button in the header's action bar**, not from the "Add
   participants" link in the line under the title. That line shows the avatars, and **nothing** when there are no
   participants.
2. The **create dialog** of a Memory in a tenant or group scope offers **"I was there"**, ticked by default. When it is
   ticked, the Memory is born with the creator as its only participant, in the same first `wrappedMeta`.

## Context & constraints

- [uc.memory.participants](../docs/features/memory/use-cases/uc.memory.participants.md):
  - main flow "name the participants", steps 1 and 5;
  - the new main flow "at creation".
- [ADR 0083](../docs/decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md): the
  participants are opaque user ids in the sealed meta, never a name. The creator's own id is exactly that.
- web/CLAUDE.md:
  - core's `NodeHeader` takes the application's actions as data (`NodeHeaderAction`), each `shown` by the capability
    function. The participants action is Memories' own.
  - People and participants: the write merges on `409` through `editParticipantsOf`.
  - A guest-mode mount proves no authenticated call.
- **Invariant:** operator-blind. At creation the server receives one sealed blob, as it does today. Nothing new is
  disclosed.

## Inventory (commands)

- The current link and the avatars line:
  `grep -rn "participants.add\|participants.none\|MemoryParticipants" web/src/features/memory -l`.
- The header action list of an opened Memory: `grep -rn "standardNodeActions\|headerActions" web/src/features/memory -l`.
- Memory creation and its first meta: `grep -rn "createMemory" web/src -l` and the Memory `createDialog` registered in
  `features/memory/register.ts`.
- The own user id the session holds: `grep -rn "userId" web/src/app/session.ts`.

## Decisions

1. **The action.** A `NodeHeaderAction` with id `participants`, icon `pi pi-user-plus`, and label "Participants" /
   "Beteiligte" (aria-label and tooltip). It is `shown` by `memoryCapabilities(...).participants` and opens the existing
   `MemoryParticipantsDialog`. Its place in the action order is beside share and rename.
2. **The line under the title** draws the avatars when there are participants, as today: popover, click to a
   profile. With none it renders nothing, neither the "nobody yet" text nor the link. The unused i18n keys go.
3. **"I was there"** is a checkbox in Memories' create dialog, ticked by default, shown only when the target scope is a
   tenant or group scope and the creator is a member. It is never shown for a personal scope or a share guest.
   - When ticked, the create call's first `wrappedMeta` includes `participants: { userIds: [own id] }`.
   - When unticked, no `participants` key is written. This matches "no participants" exactly: the key is absent, not
     an empty list.
   - It is not remembered between creations.

## Phases

- [ ] 1 web: Decisions 1–3.
  - The tests must cover:
    - the action is shown for a member and absent for every share role;
    - it opens the dialog;
    - the line renders nothing with no participants;
    - a created Memory's meta carries the creator's id when ticked and no `participants` key when unticked;
    - the checkbox is absent in a personal scope and on a guest's create.
  - Each negative is bitten.
  - Done when `npm run check` is green in `web/` and both have been clicked through in a real browser (the web e2e
    suite, or the Browser pane on a stack of its own).
- [ ] 2 docs + coverage: `uc.memory.participants`'s coverage YAML gains the creation criterion, and the wiki pages on
  participants (EN and DE) say where the action is and what "I was there" means.
  - Done when `python3 scripts/validate_coverage.py` and the trust-chain guards are green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The click-through is recorded.
- Coverage is updated.

## Retrospective

_(filled at implementation)_
