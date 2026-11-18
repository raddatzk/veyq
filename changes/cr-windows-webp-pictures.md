---
id: cr-windows-webp-pictures
title: Windows without the WebP Image Extension — the members' pictures said or decoded (#885)
status: planned
satisfies: ["uc.spaces.set-profile-picture"]
adrs: ["0139", "0127", "0136"]
invariants:
  - "a picture is opened on the device only, as now; nothing about it reaches the server or a third party"
---

## Goal

On a Windows PC where the person removed Microsoft's WebP Image Extension, every member shows their initials and nothing
says why (inbox #885). Either say it and offer the extension, or decode the pictures without it. **Blocked on the owner's
answer below**; everything else of V2 block B is `cr-the-apps-alike-block-b`.

## Context & constraints

- Re-derived at `43918c9b0` (measured): the web writes every member's picture as WebP (ADR 0127). Windows decodes it through
  WIC (`Avatars.cs`, `BitmapImage`), which has WebP only through the extension; `AvatarsTest`'s WebP case is the decode test
  whose bite showed the fallback to initials (ADR 0139 decision 6, native-apps § Open, measured 2026-11-11). The extension is
  provisioned in Windows 11's image and removable; a sideloaded MSIX cannot depend on it (no framework package, ADR 0139
  decision 6). Only the members' pictures are WebP: covers, tiles and previews are JPEGs the uploading client made.
- No word in `src/mobile/words/words.json` says this (`grep -n -i 'webp\|extension' src/mobile/words/words.json`: only the
  iOS share extension's sign-in hint).

## The question (product and copy — the owner's)

**A. Say it** (cheap, recommended): when a picture the core holds does not decode, the shell says it once per session in its
`InfoBar` — en "Members' pictures need Windows' WebP Image Extension." / de "Für die Bilder der Mitglieder braucht Windows
die WebP-Bilderweiterung." with the action en "Get it" / de "Herunterladen", opening the Store's page
(`ms-windows-store://pdp/?ProductId=9PG2DK419DRG`). A Store page is Microsoft's, so the click leaves the app; nothing is sent
by the app. Cost: two words, one `InfoBar`, a test that a failed decode raises it once.

**B. Decode it in the app**: build libwebp's decoder (the source ADR 0127 already pins for the web) into the core's
Windows build and hand the host pixels it can draw without WIC. People then see the pictures with no step of theirs. Cost:
a C library in the mingw build (cinterop, its pin, its own update path), a pixel hand-off on the wire, for a case that needs
the person to have removed a component Windows ships.

Recommendation: **A**, because the affected case is rare and self-inflicted, and B adds a native dependency and a build
path for it; B stays the answer if the owner wants no step of the person's. The wording above is a proposal.

## Phases (after the answer)

- [ ] 1 the chosen path with its test red first (A: a picture that does not decode raises the notice once, a decoded one
  does not; B: `AvatarsTest`'s WebP case decoded without WIC). · Done when: `windows/run.py build` green.
- [ ] 2 a pass on the VM with the extension removed and put back (the owner's: a system component, VM settings are theirs).
- [ ] 3 harvest — ADR 0139 decision 6 and native-apps § Open updated, the site's *Install the app* (EN+DE) names it under
  Windows' needs if A, inbox #885 deleted, this CR deleted.

## Definition of done

As the phases; `scripts/validate_*.py` green.

## Retrospective

(filled at the end)
