---
id: cr-search-e2e
title: Lane L1 drives the search and the gallery filter in a browser, the place chip included
status: planned
satisfies: [uc.platform.search, uc.memory.gallery]
adrs: ["0090", "0091", "0125"]
invariants:
  - lane L1 runs on its own instance (`scripts/run_stack_e2e.py`, project `veyq-e2e-<stamp>`); the owner's `infra` stack and the `veyq-search` stack are never stopped, recreated or reused
  - a stage that needs a host resource held by the owner's stack (ports 80/443) is not forced; the run is reported as blocked, naming the holder
  - the wire assertion (canaries) still holds: no typed value reaches the server in the clear
---

## Goal

The search has unit and component tests, and several hand-driven browser passes on a throwaway stack. No CI lane drives
it. Add L1 specs (`tests/e2e/tests/features/platform/` for the search, `…/memories/` for the gallery filter, the
implementer places them) that run against the release candidate, as every other feature's specs do. Item 8 of the
owner's search follow-ups, 2026-11-09.

The specs cover:

- **Search:**
  - a Memory in another space found by name, by a year chip, by a month chip, by a person chip, and **by a place chip**;
  - a photo of another space found by a place and opened in its Memory's lightbox;
  - a person result opening the profile;
  - a settings result opening its section;
  - the sticky highlight (the owner's Ken/Kevin example);
  - no search field on a share page.
- **The gallery filter:** a day chip narrows the grid; the lightbox pages only those items; no field for a guest.
- **The place chip:** it was never driven in a browser, because setting a Memory's place asks for the maps opt-in.
  - The spec sets a place the way a member does.
  - If that needs the third-party map or geocoder, which a hermetic L1 run cannot reach (`tests/e2e/src/egress.ts`),
    use the path the existing Memories place specs use: `rg -ln "place" tests/e2e/tests/features/memories`.
  - If none exists, seal the place into the Memory's meta through the e2e's client-crypto helpers. A seeded place is
    not a UI-set one; say which it is.

## Context (substrate: branch `worktree-search-design`; this CR runs after `cr-search-photos-of-every-space`, `cr-gallery-filter-by-facts` and `cr-search-polish`)

- **Lane L1:** `scripts/run_stack_e2e.py` (its module docstring is the procedure: images, assets, Pebble, `veyqctl
  install`, the suite, the canary sweep), the overlay in `tests/e2e/stack/`, and the Playwright config
  `tests/e2e/playwright.config.ts`.
  - The suite reaches the header through `tests/e2e/src/header.ts`; accounts through `accounts.ts`; mail through
    `mail.ts`.
  - The canaries (`canaries.ts`) must cover the search queries typed. A query is a typed value, so the sweep must find
    none of them on the server.
- **Preflight:** stage 0 wants ports 80, 443 and the admin port free. The owner's dev `infra` stack runs a Traefik
  (`veyq-traefik`). Check whether it holds 80/443 (`docker port veyq-traefik`).
  - If it does, L1 cannot run on this machine while the owner's stack is up. Do not stop it. Write the specs, prove
    they compile and lint (`tests/e2e`'s own check), and report the run as blocked, naming the holder.
  - The owner then runs it, or allows a stop.
- **Machine:** L1 is heavy. Run it alone, with no other build or check on the machine. Check `uptime` first.
- **The UI words:** read them from `src/web/src/i18n/index.ts` and the applications' catalogs. The suite's locale is
  the one the other specs use.

## Phases

Single phase.

- **Build:** the specs and any helper they need (`tests/e2e/src/`), including the canary entries for the typed
  queries.
- **Run:** L1 once, or report it as blocked as above. If it runs, read the log to confirm the new specs executed, not
  only that the job passed (`validate_e2e_discovery.py` exists for that question).
- **Coverage:**
  - the `stack` refs on `uc.platform.search` and `uc.memory.gallery` criteria the specs prove;
  - the criterion tags `validate_stack_criterion_tags.py` reads.

  A stack ref counts only once a run has executed it. Follow `docs/coverage/README.md` on `stack` and on a surface
  awaiting its first run.
- **Done when:** `tests/e2e`'s check is green; L1 has run green, or is reported as blocked with the reason; the
  trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

(filled by the implementer)
