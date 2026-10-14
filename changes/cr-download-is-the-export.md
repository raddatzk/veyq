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
- **Out of scope:** folder upload without a manifest (inbox #424); downloading a selection (#421).
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

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser round trip is recorded.
- Coverage is updated.
- An archive written before this CR still imports.

## Where it stands

All four phases are built and committed (568c902a contracts, 41fb175d + a83902e7 web, and the docs commit). Two product
questions the CR does not settle keep it open; both are the owner's:

1. **Where a member PICKS a directory.** Decision 3 names a `webkitdirectory` pick and the use case "picks a directory", but
   no control names where. Built: a DROPPED directory (with `.veyq/manifest.json` imported, without it "Folder upload is not
   available yet"), and `pickedDirectory` (`core/export/importSource.ts`, tested) that maps a picked `FileList` to the same
   import input. Recommendation: the picker arrives with inbox #424 as a "New → Upload folder…" entry whose pick goes to the
   import when the manifest is there - no import-only picker now. Related: core's **New → Import…** (ZIP only) still exists
   beside the recognition on upload; keep it (one explicit door) or drop it with #424.
2. **A drop box as a ZIP.** The Goal and ADR 0082 decision 4 say a drop box downloads as a ZIP; uc E5 and Files' hooks say a
   drop box is not exported (it is a share object with guest identities, and nothing can place a guest's folder on import).
   Built: no Download on a drop box (unchanged). Recommendation: download-only - the drop box as a directory with its
   guests' folders as folders - and on import create it as a plain folder (no identities, no links), stated in E5; or strike
   "drop box" from decision 4.

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
