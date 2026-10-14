---
id: cr-move-browser-and-replace-by-upload
title: Moving opens a folder browser with "Move here", and a file's content is replaced only by uploading its name again
status: in-progress
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

- [x] 1 web, the move browser (Decisions 1–4). Landed `4607622a`; its browser pass was a harness, not the routed app (Where it stands).
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
- [ ] 2 web, replace by upload (Decisions 5–6). **Partly landed** `0508e1eb` (rows, guest same-name replace); `FileDetail` is blocked on the open question below.
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

## Where it stands (2026-10-14, implement session)

- **Landed:** Phase 1 (`4607622a`) and the rows half of Phase 2 (`0508e1eb`). `npm run check` green after each (3458 tests).
  The guard glob (`scripts/validate_*.py`, 57 guards, each run on a detached worktree) was green on `c8f5bd1e`, the
  baseline, and green again on `0508e1eb`.
- **Open question (product, not settled by this CR), blocking the rest of Phase 2 and Phase 3:** removing `FileDetail`'s
  replace button (Decision 5) leaves a **`READ_WRITE` link on a single file** (`FilesShareRoots` admits it, inbox #378,
  `fileSingleShare.e2e.test.ts` proves its replace) with no write at all: there is no folder above the file to upload a
  same-name file into. Its share dialog still promises it (`files.share.roleContributorHint`: "…and replace its
  content"), and `docs/apps/files.md` § A single file as a share root still describes the replace. Options:
  (a) remove it as decided and stop offering `READ_WRITE` on a file (contract-free on the web: the file's share dialog
  offers `READ` only; the backend's `FilesShareRoots` keeps or drops the role — an existing link would then be a
  contributor link that can do nothing); (b) keep the replace on `FileDetail` **only at a single-file link's root** (the
  one place with no folder), removing it everywhere else as decided; (c) remove it and keep the role as it is (a dead
  option). **Recommendation: (a)**, web-side first: it keeps "there is no replace action" true everywhere, and a
  single-file link that can replace is better served by a folder link, which now does it by uploading.
- **Not done, waiting on that answer:** `FileDetail`'s replace button, its contributor sentence and its own upload
  notices (they only explain its own replace), `FilesUpload.beginReplace`, the keys `files.replace`, `files.uploadInto`,
  `files.replaceRowConfirm`, `files.detail.{replace,uploadInto,contributorHint}`, `files.guest.replaceFileConfirm`; then
  Phase 3 (docs and both wikis; the wiki still says the move picks "from a list" and describes the row's replace).
- **Browser pass:** the e2e route the brief named does not exist — `scripts/run_web_e2e.py` runs vitest suites against a
  live backend, API-level, with no browser and no Playwright in the repo. Phase 1 was clicked in the Browser pane on a
  throwaway harness (the real `MoveNodeDialog`, registry, i18n, theme and styles over an in-memory tree, served by the
  Vite dev server, deleted afterwards): as a member (a folder, a chapter, a file in a guest's folder) and as a
  `READ_WRITE` guest of a link on a folder, by mouse and by keyboard, at desktop and 375 px. Not the routed app, and no
  backend. Phase 2's guest same-name replace has not been clicked in a browser.

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

_(in progress — the CR is not done; see Where it stands.)_

**Mechanical gaps resolved at implement time:**
- *Which places are listed.* Decision 1 says "only children that can take every kind". Read literally, a chapter's
  browser would list Memories only, so a Memory in another folder is unreachable. Resolved as the old
  `moveDestinations` did: a `FOLDER` is always listed (the way down), and "Move here" there says the place cannot hold
  it (`kind`). A guest's browser lists folders only, because a Memory never takes a guest's child. A guest's identity
  folder is not listed at all, rather than listed disabled.
- *The node list.* The browser (`MoveBrowser.vue`) and its model (`move.ts`: `moveStart`, `moveTrail`, `movePlaces`,
  `moveHereBlock`) take a list. `MoveNodeDialog` keeps one `node`, because a write of several needs #421's own
  decisions: the toast wording, a partial failure, and the undo of several. Nodes from different parents open at the top.
- *Kind words.* Core has no kind noun for a non-folder kind (only `nounWithArticle`), so a Memory place shows its
  icon or cover and its name, with no meta line. A folder shows "Folder", as on the level.
- *Strings.* "Move here"'s reasons are new sentence keys `node.move.here.*`. The old fragments read wrongly as a
  sentence. `node.move.{destinations,nothing}` and `blocked.{current,identityFolder,kind}` went; `blocked.{self,inside}`
  are the listed node's reason.
- *The trail.* PrimeVue `Breadcrumb` with an `#item` slot that renders a `<button>` (never the `<a href="#">` of the
  web/CLAUDE.md trap). The shared DOM helpers are `src/test/moveBrowser.ts`.
- *Phase 2.* `FilesUpload.sameNameReplaces` went (true for both roles now); `filesCapabilities.replace` decides. A
  guest's `slug_taken` on a FILE's name is now only a race, so it gets the generic text (`files.guest.fileExists` and
  `files.guest.replaceConfirm` went).

**Test migration, per file (expect / it, `c8f5bd1e` → now):** `MoveNodeDialog.test.ts` 32→61 / 12→18, `move.test.ts`
32→59 / 16→18, `ShareTreePage.move.test.ts` 22→27 / 6→6, `MemoryHomeView.move.test.ts` 24→27 / 5→5,
`levelRows.test.ts` 117→117 / 36→37, `levelRows.guest.test.ts` 68→67 / 18→17. Every loss is an assertion about the
row's replace, the radio list, or the guest's refused same-name upload, which this CR removes.

**Bite tests (snapshot → inject → run → restore, bytes compared, `git status --porcelain` unchanged):** all 17 went red
on the named test.
- Phase 1, 9 levers: the takes-filter off (a drop box listed); the identity-folder filter off; the node itself openable;
  `current` dropped; the guest's top set to the scope root; an authenticated `sessionApi()` call from a guest's browser
  (swallowed, still red through `guardedClients`); the focus call removed; the empty text removed; "Move here" enabled
  at a blocked place.
- Phase 2, 8 levers: a `replace` row action re-added; a replace picker re-built; the guest's same-name upload made no
  replace (#392 back); a declined question still uploads; an empty file asks; a name another kind holds is sent; the
  question's old row wording; a guest's FILE `slug_taken` given a special text again.

**Process:**
- The brief's machine hint promised a Playwright e2e for the click-through. The repo has none, and reading
  `run_web_e2e.py`'s docstring settled it in one step. Next time: a hint that names a tool names the file that proves
  it exists.
- The CR's Decision 5 removed the opened file's replace without asking what that does to a single-file `READ_WRITE`
  link. The refine read the folder flows and not the file share root (§ A single file as a share root, three screens
  down in the same doc). Next time: a refine that removes a control greps every share root and role that reaches that
  control (`FilesShareRoots`, the role hints) before deciding.
