---
id: cr-per-view-rights
title: A view's per-viewer functions are its rights, not capabilities
status: in-progress
satisfies: []
adrs: ["0086", "0079", "0032"]
invariants:
  - "capability means only the instance-wide switch (ADR 0032, ADR 0088); every per-view projection says rights"
  - "Behaviour-preserving: no matrix row changes, only names"
---

## Goal

Inbox #437, first half. ADR 0086 gives "capability" one meaning, the instance switch, and names what a view offers
this viewer its **rights**. The web client still calls those functions `…Capabilities`. Rename them, their types,
files, tests and the docs that cite them. Web only: nothing per-view reaches the wire.

## Inventory (measured on `9f2661a1`, the branch's base)

`git grep -c -I -E '(folder|memory|depot|files|source|favorite|cover|member|guest)Capabilities|(Folder|Memory|Depot|Files|Favorite|Cover)Capabilities|CapabilityTarget|NO_[A-Z_]*CAPABILITIES|MemoryDetail\.caps'`
→ **234 hits in 47 files** (web/src, web/CLAUDE.md, docs/apps, ADRs 0068/0079/0083/0086, use-cases, coverage, inbox).

| Old | New |
|---|---|
| `folderCapabilities` / `FolderCapabilities` (`web/src/core/access.ts`) | `folderRights` / `FolderRights` |
| `memoryCapabilities` / `MemoryCapabilities` (`features/memory/capabilities.ts`) | `memoryRights` / `MemoryRights`, file `rights.ts` |
| `depotCapabilities` / `DepotCapabilities` (`features/depot/capabilities.ts`) | `depotRights` / `DepotRights`, file `rights.ts` |
| `favoriteCapabilities` (`core/favorites/capabilities.ts`), `coverCapabilities` (`core/node/cover/capabilities.ts`) | `favoriteRights`, `coverRights`, file `rights.ts` |
| `sourceCapabilities` (Depot's and Memories' `source.ts`), local `memberCapabilities` / `guestCapabilities` | `sourceRights`, `memberRights` / `guestRights` |
| `CapabilityTarget`, `NO_*_CAPABILITIES` | `RightsTarget`, `NO_*_RIGHTS` |
| `MemoryDetail.caps.test.ts`, the `caps` prop and locals that hold rights | `MemoryDetail.rights.test.ts`, `rights` |

**Kept, because they ARE the instance switch:** `hasCapability`, `useCapabilities`, `setCapabilities`,
`CORE_*`, `web/src/features/platform/capabilities.ts` and its test, all of `admin-web/` (the Server → Capabilities
page), the catalog, `scripts/validate_capabilities.py`. `Access.publicShares*` stay: they carry instance switches.

Guards anchored on these paths: `git grep -n 'capabilities' -- scripts web/stryker.mutate.json` names only the
instance catalog's. Coverage files name the renamed test files (`docs/coverage/**`), and `validate_coverage.py`
resolves them.

## Phase 1 — rename (single phase)

Done when:
- the inventory grep above returns nothing outside history (done inbox rows, ADR 0086's own "was" text);
- `git grep -n -i 'capabilit'` over the touched files names only the instance switch;
- `npm run check` green in `web/`; `validate_coverage.py`, `validate_changes.py` and the doc guards green.
