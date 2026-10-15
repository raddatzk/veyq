---
id: cr-share-switch-reaches-existing-links
title: A share switch reaches existing links at once
status: in-progress
satisfies: ["uc.admin.switch-capabilities"]
adrs: ["0088", "0032"]
invariants:
  - "Operator-blind E2EE: the gate reads the share row's plaintext role and the instance switch, nothing else"
  - "A guest learns nothing of the instance's policy: a switched-off link answers byte-for-byte as an unknown one"
  - "Nothing is deleted: switching back on serves the same link again"
---

## Goal

ADR 0088 decision 5: a share switch acts at once, on existing links too. While `core.public-shares`,
`core.public-shares.contribute` or `core.public-shares.drop-box` is effectively off, every guest endpoint refuses a
link of that role with the same non-enumerable `404 share_not_found` an unknown, revoked or expired link gets (owner,
inbox #442). Switching back on serves the link again, because nothing was written onto it.

## Measured (substrate: branch `claude/inbox-442-share-switch-existing-links-5b4d2e` at `9f2661a1`, clean)

- The guest operations are the 14 contract operations with no `bearerAuth` under `/api/shares/{shareId}`
  (`contracts/api/sharing.openapi.yaml`, `memories/share-media.openapi.yaml`, `depot/files.openapi.yaml`):
  `resolveShare`, `getShareTerms`, `resolveShareBlob`, `createShareIdentity`, `startShareBlobUploads`,
  `presignShareBlobUploadWindows`, `createShareNode`, `updateShareNode` (core); `listShareMedia`,
  `completeShareMedia`, `deleteShareMedia` (Memories); `listShareFiles`, `completeShareFile`,
  `ensureShareDropFolder` (Depot).
- Every one of them reaches `ShareGuestGate.activeShare` before it serves anything: `resolve`, `shareTerms`,
  `createIdentity` and `resolveBlob` call it directly (`SharingHandler`), the others through `viewGate` / `writeGate`
  (`SharingHandler.startUploads` / `windowUploads`, `ShareNodesHandler`, `ShareMediaHandler`, `ShareFilesHandler`,
  `DropFolderHandler`). Locator: `grep -rn "activeShare\|\.viewGate(\|\.writeGate(" backend/*/src/main backend/apps/*/src/main`.
- The only `CapabilityRegistry.isActive` calls in sharing are `SharingHandler` create / reactivate / reissue.
  `updateShare` changes a READ link to READ_WRITE in place and asks no switch, so a member can still make a
  contribute link while `.contribute` is off (the web hides the option; the server does not refuse it).

## Decisions (autonomous, owner's brief 2026-10-15)

- **D1 — one gate, in `activeShare`.** It is the one predicate every guest path passes, and it already answers
  "not served" as `null` for disabled, expired and not-live. The switch becomes one more `null` there, so every
  caller keeps its existing 404 mapping and no application learns a new outcome. `capabilityFor(role)` moves out of
  `SharingHandler` into `ShareAccessPolicy.capabilityFor`, so the create gate and the guest gate read one mapping.
- **D2 — the switch is checked before the password.** A switched-off password link answers 404 with and without a
  token, never the locked challenge: the challenge would tell a guest the link exists.
- **D3 — no re-check under the share-row lock.** `admitsGuests` stays the delete/revoke re-check. A switch is not a
  row the write could lock against, and a request admitted a moment before the switch commits is the same as any
  request in flight. Every multi-step guest write passes the gate again at its next step (window, complete).
- **D4 — presigned URLs already handed out run to their TTL**, as after a revoke (ADR 0013): the switch stops the
  next request, not bytes already authorised.
- **D5 — `updateShare` refuses a role change into a switched-off role** with `422 capability_disabled` (only an
  actual change; a PATCH that resends the current role is untouched). Without it a member could turn a working
  READ link into a READ_WRITE link that now serves no guest, or, before this CR, mint a contribute link past the
  switch. Narrowing READ_WRITE → READ stays allowed.
- **D6 — the members' manage list gets no "off by the instance" state here.** The web already hides what a switch
  refuses; showing a member that an existing link now serves no guest is a follow-up, not part of #442.
- **D7 — the criterion lives in `uc.admin.switch-capabilities`** (`share-switch-acts-at-once`): it is a property of
  what a switch does, triggered by three switches; the three sharing use-cases name it in their exception flows.

## Phases

### Phase 1 — contracts
- `contracts/platform/capabilities.json`: the three share entries' `whenOff` (EN + DE) and `description` say that
  existing links of the role stop at once and come back when switched on.
- `contracts/api/sharing.openapi.yaml`: `updateShare`'s 422 names `capability_disabled`; the guest operations'
  404 text names a switched-off role.
- Done when: `python3 scripts/validate_capabilities.py` and the contracts guards are green.

### Phase 2 — backend
- `ShareAccessPolicy.capabilityFor`, `ShareGuestGate.activeShare` asks `CapabilityRegistry`, `SharingHandler`
  reads the shared mapping and gates `updateShare` (D5).
- Tests, each guest path compared with the same request on an unknown link id (on: differs, off: equal, on again:
  differs), every path's mismatch collected so a bite names each one:
  - `:core` `ShareSwitchGuestGateCoreSliceTest`: core's eight operations on folder links of all three roles; each
    leaf switch refuses its own role and leaves the others served; the parent refuses all three; a password link.
  - `:apps:memories` `ShareSwitchGuestGateMemoriesSliceTest`: Memories' three plus the real upload path (start,
    window, complete) on a Memory.
  - `:apps:depot` `ShareSwitchGuestGateDepotSliceTest`: Depot's three.
  - `ReactivateShareCapabilitySliceTest`: the role change refused, the unchanged role and the narrowing allowed.
  - `ShareAccessPolicyTest`: the role → leaf matrix.
- Bites: (a) drop the switch line in `activeShare` → every guest path red in all three classes; (b) drop the
  `updateShare` gate → its test red; (c) make the refusal revoke the link → the "on again" half red.
- Done when: `./gradlew --no-watch-fs check` green; `run_backend_races_on_mariadb.py` green (a gate changed).

### Phase 3 — docs
- ADR 0088 § Consequences, ADR 0032 §5, `uc.admin.switch-capabilities` (+ coverage), the three sharing
  use-cases' exception flows, `uc.platform.capability-discovery` coverage text, web's two dialog comments that cite
  "a feature switch", the docs site's Run → Capabilities, Run → Moderation and Security → Roadmap, inbox #442 done.
- Done when: the guard glob is green.
