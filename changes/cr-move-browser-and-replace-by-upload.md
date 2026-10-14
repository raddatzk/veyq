---
id: cr-move-browser-and-replace-by-upload
title: Moving opens a folder browser with "Move here", and a file's content is replaced only by uploading its name again
status: planned
satisfies: [uc.memory.move-node]
adrs: ["0079", "0080"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

Two owner decisions from 2026-10-14, both on the web only. **Moving** a node no longer lists every destination with
a radio button. Core's move dialog becomes a **folder browser**: it opens at the node's place, shows only the places
that can take its kind, lets the user open a place, and moves with **"Move here"** or stops with **Cancel**.
**Replacing** a file's content is no longer an action. The row's "Replace content of …" / "Upload into …", the opened
file's replace button and the contributor sentence beside it are removed. A file's content is replaced by
**uploading a file of the same name into its folder**, which asks first, for a member (as today) and now for a
contributor guest too (reversing inbox #392).

## Context & constraints

- [uc.memory.move-node](../docs/features/memory/use-cases/uc.memory.move-node.md), step 2 and the quality note "One
  picker for one node and for many": the browser. Steps 3–7, A1–A2 and E1–E5 are unchanged, so the placement write,
  the slug pre-check, the drop-box question, Undo and every server refusal stay as they are.
- [docs/apps/files.md](../docs/apps/files.md):
  - § The web: "There is no replace action".
  - The capability matrix: "upload, and replace by uploading a file of the same name", with the opened file's
    replace row gone.
  - § Guests: a contributor's same-name upload replaces after the question.
- Inbox #358 (the list, now replaced), #392 (reversed), #383 (every replace asks first, unchanged: the question moves
  from the row to the upload), #421 (the selection will reuse this browser, so build it to take a node *list* even
  though this CR passes one node).
- ADR 0079: core owns the dialog and the level's rows. An application supplies only data, so the browser is core's
  and draws core's `LevelRowItem`. ADR 0080: a move re-wraps one key. Nothing in the write changes.
- web/CLAUDE.md:
  - a share view is the member view with a source and capabilities, so the guest gets the same browser over its
    subtree;
  - a click test must click the rendered element;
  - a guest-mode mount must prove no authenticated call.
- **Invariant:** operator-blind. Nothing new reaches the server. The browser reads the tree the session already holds.

## Inventory (commands, run them at implement time)

- **Move dialog and its helpers:**
  - `web/src/core/node/MoveNodeDialog.vue`, `web/src/core/node/move.ts` (`moveDestinations`, `nodeMover`) and their
    tests: `grep -rln "MoveNodeDialog\|moveDestinations" web/src`.
  - The i18n keys: `grep -n "move: {" -A40 web/src/i18n/index.ts` (both locales).
- **Replace in Files:** `grep -rn -i "replace\|uploadInto" web/src/features/files --include=*.ts --include=*.vue -l`.
  - The row actions are in `levelRows.ts`, the page button in `components/FileDetail.vue`.
  - The member's same-name path is in `uploadFile.ts`. The guest's `slug_taken` mapping (`files.guest.fileExists`)
    is in `ShareFilesSection` / `shareUpload.ts` / `source.ts`.
  - "Replace anyway" after `stale_file` stays. It is an upload notice, not a row action.
- **i18n keys that go:** `grep -n "replaceRowConfirm\|replaceFileConfirm\|uploadInto\|detail.replace\|detail.contributorHint\|fileExists" web/src/i18n/index.ts`.
  Keep whatever the same-name question still uses.
- **Docs and wiki that still say "list" or "row replace":**
  `grep -rn -i "from a list\|aus einer Liste\|row's replace\|replace button\|Ersetzen" docs/apps/files.md wiki/en wiki/de docs/features/files docs/features/sharing`.

## Decisions

