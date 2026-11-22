---
id: cr-web-file-facts-on-the-core
title: The file facts move onto the Kotlin core for the web — a content sniff (the SVG rule among it), a download name, an archive name — `@veyq/core-js/files`, the TypeScript deleted; inbox #1059 decided per app
status: in-progress
satisfies: ["uc.depot.view-file", "uc.depot.member-files", "uc.platform.export-import"]
adrs: ["0155", "0156", "0154", "0082", "0047", "0079", "0046"]
invariants:
  - "operator-blind: nothing about what is sealed, under which purpose, or what the server sees changes; the façade takes a file's first decrypted bytes, a claimed type and names, and hands back answers — no key and no sealed byte crosses it"
  - "the bytes decide, the claim only narrows (inbox #412): a sniff answers from the bytes alone, an SVG is an image only where its markup and its claim agree, and no client renders a peer's SVG anywhere a script in it could run or a resource it names could be fetched"
  - "code the façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check; no `Long`, `Regex` or `HashMap` on its path"
  - "the archive's paths are unchanged: the same tree yields the same ZIP paths in the web and the apps, and a reader accepts exactly the segments the rules produce (ADR 0082 decision 3)"
---

## Goal

[ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
decision 7.2's fourth platform slice: the **file facts** — what a decrypted file's first bytes are (`core/blob/sniff.ts`:
image, audio/video container, PDF, text and its encoding, an SVG's markup, the claims that narrow), the name a download is
saved under (`core/blob/meta.ts`' `downloadName` and its type → extension table) and the safe name of a path segment inside a
download's ZIP (`core/export/archiveName.ts`). They become the platform's `blob/Sniff.kt`, `blob/DownloadName.kt` and
`export/ArchiveName.kt` alone, drawn by the web through `@veyq/core-js/files`; their TypeScript is deleted, the adapters at the
old paths hold no rule. `contracts/platform/files/v1-vectors.json` holds both sides.

Folded in: **inbox #1059** — the web shows an SVG whose markup and claim agree as an `<img>` from a blob URL (scripts never
run), the apps show it as its text. Decided per app, security first (§ Decision log, D9).

## Context & constraints

- ADR 0155's three built slices (§ The node tree's slice, § The share links' slice, § The rights and plans' slice) carry the
  traps: an entry's code is the module file, only its types its own `.d.mts`; no `Long`, `Regex` or `HashMap` on a hot JS
  path; the running budget with per-slice allowances (`src/packages/core-js/src/slice.test.ts`); the eager-set measurement.
- ADR 0155 placed this slice late "because it is lazy on the web today and the platform module is eager". Measured at the
  refine (D2): **it is not lazy today** — every part of it sits in the eager set already.
- [ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md):
  per-module output; a bundler drops nothing of a Kotlin module file.
- [`docs/apps/depot.md`](../docs/apps/depot.md) § The viewer: the web's rules for showing a file.

## Gates (the mandate's, never loosened)

- **Size:** own code ≤ 3.0 × the deleted TypeScript's gzip; the platform module within its running budget; the eager set
  grows by at most R × the deleted TypeScript; no lazy chunk grows by more than 1 KB gz.
- **Time:** each hot rule ≤ 2.5× the TypeScript's median and ≤ 100 ms (sniffing runs per file opened).
- **On a miss:** stop before the web wiring and report the numbers.

## Phases

1. **CR** — this file, its decision log.
2. **Contracts/vectors** — `contracts/platform/files/v1-vectors.json`: every sniff, claim, extension, download-name and
   archive-name case, recorded from the web's TypeScript (its writer a Node script over the sources, deleted in phase 3).
   *Done when:* the file exists, the web's answers recorded.
3. **Core** — `Sniff` gains the SVG rule, `textEncoding` and the web's EBML reading; `DownloadName` the web's audio rows;
   all three without `Regex`/`HashMap`; `FileFactsVectorsTest` (`:core` common: JVM and `macosArm64`) reads the file and is the
   writer from here on. *Done when:* `:core:jvmTest macosArm64Test` green, the rewrite byte-identical, a bite red.
4. **core-js façade** — `jsMain/…/files/web/FilesWeb.kt`, entry `./files`, the package's `files.test.ts` over the vectors,
   the slice check's row; the size and time gate. *Done when:* the gate passes, or the CR stops here with the numbers.
5. **Web wiring** — the three TypeScript files become adapters over `@veyq/core-js/files`; Memories' three byte readers move
   into `features/memories/download.ts`; the web's tests over the vectors. *Done when:* `pnpm run check` green.
6. **Apps (#1059)** — per D9.
7. **Docs and harvest** — the decision log into ADR 0155 § The file facts' slice, `docs/apps/depot.md`, inbox #1059; this CR
   deleted.

## Decision log

Each entry: question, options, choice and why, evidence (measured with its command, or remembered), cost.

- **D1 — The slice's set.** *Q:* what TypeScript leaves? *Options:* (a) ADR 0155's row — `sniff.ts`, `downloadName` with
  `MIME_TO_EXT`, `archiveName.ts`; (b) (a) and Depot's `view.ts` decision (`loadFileView`). *Choice:* (a). `view.ts` is
  Depot's (an application's slice), and it streams and collects chunks — the browser's transport, which ADR 0155 decision 1
  keeps. Memories' `sniffMediaType` (the type an upload seals) stays Memories' and keeps its three byte readers (`asciiAt`,
  `hasAscii`, `readEbmlDocType`, 298 B gz) in `features/memories/download.ts`, not counted as deleted. *Evidence:* measured,
  `npx -y node@22 scratchpad/ff/measure-ts.mjs <web src>` (each set bundled alone, every other import external, minified,
  gzip -9): the sniff's eight exports 4,136 B min / 1,746 B gz, `downloadName` + `MIME_TO_EXT` 627 / 405,
  `archiveName.ts`' exports 1,070 / 648; **together 5,815 B min / 2,539 B gz** (ADR 0155's row said 2,566). *Cost:* none.
