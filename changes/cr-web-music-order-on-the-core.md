---
id: cr-web-music-order-on-the-core
title: The music order and fades drawn from the Kotlin core on the web — ADR 0155 decision 7.2's seventh and last slice
status: in-progress
satisfies: []
adrs: ["0155", "0156", "0135", "0142"]
invariants:
  - "the web's own answers are the vectors' first source (phase 1); the core answers every case before the web draws from it, and then writes the file"
  - "the randomness stays handed in: a case scripts every draw and the `n` it is asked with, so Fisher–Yates' call order is part of the contract"
  - "the TypeScript it replaces is deleted; the player calls the façade, no adapter holds a rule"
  - "no Long on a path the façade reaches, no kotlinx.serialization, kotlinx.coroutines, Ktor or refusal in what an entry ships"
  - "the gate: the platform module within its running budget (R = 3.0 × the deleted TypeScript's gzip), the eager set by at most R × that, no lazy chunk +1 KB gz, each hot rule ≤ 2.5× the TypeScript's median and ≤ 100 ms; a miss after one optimisation round goes to the owner"
---

## Goal

The order a music queue plays in (`PlayOrder`: shuffled, endless, a round never starting with the track that ended the
one before, previous restarting after 3 s, a dropped track leaving every round) and where and how long a crossfade runs
(`autoFadeMs`, `pressFadeMs`, `fadeOutAtMs`, `fadeLengthAt`) come from the platform core (`audio/`) through
`@veyq/core-js/audio`, held by `contracts/platform/audio/v1-vectors.json` on every client. The core already had both as
ports of the web's TypeScript (ADR 0142 decision 1); this slice makes them one.

## Phases

1. The vectors from the web's own TypeScript (`music.vectors.write.test.ts`, deleted in phase 2).
2. The core answers every case and writes the file (`MusicVectorsTest`); the fades on `Double` as the web's, no `Long` on
   the façade's path.
3. The façade, `@veyq/core-js/audio`, its package test and the slice check's row.
4. The web draws from it; `order.ts` and `fades.ts` deleted, the player calls the façade.
5. The gate measured; the harvest into ADR 0155 (decision 7.2's list closed); this CR deleted.

## Decision log

(kept by the implementer; moved into ADR 0155 § The music order and fades' slice at harvest)
