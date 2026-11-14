---
id: cr-remove-streaming-music
title: Background music's streaming services go — the music frame and host, the vendor configuration, the playlists and the release flag
status: planned
satisfies: []
adrs: ["0135", "0131", "0088", "0082", "0035", "0079"]
invariants:
  - "after this CR the app's CSP keeps frame-src 'none', its Permissions-Policy is the constant PERMISSIONS_POLICY and its COOP is same-origin on every instance (ADR 0135 decision 1)"
  - "no capability entry has the source `deployment`, and no `memories.music` id is advertised (ADR 0135 decision 1)"
  - "an export written before this CR that carries `music` imports without an error, and the playlists are not restored (decision 4 below)"
---

## Goal

Remove everything [ADR 0131](../docs/decisions/0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md)
built, as [ADR 0135](../docs/decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md)
decision 1 says. That covers the Spotify and Apple Music playlists in a node's meta, the music frame on its own host,
the vendor configuration, the widened app policies, the privacy-notice passage and the temporary release flag. It also
covers the two shell seams music was the only user of (`app.footerBar`, `onSessionEnding`). The instance is left as it
was before ADR 0131. `cr-background-music-from-own-audio` builds the replacement afterwards. This CR adds nothing.

## Context & constraints

- Measured on `main` at `8574dda3b` (2026-11-14) by a delegated sweep, from four keys. Their union is the inventory
  below. One fact to **spot-check before phase 2**: `TenantsResource.kt` reads `o.deployed` (around line 464), and only
  the `deployed` key finds it. It is the file the `music` locator misses.
- **Music was released dark.** No instance served it unless `VEYQ_MUSIC_RELEASED=true` was set. The e2e overlay sets
  it, and the owner set it on test instances. So no production data depends on it. The leftovers are named in ADR 0135's
  Consequences and are left in place (decision 5).
- **On this Mac, `git grep -E` has no `\b`.** Use `-w` or `-P`. Otherwise `\b0131\b` silently matches nothing.
- Backend `:apps:memories` has nothing to change: no locator hits it.

## Decisions (technical, settled here)

1. **ADR 0131 stays, marked `Superseded by 0135`.** That was done at refine. Every *citation* of it in code, contracts,
   site and state docs goes, along with the code it explains. `validate_doc_citations.py` keeps the remaining
   citations resolvable.
2. **The `deployment` capability source goes.** Its only entries were the three `memories.music` ones. This removes
   `DeploymentCapabilities`, the `deployment` constructor parameter of `CapabilityRegistry`, `deployed` on the wire
   and in the handlers, and `"deployment"` from `scripts/validate_capabilities.py`'s `KNOWN_SOURCES`, with its 0131
   docstring. A source with no entry would only be a door left open. `serviceUrl` on `Capability` stays, because the
   external services use it. Only its `memories.music` sentence goes.
3. **The app policies go back to constants.** `ExternalOriginSet.frame`, `withOrigins(frame=)`,
   `permissionsPolicy(frame)` and the COOP switch go. The result is `frame-src 'none'`, `PERMISSIONS_POLICY` and
   `same-origin`. The music host's own policy (`musicFramePolicy`, `MUSIC_PERMISSIONS_POLICY`,
   `SPOTIFY_PLAYER_ORIGIN`) goes with its route.
4. **The export drops `music`.** The importer **ignores** a `music` key on a memory or chapter instead of refusing it.
   The schema's memory and chapter objects carry no `additionalProperties: false`, so an older archive still validates.
   A unit test imports one that has the key: it imports fine, and no playlist ends up in the meta.
5. **No migration and no client clean-up.**
   - A `memories.music…` switch row is filtered by `CapabilityIds.ADVERTISED`.
   - A `music` field already sealed into a node's meta is read by nothing. A re-key carries it unchanged
     (`rekeyScope.test.ts`, which uses `music` as its sample of an unknown plain field: switch the sample to a neutral
     name, keeping what it proves).
   - The `veyq:music-consent` key stays in the browsers that hold it.
6. **The privacy notice loses its `background-music` passage**, and its `revision` moves, as the file's own note
   requires. Nothing accepts the notice, so the bump reopens nothing. `PrivacyNoticeSliceTest`'s "no background-music
   passage" assertion goes with the passage.