- **D2 — Lazy or eager: the central question, answered by a measurement.** *Q:* the platform module is eager; does moving
  this slice turn lazy code eager, and would a second, lazily loaded Kotlin module be cheaper? *Evidence:* measured on the
  web's production build at `c4306dcce` (`vite build` under Node 22, then `/usr/bin/python3 scratchpad/ff/eager.py dist`:
  `index.html`'s entry and modulepreloads, each gzip -9; markers located by grep in the minified chunks): the eager set is
  **639,288 B gz** (ADR 0155's last recorded number); the sniff (`theora`, `x-ndjson`, `utf-16le`, `avis`) sits in
  `activeSpace` — eager, pulled there by `core/people/avatarCache.ts` (`sniffImage`) and Expenses' `receiptRules.ts`; the type
  → extension table in `activeSpace`; `archiveName`'s rules (`con|prn|aux`) in `main`. **Nothing of the slice is lazy
  today** — ADR 0155's premise was the Depot viewer's import, but the module lands where its first eager importer puts it.
  *Choice:* the platform module, as every slice before; no second module. A lazily loaded module would pay ADR 0156's
  per-module overhead to keep lazy what is eager already. *Cost:* none beyond R; ADR 0155's note on slices 4–6 corrected for
  this one (slices 5 and 6 measure their own).
- **D3 — The entry is `@veyq/core-js/files`.** `jsMain/…/files/web/`, a core area's name (no application is `files`), so the
  web's direction guard reads it as core's; its code the module file, its types `dist/files.d.mts` (remembered from the earlier
  slices' entries).
- **D4 — Bytes cross without a copy.** The web holds a `Uint8Array`; Kotlin/JS's `ByteArray` is an `Int8Array`. The façade
  takes the `Uint8Array` and reads it through an `Int8Array` view of the same buffer — no copy of a 256 KiB first chunk.
- **D5 — The taken names cross as the web's own `Set`.** `archiveName(name, taken)` adds to the caller's set, which the web's
  exporter and folder upload keep across a directory. *Options:* an opaque handle per directory; an external interface
  (`has`, `add`) a `Set<string>` satisfies. *Choice:* the external interface — the callers keep their sets, no handle to
  track; the core's `ArchiveName.of` takes its own small `Taken` interface, a `MutableSet` adapter on the apps.
