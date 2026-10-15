---
id: cr-files-becomes-depot
title: The second application's code, stored ids and docs say Depot
status: planned
satisfies: []
adrs: ["0086", "0015", "0082", "0079", "0087"]
invariants:
  - "Product language: one name per application (ADR 0086) — after this CR nothing names the application Files"
  - "Operator-blind E2EE: the blob role FILE_CONTENT is bound into every sealed wrap's context and does not change"
---

## Goal

Make the second application **Depot** everywhere its name sits, as ADR 0086's Depot paragraph sorts it: the
contract directory, the Gradle module and package, the web feature, the i18n namespace, the app-prefixed seams, the
stored node kind `FILES_DROPBOX` → `DEPOT_DROPBOX`, the export ids (`depot.file`, `depot.drop-box`, application
`depot`), the application's tab label (*Depot*), the docs, the use-case ids (`uc.files.*` → `uc.depot.*`) and the
docs site. The generic word *file* stays where it means a file: node kind `FILE`, blob role `FILE_CONTENT`, the API's
resource-named tags, paths and schemas, and the UI's *file* / *Datei*. Pre-V1, so the baseline changes in place and
every database is recreated — the dev databases need that anyway after `cr-hybrid-kem-is-x-wing`. This splits the
Depot part out of inbox #437; the Rights and Revision renames stay there.

## Context & constraints

- [ADR 0086](../docs/decisions/0086-one-word-per-meaning.md) § Depot: what moves, what stays, and why (Memories'
  precedent: the id names directories, module, package, namespace and app-prefixed seams; the API is named by
  resource).
- [ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md): Depot claims no route segment (`claimNoRouteSegment`), so
  no URL slug moves.
- [ADR 0082](../docs/decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md):
  the export manifest's content types and application ids — `contracts/export/veyq-subtree.schema.json` and
  `web/src/features/files/exportImport.ts`. No alias for `files.*`: before V1 there is no archive to read.
- [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md): its Use
  bullet asks the Depot pages for a one-line note while the UI says *Files*; this CR removes the need, so phase 3
  removes that clause, and `cr-use-area-content` phase 6 writes no such note.
- The build couples the contract directory and the module: `backend/buildSrc/.../SpecSets.kt` fails the build when
  `contracts/api/<dir>/` has no `:apps:<dir>`, so both move in **one commit**.
- `FILE_CONTENT` is encoded in the AEAD context of sealed wraps (`contracts/crypto/vectors/v1.json` `file_blob`) —
  renaming it would break every wrap; it is generic and stays.
- The upgrade record's top-level key `files` (`web/src/test/upgradeRecord.ts`, read by `scripts/run_web_e2e.py`)
  names the recorded files, not the application; it stays, so the upgrade proof keeps reading its predecessor.
- `changes/cr-capability-register-recut.md` (another session, in flight) names `FILES_DROPBOX` and plans `depot.*`;
  whichever CR lands second follows the other's names.

### Inventory — the locators

Measured at refine on `main` @ `d08a289f`. Telling the application from the word *file* is the work: the
application is a directory, module, package, `Files…`/`FILES_…` seam, the i18n namespace, the export prefix, a
`uc.files.*` id, or prose naming the application.

| What | Locator |
|---|---|
| Backend module, package, app-prefixed seams | `git grep -nE 'veyq\.files\|apps:files\|apps/files\|\bFiles[A-Z]' -- backend Dockerfile` |
| The stored kind | `git grep -n FILES_DROPBOX` |
| Contract directory and its consumers | `git grep -n 'api/files\|files\.openapi'` and `web/package.json` `generate:api` |
| Web feature, its symbols and importers | `git grep -n "features/files/\|'\.\./files/\|generated/files" web/src` and `grep -rhoE "[A-Za-z_]*(Files\|FILES)[A-Za-z_]*" web/src/features/files \| sort -u` |
| Export ids | `git grep -nE "'files\.(file\|drop-box)'\|application: 'files'\|files\.file\|files\.drop-box\|filesFile\|filesDropBox"` |
| i18n namespace | `git grep -cE "['\"\`]files\.[a-zA-Z]" web/src` and the `files:` blocks in `web/src/i18n/index.ts` (EN and DE) |
| Guards that name the application | `git grep -nE "apps/files\|apps:files\|features/files\|\"files\"\|FILES_DROP" -- scripts` |
| Use-case ids and their citations | `git grep -noE 'uc\.files\.[a-z-]+'` |
| Docs and site naming the application | `git grep -nwE "Files" -- docs site CLAUDE.md backend/CLAUDE.md web/CLAUDE.md` (read each hit: the application or the word) |