7. **Both shell seams go: `app.footerBar` and `onSessionEnding`.**
   - Removed: the registry's door, `core/footerBar/FooterBars.vue` and its two mounts (`AppShell.vue`,
     `ShareView.vue`), `app/sessionEndingSteps.ts` and the 2 s budget in `app/sessionEnd.ts`. `AppShell.footerBar.test.ts`
     goes with the slot.
   - Kept: the sign-out's `veyq-end-session` to the service worker. It is not part of the seam.
   - The next CR adds core's own bar to the shell, not as a door for applications.
8. **The `music` icon stays** in `VeyqIcon.vue` and `icons.ts`, because the next CR's header button uses it. Every
   other `features/memories/music/` file goes, including its tests.

## Inventory (locators — run, read as lists)

| Key | Command | Finds |
|---|---|---|
| The word | `git grep -il music` | most sites; misses the five below |
| The ADR | `git grep -lw 0131` | 0131 citations. False hits: `scripts/make_exif_fixture.py` (EXIF tag 0x0131), both `eslint.config.js` (an old cr id), a PNG |
| The wire and source | `git grep -lw deployed -- src` and `git grep -l -e DeployedValues -e DeploymentCapabilities` | `TenantsResource.kt`, `CapabilityRegistry.kt` |
| The seams | `git grep -l -e onSessionEnding -e SessionEnding -e footerBar -e FooterBar` | `FooterBars.vue`, `sessionEndingSteps*.ts`, the registry and its tests, both mounts |
| The flag | `git grep -ilE 'music[._-]?released'` | the flag's sites (inbox #859's list) |
| The guard's source | `git grep -n '"deployment"' -- scripts contracts` | `validate_capabilities.py`, `capabilities.json` |

Grouped by phase, from that sweep:

| Phase | Delete | Edit |
|---|---|---|
| 1 contracts | — | `contracts/platform/capabilities.json` (the three entries); `contracts/api/_common.openapi.yaml` (`Capability.spotifyClientId`, `.appleDeveloperToken`, the `memories.music` sentence under `serviceUrl`); `contracts/api/setup.openapi.yaml` (`musicReleased`); `contracts/terms/privacy-notice.json` (the passage, `revision`); `contracts/export/memories/content.schema.json` (`music` on memory and chapter, `$defs.music`, the chapter description) |
| 2 backend | `appshell/MusicFrameHost.kt`, `platform/capabilities/MusicDeployment.kt`, `MusicFrameHosts.kt`; tests `MusicDarkSliceTest`, `MusicFrameSliceTest`, `MusicSettingsTest`, `TraefikFileMusicRouteTest`; `src/test/resources/META-INF/resources/music-frame.html` | `VeyqConfig.Music`; `CapabilityRegistry`, `CapabilityWire`, `ExternalServices`, `ExternalOrigins`, `AppShell`, `AppShellRoutes`; `SetupHandler`, `SetupResource`; `TenantCapabilitySwitchHandler`, `TenantsResource`, `TenantHandler`; `TenantIngressSinkProducer`, `TraefikFileIngressSink`; `application-test.properties`; `CapabilitySwitchFixtures.UNCONFIGURED_IN_TEST` and its users; the asserting tests the locators name (`CapabilityRegistryTest`, `CapabilityManifestSliceTest`, `InstanceCapabilitiesSliceTest`, `TenantCapabilitySwitchesSliceTest`, `PrivacyNoticeSliceTest`) |
| 3 web | `src/web/music-frame.html`, `src/web/src/music-frame/`, `src/features/memories/music/`, `core/footerBar/`, `app/sessionEndingSteps*.ts`, `AppShell.footerBar.test.ts` | `vite.config.ts` (the `music-frame` input); `shared/state/capabilities.ts`, `useCapabilities.ts` (+ test); `src/test/setup.ts`; `features/platform/capabilities.ts`; `core/applications/registry.ts` (+ test); `app/sessionEnd.ts` (+ test); `AppShell.vue`, `ShareView.vue`; `features/memories/register.ts`, `lifecycle.ts`, `components/MemoryDetail.vue` (+ `.rights.test.ts`), `exportImport.ts` (+ test), `banner/bannerModel.test.ts`, `i18n.ts`; `core/node/rekey/rekeyScope.test.ts`; `src/web/CLAUDE.md` (the footer-bar and music-frame rules) |
| 4 infra, e2e, guard | `infra/kubernetes/charts/veyq/ci/music-values.yaml`; `tests/e2e/tests/features/memories/memory-music.spec.ts` | `infra/docker/docker-compose.prod.yml`, `infra/docker/.env.example`; the chart's `values.yaml`, `values.schema.json`, `templates/_helpers.tpl` (`veyq.musicDomain`, the `https-music` listener), `app-deployment.yaml`, `app-route.yaml`; `tests/e2e/stack/docker-compose.e2e.yml`; `scripts/validate_capabilities.py` |
| 5 docs, site, inbox | `docs/features/memory/use-cases/uc.memory.music.md` and its coverage file `uc.memory.music.yaml` | `docs/crosscutting/cc.security.csp.md` (the frame-src widening, § The music frame, COOP and Permissions-Policy lines); `docs/crosscutting/security-model.md` (the third-party "fourth instance" passage); `docs/features/memory/_index.md`; `docs/features/memory/enablers/en.memory.node-meta.md` (`music`); `docs/features/account/use-cases/uc.account.sign-out.md` (the ending step); `docs/apps/memories.md`; site `run/capabilities.mdx` (§ Background music, its 0131 `sources:` entry), `run/configuration.md` (the `MUSIC_*` and `music.*` rows, its 0131 `sources:` entry), `security/threat-model.md` (the music-service row); `docs/inbox.md` (#849's music-host example) |

**The chart's upgrade check:** `chart_upgrade_check.py` renders the candidate with the *predecessor's* `ci/*.yaml`.
That includes the old `music-values.yaml`, which now meets a schema without `music`. Phase 4 runs it, and then either
the schema tolerates the key for one release or the check names that file as removed. Decide by what the check
already does for a removed values file (`git log -S'ci/' -- scripts/chart_upgrade_check.py`).

## Phases

**Phase 1 — contracts.** Decisions 2, 4 and 6 at the contract level. Done when: `pnpm run generate:api` and the
contracts guards (`validate_capabilities.py` once phase 4's source removal is in, `validate_field_visibility.py`) are
green, and the predicted reds are named. Those are the backend lines that build the removed `Capability` fields and
`musicReleased`, and the web's readers of `musicReleased` and `MEMORIES_MUSIC*`. Run `pnpm run check` once to see them.

**Phase 2 — backend.** Decisions 2, 3 and 6. Test what remains:
- the app shell answers `frame-src 'none'`, the constant Permissions-Policy and COOP `same-origin` on an instance with
  every former `VEYQ_MUSIC_*` variable set. This is the positive control that the removal reached the headers, not
  only the config.
- a host that used to be the music host gets the ordinary unknown-host answer.
- the capability listing names no `memories.music` id even where a switch row for one exists.

Done when: `./gradlew check` is green.

**Phase 3 — web.** Decisions 4, 5, 7 and 8. Unit tests:
- the importer case of decision 4;
- `sessionEnd` still ends the service worker's playbacks and loads the sign-in page;
- the registry exposes no `footerBar` and the lifecycle no `onSessionEnding`.

Done when:
- `pnpm run check` is green;
- `git grep -il music -- src/web/src` lists only the `music` icon and the replaced rekey sample, if that kept the word;
- a browser pass on the dev instance shows a Memory's ⋯ menu and header with no music entry, no console error, and
  sign-out still reaching the sign-in page.

**Phase 4 — infra, e2e, guard.** Decision 2's guard line, and the inventory row. Done when:
- the chart's checks are green (`chart_meta_check.py`, `chart_upgrade_check.py`, with the decision above recorded in
  the commit);
- `docker compose -f infra/docker/docker-compose.prod.yml config` is clean;
- `validate_run_config_reference.py` and `validate_capabilities.py` are green.

**Phase 5 — docs, site, inbox.** Delete `uc.memory.music` and its coverage file. Edit the state docs and site pages in
the inventory, so that each one describes the instance without music: the CSP doc gets its constant values back, and
the security model's list of third-party services shrinks by one. Remove #849's music-host example; its subject (a
sibling host and the refresh call) stands without it. Done when: every `scripts/validate_*.py` guard and the site's
own check are green, and `git grep -lw 0131` lists only ADR 0131, ADR 0135, `docs/decisions/README.md` and the
false hits named above.

## Definition of done

Every phase's done-when is met. `git grep -il music` lists only ADR 0131, ADR 0135, `uc.memory.background-music`
and its coverage, the enabler `en.platform.audio-player`, `docs/inbox.md` (#36, #847), the `music` icon, and
`changes/`. Every remaining hit is spared by name. This CR's lessons are moved to where they bind, and this file is
deleted.

## Retrospective

_(filled by the implementer)_
