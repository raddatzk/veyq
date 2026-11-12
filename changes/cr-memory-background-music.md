---
id: cr-memory-background-music
title: Background music for a Memory — sealed playlists, played by the vendor's SDK in the music frame
status: in-progress
satisfies: ["uc.memory.music"]
adrs: ["0131", "0088", "0016", "0026", "0082", "0109"]
invariants:
  - "the playlist reference is sealed content in the node's wrappedMeta; the server never sees it"
  - "no vendor script runs in the app origin; the SDK runs only in the music frame, a page of the music host"
  - "nothing from a vendor or the music host loads before the viewer's opt-in, per browser and per service, withdrawable at the bar"
  - "vendor tokens never reach the veyq server"
  - "an instance without the music configuration works fully; a share guest gets what a member gets"
---

## Goal

Inbox #36 as the owner directed on 2026-11-12, in the form ADR 0131 decided.

## Phases

1. **Docs (refine):** ADR 0131, uc.memory.music, the catalog's three entries (source `deployment`), the guard's source.
2. **Contracts:** `Capability.spotifyClientId` / `appleDeveloperToken`, `serviceUrl` on `memories.music`; the export
   schema's `music`.
3. **Backend:** `veyq.music.*` config; the three entries active only while configured (an Apple token's `exp`); the wire
   values; the app shell's `frame-src`, Permissions-Policy and COOP while active; the music host serving `/music-frame`
   and its assets with its own policy (frame-ancestors from the instance's domains), nothing else; the Traefik router.
4. **Web:** `music` in the meta (parse, normalize, budget under 8192), the dialog, the bar with the consent, the frame
   (`music-frame.html`: PKCE for Spotify, MusicKit for Apple, the message protocol), export/import.
5. **Infra + published docs:** Compose and `.env.example`, the chart (values, schema, host, route, env), the Run pages,
   the Use page (EN+DE), the security model, cc.security.csp, the privacy notice; coverage; the L1 spec (written, not run).

## Locators

- Capability wire: `grep -rn "capabilityWire(" src/backend/core/src/main`
- The policies: `grep -rn "PERMISSIONS_POLICY\|COOP_HEADER\|frame-src" src/backend/core/src src/web/src/shared/security`
- Meta fields exported: `grep -n "banner\|participants" src/web/src/features/memories/exportImport.ts`
- Run config guard: `scripts/validate_run_config_reference.py`

## Definition of done

`./gradlew check` (src/backend), `pnpm run check` (src/web; tests/e2e lists the new spec), the guard glob green; the
owed live pass with real accounts named in inbox #846.
