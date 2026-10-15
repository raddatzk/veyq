---
id: cr-one-major-from-version
title: The protocol major is derived from VERSION; the precise release stays Server-Owner-only
status: in-progress
satisfies: ["uc.platform.capability-discovery"]
adrs: ["0085", "0057", "0032"]
invariants:
  - "Operator-blind: /api/setup stays pre-auth, identical for every caller, and advertises the major and nothing more precise"
  - "Self-hostable by default: the major travels inside the build, so neither runtime target (compose, Helm) needs a value for it"
---

## Goal

ADR 0085 decision 1, the part due before V1 (inbox #436): `VERSION` is the platform's one major, and what
`GET /api/setup` advertises as `version.major` is derived from it instead of set by hand. The web client's compiled
major follows the same file. The precise release `<major>.<minor>` stays Server-Owner-only (ADR 0057 §4).

## Measured (2026-10-15, this worktree)

- `cat VERSION` → `0`; `grep -n 'veyq.version.major' backend/core/src/main/resources/application.properties` → `35:veyq.version.major=1`.
- `VeyqConfig.Version.major()` `@WithDefault("1")`, read once by `SetupHandler`; no test profile, compose file or
  chart sets the key (`grep -rn 'version.major\|VERSION_MAJOR' infra charts backend` → assertions only).
- `web/src/shared/state/capabilities.ts:7` `export const EXPECTED_MAJOR = 1`; the gate in `useCapabilities.ts:24-25`.
- "never merged" wording: `contracts/api/instance.openapi.yaml:9`, `contracts/api/setup.openapi.yaml:190`,
  `application.properties:39`, `VeyqConfig.kt:84`, `ReleaseIdentity.kt:12`, `InstanceReleaseSliceTest.kt:63`,
  `admin-web/src/features/instance/ReleaseVersionPanel.vue:9`.
- Precedent for a build-generated constant from a repo file: buildSrc's `GenerateCapabilityRegister`
  (`contracts/platform/capabilities.json` → `CapabilityIds`). Precedent for a build-time web constant:
  `__PDFJS_VERSION__` / `__APP_VERSION__` in `web/vite.config.ts` `define`.

## Decisions

1. **Transport: a generated compile-time constant, not a stamp and not config.** buildSrc gains
   `GeneratePlatformMajor`, registered for `:core`, which reads `../VERSION` (from the Gradle root, as the specs
   are read) and writes `PlatformMajor.MAJOR` into `me.raddatz.veyq.core.platform.capabilities`. `SetupHandler`
   reads it; `veyq.version.major` and `VeyqConfig.Version.major()` are removed.
   - Not `VEYQ_VERSION`: that stamp is absent in every build the pipeline never stamped (dev, tests, a local
     `docker build`), and absence is its first-class answer. The gate's major cannot be absent.
   - Not config: a config key is settable by env (`VEYQ_VERSION_MAJOR`) on either runtime target, which would let
     one instance advertise a major its code does not speak. The jar carries the number, so compose and Helm need
     nothing, and `container.yml` needs nothing beyond what it already does (it reads `VERSION` for the tag); the
     root `Dockerfile`'s backend stage copies `VERSION` in beside `contracts/`.
2. **Major 0 is advertised as 0; no floor.** ADR 0085 § Consequences moves `VERSION` and the advertised major to
   `1` together at V1; a floor (`max(VERSION, 1)`) is a second rule, i.e. the two counters ADR 0085 rejects. The
   window counts released majors only, so no store client will carry a `0` adapter, and the two clients that exist
   ship in the server's own image. The contract already allows it (`minimum: 0`). Cost: a tab still holding a
   bundle compiled at `1` sees "update this server" against a rebuilt dev instance until it reloads.
3. **The web's `EXPECTED_MAJOR` is derived from `VERSION` too** (`__VEYQ_MAJOR__`, a `define` in `vite.config.ts`;
   the root `Dockerfile`'s frontend stage copies `VERSION`). The gate stays an equality (ADR 0085 decision 6). So
   the V1 commit stays what `infra/README.md` says: `VERSION` and the `release:` pin line, no third file.
4. **No new script guard; executed tests hold "cannot diverge".** Each reads `VERSION` from disk, independently
   of the derivation it checks: `CapabilityManifestSliceTest` asserts `/api/setup`'s `version.major` equals it;
   `web/src/shared/state/capabilities.test.ts` asserts `EXPECTED_MAJOR` equals it. A text guard over
   `SetupHandler` / `capabilities.ts` would prove a spelling, these prove the served value.

## Bite-tests

- `CapabilityManifestSliceTest` major-equals-VERSION — **live defect**: written first, red on the untouched tree
  (`1` vs `0`). Then **injection**: `SetupHandler`'s major hard-set to `1` after the change → red; restored by
  snapshot.
- `capabilities.test.ts` — **live defect**: red against `EXPECTED_MAJOR = 1`. Then **injection**: the constant
  hard-set to `1` again → red; restored by snapshot.

## Phases

1. **Contracts** — `setup.openapi.yaml` `Version.major` (one major with the release, removal-only, the 1 May train,
   the client window; still the major only) and `build`; `instance.openapi.yaml` intro. Description-only: no
   generated shape changes. Done when: contracts guards green.
2. **Backend** — generator, `SetupHandler`, config and wording, `Dockerfile`, tests. Done when:
   `./gradlew --no-watch-fs check` green.
3. **Web + admin-web** — `__VEYQ_MAJOR__`, `capabilities.ts`, test fixture, the new test; `ReleaseVersionPanel.vue`
   wording. Done when: `npm run check` green in both.
4. **Docs + harvest** — ADR 0085 § Consequences, ADR 0057 §4, `docs/features/admin/_index.md`,
   `site/.../run/upgrade.mdx`, the capability-discovery use-case + coverage, inbox #436. CR deleted. Done when: the
   full guard glob is green.
