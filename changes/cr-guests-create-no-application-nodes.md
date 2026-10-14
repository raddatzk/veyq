---
id: cr-guests-create-no-application-nodes
title: A share guest creates folders and uploads files, never a Memory or another application's node
status: planned
satisfies: [uc.sharing.share-contributor]
adrs: ["0080", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

Owner decision 2026-10-14. A `READ_WRITE` guest of a folder link may create folders, and may upload files (Files'
`FILE`, the contributor's upload). It may **not** create a Memory, a drop box, or any other application's node. Today
the server lets a guest create any kind `NodeKindRules` admits under a folder. The web shows a guest a disabled
"New Memory" entry, which is a bug against a comment that says guests create Memories. After this CR the **server**
refuses such a create. Core admits a `FOLDER`, plus any kind whose application declares it guest-creatable. The
**web** offers a guest no entry for such a kind at all: hidden, not disabled.

## Context & constraints

- [ADR 0080](../docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6 has
  been edited: what core lets a guest create under a folder. The declaration is the kind's application's, fail closed.
- [docs/apps/memories.md](../docs/apps/memories.md): the matrix row "Create a chapter or a Memory" and the folder-level
  paragraph. [docs/apps/files.md](../docs/apps/files.md) § Guests: a contributor's `FILE` create stays, through the
  declaration.
- **The server is the authority.** `backend/core/.../sharing/GuestNodeRules.kt` `FolderGuestNodeRules.readWriteAllows`
  answers `true` for every op today. Only `CreateChild` narrows; move-in, move-out and rename are unchanged.
- ADR 0079: core never names an application's kind. So the declaration is a seam an application implements (a CDI
  bean, like `ShareRoots` / `WriteOnlyGuestKinds`), not a list in core.
- **Invariant:** operator-blind. Unchanged, because the rule reads only the kind, which the server already sees.

## Inventory (commands)

- `grep -rn "GuestNodeRules\|WriteOnlyGuestKinds\|ShareRoots" backend/core/src/main backend/apps/*/src/main -l`
  shows the seams and their implementations.
- Guest create tests: `grep -rln "CreateChild\|createShareNode" backend/core/src/test backend/apps/*/src/test`.
- Web "New" menu for a guest: `grep -rn "createEntry\|allowed(" web/src/features/memory/register.ts web/src/features/files/register.ts web/src/core/node`,
  plus `ShareTreePage.vue`'s header comment about guests creating Memories.

## Decisions

1. **Backend.** A new core seam, e.g. `GuestCreatableKinds`, with one bean per application that declares the kinds
   its guests may create. Files declares `FILE`; Memories declares nothing.
   - `FolderGuestNodeRules` allows `CreateChild(k)` iff `k == FOLDER` or a declaring bean names `k`.
   - Any other create is refused as `illegal_scope`, the existing code for "the container's owner does not allow
     it". No new error code, so no contract change beyond the prose.
   - A kind claimed by two applications is a startup failure, as the other seams handle it.
2. **Web.** Each application's `createEntry.allowed(ctx)` is false for every share role where the kind is not
   guest-creatable. That means Memories' Memory entry and Files' drop box entry. The entry is **not shown**, rather
   than disabled.
   - Core's folder entry stays for a `READ_WRITE` guest.
   - Files' "Upload files" stays.
   - Fix `ShareTreePage.vue`'s comment.

## Phases

- [ ] 1 backend: Decision 1.
  - Slice tests: a guest's create of `FOLDER` and `FILE` under a folder succeeds; `MEMORY` and `FILES_DROPBOX` are
    `422 illegal_scope`; move and rename of an existing Memory by a guest are unchanged; a second bean claiming
    `FILE` fails startup.
  - Bite the narrowing, by restoring `true`.
  - Done when `./gradlew check` is green.
- [ ] 2 web: Decision 2.
  - Tests: a `READ_WRITE` guest's "New" menu lists the folder entry only, and the upload action stays. A member's menu
    is unchanged.
  - Done when `npm run check` is green, and the guest's menu has been seen in a real browser on the dev instance
    (`veyq-web` on 8089, `?devlogin`, a folder link opened as a guest). Note: a backend change needs the shared CLI
    `quarkusDev` to pick it up. The main session restarts it, so ask in the hand-back if hot reload does not take.
- [ ] 3 docs: `docs/apps/files.md` § Guests names the declaration; the sharing use-case and coverage for the
  contributor; both wikis' contributor text.
  - Done when the trust-chain guards are green.

## Definition of done

- `./gradlew check` and `npm run check` are green.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser check is recorded.
- Coverage is updated.

## Retrospective

_(filled at implementation)_
