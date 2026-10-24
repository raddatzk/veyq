---
id: cr-profile-where-the-time-goes
title: Where does the time go? A profile of the client's and the server's hot paths, its method published in the Security area
status: in-progress
satisfies: ["uc.account.unlock", "uc.memory.upload", "uc.memory.gallery"]
adrs: ["0017", "0105", "0040"]
invariants:
  - no product code changes; a hotspot found here is an inbox row, and its fix a CR of its own
  - only synthetic content and test accounts are profiled; no profile file enters git or leaves this machine
  - a reading is not a target (architecture §10 "Deliberately unstated" stays true)
  - the release-candidate images are profiled, never `quarkusDev` and never the Vite dev server
---

## Goal

Owner 2026-10-23: *start veyq under a profiler and see where it could improve*, and publish the method and the result
in the Security area. The measurements so far answer **how long** and **whether it carries**
(`cr-volume-measurement-local`, the CAX CR, #489's targets); none answers **where the time goes inside** an
operation. This CR takes one profile per hot path, client and server, names each hotspot with the number behind it,
and writes the method and the reading into the test concept that a reader judging the project finds:
`site/src/content/docs/security/how-veyq-is-tested.md`.

Architecture §10 already says where the cost should be: *"the expensive paths (crypto, derivatives) run on the
client"*. That sentence is a claim nobody has profiled; this CR measures it.

## Context & constraints

- **Not a second volume harness.** Volume, server legs at scale and the gallery's targets belong to
  `scripts/measure_volume.py` and #489; this CR does not repeat them. It may boot and seed through that harness
  while it exists, but nothing it leaves behind may depend on it: the CAX CR's phase 5 deletes it.
- **Profile files carry secrets, both sides.** A browser heap snapshot or a trace with heap samples holds the
  decrypted content and the unwrapped keys of the session it ran in. On the server a JFR recording by default
  writes the process environment and system properties, and the production recipe passes
  `QUARKUS_DATASOURCE_PASSWORD` in the environment (`infra/docker/docker-compose.prod.yml:156`); a heap dump holds the
  KEK read from `VEYQ_SECURITY_CONFIG_SECRET_FILE` (`:177`). Operator-blindness is untouched: the server holds no
  content key to leak. The method still has to say how these files are handled, and that is the part that
  belongs in the Security area.
- **No CI job.** A profile is a reading on one machine, run by hand; it makes no coverage claim (ADR 0040).
- **Heavy work:** it does not run in a parallel wave beside another live proof.

### Measured substrate (this worktree at `5cdcb59c`)

| Fact | Locator |
|---|---|
| Content AEAD is AES-256-GCM in pure JS (`@noble/ciphers`), not WebCrypto | `src/packages/crypto/src/primitives.ts:10,43-45` |
| WebCrypto is used only for the device-bound keys and `pwned` | `grep -rln crypto.subtle src/web/src` → `rememberMe/sessionVault.ts`, `rememberMe/deviceKeyWrap.ts`, `trustDevice/deviceKey.ts`, `shared/pwned.ts` |
| Argon2id runs in a worker | `src/packages/crypto/src/argonWorker.ts`, `argon.worker.ts` |
| The runtime image is Temurin 21 **JRE** (no `jcmd`); the entrypoint is fixed, so JFR is switched on through `JAVA_TOOL_OPTIONS` | `infra/docker/Dockerfile:106,176` |
| Readings of the volume harness go to `.run/measurements/`, ignored by git | `.gitignore:295` |
| Unexplained server reading: the app peaked at 0.91 GiB of its 1 GiB limit during a 1,000-photo upload, although bytes go direct to the store | `changes/cr-volume-measurement-cax.md` § Local pre-check |
| Open measurement rows nearby: #261 (an upload's wire share; #220 waits on it), #260 and #221 (other browsers, after the V1 queue), #489 (gallery targets) | `docs/inbox.md` |
| Temurin's JRE (21.0.12+8) ships `jdk.jfr` and `jdk.management.jfr` (measured in phase 1) | `java --list-modules` in a local app image |
| The environment and the system properties are recorded by `jdk.InitialEnvironmentVariable` and `jdk.InitialSystemProperty`, and `<event>#enabled=false` in `-XX:StartFlightRecording:` switches each off on JDK 21 (measured in phase 1: a bare JVM in the JRE image, a canary variable found once in the recording without the two overrides, never with them) | `jfr summary`, `jfr print --events jdk.InitialEnvironmentVariable` |

## Decisions this CR takes

1. **Three scenarios, each profiled on both sides.**
   - **S1 unlock**: sign in with a password and open the space. Expected: Argon2id dominates by design
     (ADR 0017's parameters). The question is what else costs anything.
   - **S2 upload**: 50 phone-sized photos (about 4 MB each) into one Chapter. The split per stage: decode and
     derivatives, sealing (AES-GCM), hashing, presign, the wire, the server's commit. Server side: where the heap
     goes (the 0.91 GiB reading).
   - **S3 open a 1,000-photo Chapter** (the ADR 0105 grid): main thread against the listing worker, time per
     opened thumbnail.
2. **How each side is profiled.** Client: Chromium through Playwright, CPU profile and performance trace via CDP,
   three runs at CPU throttle 1 and 4, the machine idle (load ≤ 3) at the start. Server: the production recipe
   with JFR through `JAVA_TOOL_OPTIONS`, the environment and system-property events **off**, plus the number of
   SQL statements per request. No heap dump on either side: allocation is profiled through the sampled JFR
   events and the trace's own memory counters.
3. **One micro-reading on crypto throughput.** AES-256-GCM over 4 MB and 64 MB through `@noble/ciphers` against
   WebCrypto in the same browser, with the same key, nonce and AAD, both checked against the v1 conformance
   vectors. A reading only: a switch would change ADR 0017's suite and is an ADR and a CR of its own.
4. **What a reading is.** The top self-time functions and the stage split per scenario, with commit, machine,
   browser version and date. The files stay in `.run/profiles/` (ignored by git), are never committed and never
   attached to an issue. Only the numbers move into docs.
5. **Where the result lands.**
   - Architecture §10, under "Deliberately unstated": one paragraph saying how performance is measured instead
     of targeted (this CR's method, in brief). It is the docs source the site page cites.
   - The Security page *How veyq is tested*, inside its test concept rather than beside it (owner 2026-10-23):
     **Profiling** becomes a row of § The stages and of § Where and when each stage runs (no job; by hand, with its
     last run). The row says plainly what sets it apart from every other stage: it is a reading, nothing in it
     can turn red. A short subsection under the stages holds what the rows cannot: how profile files are handled
     and why (the context above), and the reading with its limits (one machine, Chromium only, synthetic data).
     § Re-run it gains its command. The page's `sources:` gains `docs/architecture/README.md`.
   - ADR 0091 is not touched: its stages are the per-commit pipeline, and a hand-run reading is not one of them.
   - One inbox row per hotspot, each with its number; §11 only for a hotspot that limits a household.

## Open questions (owner)

**All decided** (owner 2026-10-23, "Ansonsten passen die drei"): each recommendation is taken.

- **Q1: This CR absorbs #261.** S2's stage split measures exactly the wire share #261 asks for; phase 3 deletes
  #261 with S2's reading and gives #220 its number.
- **Q2: The profile driver stays in the tree**, small and on its own (`tests/e2e/profile/` and
  `scripts/profile_hot_paths.py`), seeding through the e2e bootstrap and not through the volume harness, so the
  page's § Re-run it holds. Run by hand, no CI job.
- **Q3: It runs at the V1 heavy stage** together with #489's reading (same instance, same seed, one quiet
  machine); phase 1 can come earlier.

## Phases

- [x] **1 driver and method**: the profile driver (Q2), JFR through `JAVA_TOOL_OPTIONS`, the remembered facts of
  the substrate section measured. · Done when: S1 runs end to end once and writes its files to `.run/profiles/`;
  `git status` shows none of them. **Bite:** a JFR recording taken with the run's settings and a canary variable
  (`VEYQ_PROFILE_CANARY=<random>`) in the app's environment does not contain the canary; the same recording
  without the settings does.
  *Landed 2026-10-23* (`scripts/profile_hot_paths.py`, `tests/e2e/profile/`): S1 ran end to end once (one run at
  throttle 1 and one at 4, on a busy machine, `--ignore-load`, so not a reading). The bite is part of every run, not a
  one-off: the run's recording held neither the canary nor any of the `.env`'s four secrets; the control recording
  held the canary, and with it `MARIADB_PASSWORD` and `GARAGE_ADMIN_TOKEN` — the CR's concern, measured. Two things
  phase 2 inherits: L1's overlay already passes the app's truststore through `JAVA_TOOL_OPTIONS`, so the recording is
  appended to it, never set (setting it broke the store check with PKIX); and throttle 4 barely moved S1 (3.37 s →
  3.52 s), which suggests Chromium's CPU throttle does not reach the Argon2id worker — read that before a throttle-4
  number is taken as a slower device's.
- [x] **2 readings**: S1–S3 on both sides, three runs each at throttle 1 and 4; the crypto micro-reading
  (decision 3). · Done when: every scenario has a stage split and its top functions, and each hotspot found has
  the number that justifies it.
  *Taken at the V1 heavy stage*: S1, S3 and the crypto reading in the collection run (2026-10-23, `87266fe3`), S2's six
  runs with the JFR bite and `meta.json` in fix round 1 (2026-10-24, `d4eab6be`), once F7 no longer made the strict
  request check refuse it. The server side was read from the two recordings at phase 3 (`jfr summary`, `jfr view
  hot-methods`, `jfr view cpu-load`, `jdk.GCHeapSummary`): the JVM averaged 0.3–0.6 % user CPU, its top method held
  under 4 % of 223–276 execution samples, the heap peaked at 171–173 MB of 616 MB. Hotspots: #536 (sealing), #537 (two
  N+1 shapes); readings without a row: the throttle (ADR 0105 § Targets), S1's idle main thread, the wire share (#220).
- [x] **3 record**: decision 5's places; #261 deleted (Q1), #220's row given S2's wire share. · Done when: the site check and every
  `scripts/validate_*.py` except `validate_guard_gaps.py` green.
  *Done 2026-10-24*: architecture §10's paragraph, the Security page's Profiling rows, its subsection and § Re-run it,
  ADR 0105 § Targets' throttle sentence, inbox #536–#538, #220 given 9.5 %, #261 and #526 deleted.

## Definition of done

Every phase's Done-when; the Security page's test concept lists profiling as a stage and says how profile files
are handled and what the reading showed; no profile file in git; no product file changed.

## Retrospective

- **Chromium's CPU throttle does not reach workers, and the method assumed it did.** Decision 2 took "throttle 4" as a
  slower device; phase 1 saw S1 barely move and the collection run measured it (the Argon2id worker 5,354 vs 5,413 ms,
  the main thread ×4.5). Root cause: an emulation was read as the thing it emulates without one control run. Moved to
  ADR 0105 § Targets, the driver's limits and the Security page. Next time: a refine that leans on an emulation names
  the control that shows what it does not reach.
- **A JFR recording leaks the environment unless its two events are off.** The control recording held the canary, the
  database password and the store's admin token (phase 1). The CR had named the risk from the recipe, which is why the
  bite existed from the first run; it lives on in the driver's per-run bite and on the Security page.
- **A strict "no failed request" check exposed F7.** The driver refused S2 because Chromium reported unread empty bodies
  as `ERR_ABORTED`; excluding aborts as noise would have hidden a product defect that also held #489 at
  `carries: false`. Kept in the driver's docstring and in `changes/README.md` § A false positive in a measurement is
  explained, not excluded.
- **Process:** phase 2 was run inside the heavy stage's collect-only run, so its server half (the JFR reading) was taken
  at phase 3 from files left on disk, not in the run. Next time: a phase's Done-when names who reads which file, so a
  run that only collects does not leave a reading unowned.