1. **The browser.**
   - It opens at the parent of the node, or the parents of the nodes. The breadcrumb runs up to the session's top:
     a member's scope root, a guest's link root (`TreeSource`, so a guest's links keep `#k=`).
   - The list shows only children that can take **every** kind being moved (`placement`). Folders and Memories are
     drawn as the level draws them (`LevelRowItem`, no actions, icon or cover as the level resolves it).
   - Nothing else is listed: no files, no Memories that cannot take the kind.
   - An empty place says so ("No place here takes …").
2. **What may be opened and where "Move here" is enabled.**
   - The moved node itself and anything below it are listed disabled, with the reason, and cannot be opened.
   - A guest's identity folder is not offered, as today.
   - "Move here" is disabled with the reason where the node is now, and wherever `moveDestinations`' existing
     reasons say no. Those reasons are reused, not re-derived.
3. **Keyboard and screen reader.** Each place is a button that opens it. The breadcrumb items are buttons. "Move here"
   is the dialog's primary button. Focus goes to the list after moving into a place. Tests click the rendered
   elements.
4. **The write is unchanged.** "Move here" calls the same `nodeMover` path, with the same slug pre-check, drop-box
   question, toast and Undo.
5. **Replace by upload, for both roles.**
   - The row action ("Replace content of …" / "Upload into …") and `FileDetail`'s replace button and contributor
     sentence are removed.
   - A member's same-name upload already asks (`files.replaceConfirm`) and fills an empty file without asking. That
     stays.
   - A contributor guest's same-name upload goes through the **same** question, then replaces through the guest's
     existing replace door (the one the row used). It no longer ends in `409 slug_taken` + `files.guest.fileExists`.
   - A name another kind holds is still refused before anything is sent (inbox #400).
   - "Replace anyway" after `stale_file` stays, for both roles.
6. **The capability stays, the surfaces go.** `filesCapabilities.replace` keeps deciding whether a same-name upload may
   replace. It is no longer read by a row or by `FileDetail`.

## Phases

- [ ] 1 web, the move browser (Decisions 1–4).
  - Rewrite `MoveNodeDialog` over a node list and port its tests to the browser.
  - The test file must cover:
    - it opens at the parent;
    - a non-taking child is not listed;
    - the node itself is disabled and cannot be opened;
    - "Move here" is disabled where the node is now, enabled one level down;
    - moving into a place and back up via the breadcrumb works;
    - a guest sees only its subtree, and its mount makes no authenticated call;
    - "Move here" sends the existing placement write.
  - Done when `npm run check` is green in `web/` and the dialog has been clicked through in a real browser, as a
    member and as a `READ_WRITE` guest, with a screenshot in the retro.
- [ ] 2 web, replace by upload (Decisions 5–6).
  - Remove the row action, the page button and its sentence, and the now-unused keys.
  - Turn the guest's same-name `slug_taken` path into the asked replace.
  - The tests must cover:
    - neither surface offers a replace any more;
    - a member's and a guest's same-name upload asks, and replaces on accept;
    - cancel sends nothing;
    - an empty file is filled without the question;
    - a name another kind holds is still refused unsent.
  - Done when `npm run check` is green, each new negative is bitten, and a guest's same-name replace has been clicked
    in a real browser.
- [ ] 3 docs: sweep the inventory's grep in `docs/apps/files.md`, the sharing and files use-cases and **both wiki
  languages** (the wiki is what users read). Done when the trust-chain guards are green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green on a run over the committed tree. Run it on a detached worktree, per
  changes/README.md.
- Both flows have been clicked through in a real browser, as a member and as a contributor guest.
- `docs/coverage/` is updated for `uc.memory.move-node`. Each changed acceptance criterion is covered by a test or an
  explicit gap, and `python3 scripts/validate_coverage.py` is green.
- Not in scope:
  - the selection and bulk move of #421 (the browser only accepts a list);
  - drag and drop;
  - copying (#423).

## Retrospective

_(filled at implementation)_
