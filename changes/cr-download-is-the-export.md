---
id: cr-download-is-the-export
title: Download replaces Export - a readable ZIP with a hidden manifest, recognised on upload, unknown applications reported
status: in-progress
satisfies: [uc.platform.export-import]
adrs: ["0082", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

There is no "Export" any more. Every node offers **Download**: a file downloads as itself, while a folder, Memory,
chapter or drop box downloads as a ZIP. The ZIP **unpacks as the tree**, with real, file-system-safe names, and its
manifest sits beside the tree at `.veyq/manifest.json`, so the same archive is both an ordinary download and an
importable export. On upload, a ZIP, or a picked directory holding the manifest, is offered **Import** or **Upload as a
file**. The import's dry-run reports nodes this build cannot read (an unknown application, newer content) with
**Import the rest** / **Cancel**. The reader treats the archive as untrusted input. Owner decisions 2026-10-14.

Second round, owner answers 2026-10-14 (Decisions 6 and 7): a directory is picked through **New → Upload folder…**,
which also brings the ordinary folder upload (inbox #424) - with the manifest it goes to the import, without it it is
uploaded as folders and files - and core's **New → Import…** goes. A drop box downloads as a ZIP like any other node
and imports as a plain folder.

## Context & constraints

- [ADR 0082](../docs/decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md):
  decisions 3, 4, 7, 9 and 10 were edited and added by this refinement. Decisions 1, 2, 5, 6 and 8 and the Addendum
  are unchanged.
- [uc.platform.export-import](../docs/features/platform/use-cases/uc.platform.export-import.md): download flow,
  import step 1–2, E6, E8.
- [`contracts/export/veyq-subtree.schema.json`](../contracts/export/veyq-subtree.schema.json) is the contract and
  changes **first** (contracts → web). The manifest gains each node's path and the `applications` list (id plus
  content-schema version). The payload location moves from `data/<id>/` to the node's path. The location option
  goes.
- **`version` policy (ADR 0082 Consequences):** a change an old reader would misread bumps `version`. The new layout
  is such a change. So the reader must read **both** the new version and the current one, because archives already
  written must stay importable. The writer writes only the new one.
- **In scope since the second round:** folder upload without a manifest (inbox #424, Decision 6).
- **Out of scope:** downloading a selection (#421); a `WRITE_ONLY` drop-box guest's folder upload (the drop page).
- **Invariant:** operator-blind. Nothing reaches the server but the ordinary writes the import already makes.

## Inventory (commands)

- Export/import code and its dialogs: `grep -rln "veyq-subtree\|exportSubtree\|importSubtree\|ExportDialog\|ImportDialog\|includeLocation" web/src`.
- Every "Export" label and entry: `grep -rn -i "export" web/src/i18n/index.ts` (both locales), and the action
  registrations in core and the applications.
- The kinds' hooks: `grep -rn "exportContent\|importContent" web/src -l`.
- The guest's download action (today: file only) and where a folder/Memory would get one:
  `grep -rn "download" web/src/core/node web/src/features/files/levelRows.ts -l`.
- Wiki: `wiki/en/export-and-import.md`, `wiki/de/export-und-import.md` (and any link to them).

## Decisions (beyond the ADR, settled here so the build does not guess)

1. **Safe names** are one pure function in core (`archiveName`), used by the writer, the directory import and later
   #424. It applies decision 3's rules. A clash suffix is ` (2)`, ` (3)`, … before the extension. Its unit tests are a
   table of the hostile cases: `..`, `a/b`, `CON.txt`, `name. `, a 300-character name, two names that differ only in
   case, and an empty name.
2. **One action, per surface.**
   - **Download** sits where "Export" was (row menu, folder header, node page). On a file it is the existing direct
     download. On a folder or Memory it goes straight to the "Save as ZIP…" flow, with a summary of items and bytes and
     what a hook left out, as today, but **no warning text and no location checkbox**.
   - A `READ`/`READ_WRITE` guest gets the same action over the guest's source (ADR 0082 decision 5).
3. **Recognition on upload.**
   - A picked or dropped ZIP is sniffed for `.veyq/manifest.json`. **Both** layouts are recognised: the old
     `manifest.json` at the root and the new one.
   - A picked directory (`webkitdirectory`) with `.veyq/manifest.json` at its root is imported from its `File`s, with
     the same per-file `sha256` checks.
   - Recognised, a member gets a choice: **Import** or **Upload as a file**. A guest gets the ordinary upload.
   - A directory without the manifest gets a clear "Folder upload is not available yet" until #424.
4. **Unknown and newer (decision 9).** The dry-run lists, per application, how many nodes it would skip and why:
   unknown application, or content version newer than this build reads. **Import the rest** skips exactly those
   subtrees and names them in the final report. **Cancel** writes nothing. An archive with nothing importable offers
   only Cancel.
5. **Hardening (decision 10)** sits in the reader, before the dry-run returns:
   - schema validation;
   - caps (node count, depth, name length, each metadata field at its contract `maxLength`);
   - ZIP structure checks (offsets inside the file, no overlaps, declared size equals actual size, ZIP64 in range);
   - `sha256` while streaming, as today.
   Each refusal carries a reason the dialog shows (E8).

6. **Upload folder (owner answer 2026-10-14; inbox #424 joins this CR).**
   - The one directory picker is **New → Upload folder…** in the level's "New" menu, beside "Upload files". It is
     Files' level action (a tree of `FOLDER`s and `FILE`s; core never names `FILE`, ADR 0079), offered where Files'
     "Upload files" is offered; it takes a dropped directory too. The existing `pickedDirectory`
     (`core/export/importSource.ts`) maps the picked `FileList`.
   - A directory with `.veyq/manifest.json` at its root goes to the import (as a dropped one does today); one without it
     is uploaded as its tree: a `FOLDER` per directory, a `FILE` per file, every name through `archiveName`'s rules
     and its clash suffix, the top folder's name clashing among its siblings as `Name (2)` (as the import's root).
   - The "Folder upload is not available yet" message goes.
   - Before anything is written: the quota preflight for the tree's total bytes (the import's `checkTenantStorageFit`,
     same rules: a "no" says so and writes nothing, an unanswered question never blocks).
   - A file that fails is the upload queue's per-file failure; the others go on (the upload's existing rule: one
     file's hard failure does not cancel its batch).
   - A `READ_WRITE` guest gets it as a member does (share view = member view; a guest creates folders and uploads
     files), with no import: a guest's directory is always the ordinary folder upload, its `.veyq/` included as files.
   - **Core's "New → Import…" is removed** (entry, i18n keys, tests); upload recognises an archive itself.
7. **A drop box downloads and imports as a plain folder (owner answer 2026-10-14).**
   - Download: a drop box offers **Download** like any other container (member, and a `READ`/`READ_WRITE` guest of a
     folder link above it, who sees it read-only); the ZIP holds the drop box as a directory and each guest's folder as
     a directory under its shown name (a repeated name numbered as the detail numbers it), the files inside.
   - Import: the drop box and its guests' folders are created as **plain `FOLDER`s** - no drop key, no identities, no
     links; nothing can rebuild a guest's folder or identity. The manifest keeps the truth (the kind it was); the
     mapping to a folder is the drop box kind's own declaration, so core does not name `FILES_DROPBOX`.
   - If the manifest needs a field for this, it is a contracts change first; an addition an old reader ignores does
     not bump `version`.

## Phases

- [x] 1 contracts: the schema's new version.
  - The schema gains: node path, `applications`, and `.veyq/manifest.json` as the location. `options.includeLocation`
    is removed, and payload locations become paths.
  - The old version stays describable, because the reader needs it.
  - Update the ADR's worked example to the new layout.
  - Done when the schema validates the new worked example and an old one, and the trust-chain guards are green.
- [x] 2 web, write: the writer and walker emit the new layout and manifest (Decision 1, and Decision 2 for the
  download surfaces, including the guest's). The "Export" labels and the location option go.
  - Tests: an archive written from a tree with hostile names unpacks (`unzip -l` in a node test, or the reader) to
    the safe paths, and the manifest maps every node.
  - Done when `npm run check` is green in `web/`.
- [x] 3 web, read: recognition (Decision 3), unknown/newer (Decision 4) and hardening (Decision 5).
  - Tests cover:
    - an old-layout archive still imports;
    - a new-layout archive and its unpacked directory import to the same tree;
    - an unknown application's subtree is listed and skipped on "Import the rest";
    - Cancel writes nothing (no request recorded);
    - each hardening refusal fires on a crafted archive and writes nothing.
  - Each refusal is bitten.
  - Done when `npm run check` is green, plus a real-browser round trip on the dev instance (veyq-web on 8089,
    `?devlogin`): download a folder with a Memory and a file, unpack it, upload the ZIP and then the directory, and
    import both. The owner allowed using and wiping the dev instance.
- [x] 4 docs: both wiki pages (rename to download wording, keep the old page names as links if other pages link
  them), `docs/apps/*` where "export" is named, and the coverage YAML for the use-case's new criteria.
  - Done when `python3 scripts/validate_coverage.py` and the trust-chain guards are green.

- [x] 5 web: Decision 6 - "New → Upload folder…" (pick and drop), the folder upload, the manifest routing, the preflight;
  core's "New → Import…" removed.
  - Tests: a picked directory with the manifest opens the import and one without it creates the tree (folders, files,
    safe names, a clash suffix); the preflight's "no" writes nothing; one file's failure leaves the rest uploaded; a
    `READ_WRITE` guest's pick is the folder upload, never the import; the "New" menu no longer lists Import. Bitten.
  - Done when `npm run check` is green and a real browser on the dev instance picked a directory both ways.
- [x] 6 web (+ contracts if needed): Decision 7 - drop box download and import as plain folders.
  - Tests: a drop box with two guests' folders downloads as that tree; its import creates folders only (no drop key,
    no share); the Download action is offered on a drop box to a member and to a guest of a folder link above it,
    never to its `WRITE_ONLY` guest. Bitten.
  - Done when `npm run check` is green and a drop box was downloaded and re-imported in a real browser.
- [x] 7 live: a guest's download in a real browser - a `READ` folder link holding a Memory and a file (and a drop
  box) downloads its ZIP, which unpacks as the tree.
- [x] 8 docs: uc E5, E6 and import step 1 (no "not available yet", no "Import…", the picker), `docs/apps/files.md`
  (Upload folder; the drop box's download), both wikis, coverage, inbox #424 closed into this CR.
  - Done when the trust-chain guards are green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser round trip is recorded.
- Coverage is updated.
- An archive written before this CR still imports.

## Where it stands

All eight phases are built and committed; the CR waits only for the closing retro with the owner (the main session deletes it).

- Phases 1-4: 568c902a (contracts), 41fb175d + a83902e7 (web), 08665b74 (docs).
- Phase 5 (Decision 6, inbox #424): 08bd63ee - "New → Upload folder…", the folder upload, the manifest routing, the preflight;
  core's "New → Import…" gone.
- Phase 6 (Decision 7): c8196786 (contracts: `files.drop-box`) and 87ba7f5e (web: the drop box's Download, its ZIP under the
  shown names, its import as plain folders).
- Phase 7 (live, no code): a `READ` folder link holding a Memory (empty, then with a photo), files and a drop box with two
  guests named "Dr. Anna"; the guest's ZIP unpacks as the tree (`unzip -l`).
- Phase 8 (docs): the use case, ADR 0082 decisions 3 and 4, `docs/apps/files.md`, both wikis, coverage, `web/CLAUDE.md`, inbox
  #424 closed - in the docs commit that carries this section.

No question is handed back. What the build settled on its own is in the retrospective (each mechanical, none a product
choice the CR left open).

## Retrospective

- **A phase commit that did not build (execution).** Phase 2 renamed `exportActions` with `git mv`, rewrote it, and staged
  by a `git status` filter that skipped `RM` lines - so 41fb175d holds the renamed module's old body and does not typecheck
  alone; a83902e7 carries the fix (no history rewrite on a shared `main`). Root cause: `RM` read as "done". Moved to
  `docs/ai/README.md` step 3 (an `RM` line is a rename with its edits unstaged). Next time: after a `git mv` + edit, `git add`
  the new path and confirm the line reads `R `.
- **The CR settled the archive and left the controls open (planning).** It decided a picked directory and a drop box's ZIP
  without naming the control or reconciling E5, so both came back as questions; and "Download sits where Export was" did not
  say that a file must NOT get core's ZIP action - resolved mechanically by a descriptor flag, `downloadsAsFile`. Next time:
  a refine that adds an input names the control it arrives through, per surface.
- **Bites showed four tests that passed for the wrong reason.** A content-type refusal was caught by the "application not
  listed" check, and three path refusals by the node-path check, so removing the named check stayed green. Each test now
  keeps every other check satisfied and pins its refusal's message. Next time: write a negative's crafted input so that only
  the check under test can refuse it, and bite it at once.
- **Mechanical gaps resolved in the build:** a file kind's layout and Download (`downloadsAsFile`); the application of a node
  (its `content.type` prefix) and the build's version (`contentSchema` on the descriptor); "schema validation" of a kind's
  content (`validateContent`, core never names a kind's fields); the caps' values (the export schema now carries every
  `maxLength`, pinned by tests to the reader); one import per pick or drop; a directory offers Import only (it cannot be
  "uploaded as a file"); Windows-forbidden characters `<>:"|?*` count as unsafe; the old i18n keys renamed in place (`export.*`
  namespace kept, values say Download); wiki pages renamed with no stubs (only `Home.md` linked them, now relinked).
- **Bitten (each red, then restored by snapshot):** WRITE_ONLY guest download; ZIP action on a one-file kind; reserved names;
  case-fold clash; `.veyq` reservation; length bound; guest reads the member directory; v1 layout read; NFC for a directory;
  unknown application skip; newer content skip; Cancel writes nothing; content-type, unlisted-application, v2-at-v1-place,
  kind content check, name cap, depth cap, node-count cap; payload safe names, payload under its node, node path under its
  parent, `.veyq` path, FS-refused name; payload presence/size pre-check; ZIP offset outside, declared size, overlap, ZIP64
  range; LIMITS vs schema pin; guest upload not screened; folder-upload message; directory not uploaded as a file; one import
  per drop; Upload-as-a-file only when offered; Import hidden with nothing importable; Memories participant cap and medium
  without file; Files' picker through the screen.
- **Phases 5-8: the CR's two late decisions were complete enough to build without a question.** Decision 6 and 7 named the
  control per surface (the lesson of phase 1-4's retro, applied by the refinement), and the brief needed none of its machine
  hints beyond the dev instance's. What the build still had to settle, each mechanical and written where it binds:
  - a guest asks no quota preflight (the endpoint is a member's; "an unanswered question never blocks" already covers it);
  - a folder that cannot be created is named in a notice and its subtree is not sent (the CR named only a FILE's failure);
  - names are numbered by slug too, not only by `archiveName`'s case fold (`a.b` and `a b` fold to one folder slug);
  - the manifest's content type is `files.drop-box`: the schema's `content.type` pattern refuses `files.dropBox`;
  - an empty directory is not created (a pick or a drop lists only files) - stated in `docs/apps/files.md` and #424.
- **The CR's "numbered as the detail numbers it" was half the rule (planning).** Taking each archive segment from the shown
  name was not enough: the import numbers a clash in the order it meets the nodes, and the walker listed two equal names in
  served order - so the second Anna's files would have come back in the folder named "Anna". The drop box's import test
  showed it; the walker now lists equal names in their shown order, numerically (`G (2)` before `G (10)`), bitten by an
  eleven-name test. Next time: a rule about names in an archive is stated for BOTH directions - the path written and the
  name an import gives it back.
- **One guard half cannot be bitten.** `canUploadFolder` is Files' `upload` AND core's `createFolder`; in every row of today's
  two matrices they agree, so removing the `upload` half stays green. Kept as the statement of the rule; it bites the day
  the matrices differ.
- **Live verification without the OS dialogs (tooling).** The browser pane drives neither a directory picker nor a save
  picker. Used instead: `HTMLInputElement.prototype.click` hooked to capture the detached picker, its `files` set to `File`s
  carrying `webkitRelativePath`, then `change`; a drop as a synthetic `drop` event with `webkitGetAsEntry` entries; and
  `window.showSaveFilePicker` stubbed to collect the archive, read back as base64 into a fresh scratch directory for
  `unzip -l`. A PrimeVue popup left open by a JS click covered the header and swallowed a ref click; a `ref` taken before
  the menu re-rendered clicked the wrong place. Next time: open menus by a real click, and `find` the ref again right
  before the click.
- **Bitten (each red, then restored byte for byte by a script):** phase 5 - the directory screen ignoring who looks or the
  manifest, a manifest below the root counted (after a first bite missed and the test gained that case), a dropped
  directory not handed on, two imports per drop, a drop ignoring `enabled()`, the Import entry back in the menu, no
  preflight, a preflight blocking on no answer, a guest reading `activeSpace`, a picked directory not screened, no
  `webkitdirectory`, a dropped directory screened twice, no notice for a folder that failed, a guest's new folder not joining
  its key chain, the plan ignoring slugs, the top folder not numbered among siblings, no `archiveName`, no next suffix on
  `slug_taken`, a failed sub-folder aborting all, paths not NFC, `canUploadFolder` without core's `createFolder`; phase 6 -
  `importsAsFolder` ignored by the dry run or by the create, segments from the own name, no shown-name order, a
  non-numeric order, the drop box not exported, not imported as a folder, its content's extras or type unchecked, no
  Download in its header, a `WRITE_ONLY` guest allowed to download.
