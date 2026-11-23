---
id: cr-web-media-headers-on-the-core
title: The media headers drawn from the Kotlin core on the web — ADR 0155 decision 7.2's fifth slice
status: in-progress
satisfies: []
adrs: ["0155", "0156", "0154"]
invariants:
  - "the web's own answers are the vectors' first source (phase 1); the core answers every case before the web draws from it, and then writes the file"
  - "the TypeScript it replaces is deleted, its adapters hold no rule; the façade's code is the module file, its types a `.d.mts` of their own"
  - "no Long, Regex or HashMap on a path the façade reaches; no kotlinx.serialization, kotlinx.coroutines, Ktor or refusal in what an entry ships"
  - "the gate: the platform module within its running budget (R = 3.0 × the deleted TypeScript's gzip), the eager set by at most R × that, no lazy chunk +1 KB gz, each hot rule ≤ 2.5× the TypeScript's median and ≤ 100 ms; a miss after one optimisation round goes to the owner"
---

## Goal

An image's pixel size from its header (`imageHeader.ts`), a video's display size and capture time from its container
(`videoHeader.ts`) and a photo's EXIF, XMP and IPTC tags (`exifTags.ts`, over the ExifReader library) come from the
platform core (`media/`) through `@veyq/core-js/media`, held by `contracts/platform/media/v1-vectors.json` on every client.

## Phases

1. The vectors from the web's own readers (`media.vectors.write.test.ts`, deleted in phase 2).
2. The core answers every case and writes the file (`MediaVectorsTest`); its readers without `Long`, `Regex`, `HashMap`.
3. The façade, `@veyq/core-js/media`, its package test and the slice check's row.
4. The web draws from it; the TypeScript and the ExifReader dependency deleted.
5. The gate measured; the harvest into ADR 0155; this CR deleted.

## Decision log

(kept by the implementer; moved into ADR 0155 § The media headers' slice at harvest)
