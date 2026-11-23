---
id: cr-web-export-archive-on-the-core
title: The export archive drawn from the Kotlin core on the web — ADR 0155 decision 7.2's sixth slice
status: in-progress
satisfies: []
adrs: ["0155", "0156", "0082", "0152"]
invariants:
  - "the web's own answers are the vectors' first source (phase 1); the core answers every case before the web draws from it, and then writes the file"
  - "streaming stays streaming: the writer holds one chunk and a directory line per entry, the reader one READ_CHUNK; no archive is buffered whole on the web"
  - "the TypeScript it replaces is deleted, its adapters hold no rule; the façade's code is the module file, its types a `.d.mts` of their own"
  - "no Long on a hot path the façade reaches, no kotlinx.serialization, kotlinx.coroutines, Ktor or refusal in what an entry ships"
  - "the gate: the platform module within its running budget (R = 3.0 × the deleted TypeScript's gzip), the eager set by at most R × that, no lazy chunk +1 KB gz, each hot rule ≤ 2.5× the TypeScript's median and ≤ 100 ms; a miss after one optimisation round goes to the owner"
---

## Goal

A download's ZIP — the store-only writer and its exact length (`zipLength`), the reader that distrusts what it opens, the
CRC-32 both run — and the manifest's check (`parseManifest`, `collectPayloadsOf`) come from the platform core (`export/`)
through `@veyq/core-js/export`, held by `contracts/platform/export/v1-vectors.json` on every client. A wrong byte here
loses a user's data: every case is byte-exact, and the bites prove the vectors notice.

## Phases

1. The vectors from the web's own TypeScript (`archive.vectors.write.test.ts`, deleted in phase 2).
2. The core answers every case and writes the file (`ArchiveVectorsTest`); its CRC and hot records without `Long`.
3. The façade, `@veyq/core-js/export`, its package test and the slice check's row.
4. The web draws from it; the TypeScript deleted, `zip.ts` and `manifest.ts` adapters.
5. The gate measured; the harvest into ADR 0155; this CR deleted.

## Decision log

(kept by the implementer; moved into ADR 0155 § The export archive's slice at harvest)