Look-alikes that stay: `FILE`, `FILE_CONTENT`, `FileRow`, `FileBlobData`, `FileViewer`, `uploadFiles`, `listFiles`,
the tags `files` / `share-files`, the paths `/files`, the generated `FilesApi` / `ShareFilesApi` and the
`FilesResource` / `ShareFilesResource` that implement them, the upgrade record's `files` key, the historical CR ids
`cr-files-application` / `cr-file-uploader`, and every *file* / *Datei* that means a file.

## Phases

- [x] 1 contracts + backend — `contracts/api/files/` → `contracts/api/depot/` (spec file and the prose that names
      the application) together with `:apps:files` → `:apps:depot` and `me.raddatz.veyq.files` → `me.raddatz.veyq.depot`
      in one commit (settings, root composition, `Dockerfile` COPY lines, `ModuleCompositionTest`, `Composition.kt`);
      the app-prefixed seams `Files…` → `Depot…`; `FILES_DROPBOX` → `DEPOT_DROPBOX` in the module, core's comments
      and tests, the contract descriptions and the export schema; the export schema's `files.*` content types and
      application id; the backend guards (`validate_kernel_imports_no_slices.py`, `validate_guard_gaps.py`'s
      anchors); the coverage YAMLs' backend test-class citations follow the renamed tests. · Done when:
      `./gradlew check` green, the contracts guards and `validate_backend_module_graph.py` green; bite: a
      `contracts/api/files/` directory restored beside `depot/` turns the build red on the missing module. (Ticked 2026-10-15 with one red outside
      this CR: `CapabilityRegistryTest`, from `cr-capability-register-recut` phase 1's catalog ids.)
- [x] 2 web — `web/src/features/files` → `web/src/features/depot`, `generate:api` input and output
      (`generated/depot`), `registerFiles` and every app-prefixed symbol, the export ids in `exportImport.ts` and its
      import validators, the i18n namespace `files:` → `depot:` (EN and DE) with the application's tab label
      `Depot`, while every string that means a file keeps *file* / *Datei*; `validate_web_core_imports_no_applications.py`
      (application map, namespace, kind fragment); the coverage YAMLs' `src/features/files/` paths. · Done when:
      `npm run check` green in `web/`; the web e2e suites green including the Depot suites and the upgrade proof
      (`scripts/run_web_e2e.py`, run named); a browser pass shows the level tab *Depot*, an upload, a drop box, and a
      download whose manifest says `depot.file`; bite: an import of a manifest with `files.file` skips those nodes. (Ticked 2026-10-15: `run_web_e2e.py` 26/26 incl. the
      `upgrade` pair from this tree; the two-build `--upgrade` proof is reported in the close-out, not here. Reds outside
      this CR: `platform/capabilities.test.ts`, from `cr-capability-register-recut` phase 1's catalog ids.)
- [x] 3 docs + site — `docs/apps/files.md` → `depot.md`, `docs/features/files/` → `docs/features/depot/`,
      `docs/coverage/features/files/` → `…/depot/`, `uc.files.*` → `uc.depot.*` with every citation (locator), the
      ADRs' and CLAUDE.mds' live mentions of the application, `security-model.md`; the site's `files.md` / `de/files.md`
      → `depot.md` with the sidebar, the links to it and its `sources:`, and the application named Depot in the
      site's prose; ADR 0087's "while the web client still labels it Files" clause and `cr-use-area-content`
      phase 6's note go; inbox #437 keeps only Rights and Revision. · Done when: trust-chain green, `npm run check`
      in `site/` green, and the docs locator's remaining hits are each the word *file* or a historical CR id. (Ticked 2026-10-15; the inbox's DONE rows keep
      *Files* as the record of what was built then, ADR 0086 keeps it where it records the rename.)

## Definition of done

Nothing in code, stored data, exports, docs or the site names the application *Files*; the UI's level tab says
*Depot*; every generic *file* is untouched and `FILE_CONTENT` unchanged; backend, web and site checks, the e2e suites
with the upgrade proof, and the trust-chain are green. The renamed guards keep the application map honest from here.

## Retrospective

(filled at close)
