---
id: cr-node-header-is-cores
title: Core draws every node page's header; an application contributes actions, facts, a subtitle and a banner as data
status: done
satisfies: ["uc.platform.node-cover", "uc.memory.participants", "uc.depot.view-file"]
adrs: ["0110", "0079"]
invariants:
  - a share page links no person and fetches no member picture (src/web/CLAUDE.md, "A member's profile picture"; ADR 0083)
  - Report is on every node's header (DSA Art. 16, ADR 0093 § 1, ADR 0110 decision 9)
  - core imports no application (ADR 0079 decision 4)
---

## Goal

The owner's principle (2026-11-03): generalise into core whatever takes no function away from an application. The header
row is the same for every application, so core draws it - core's node page for every kind, the level for a folder - from
the kind's descriptor (icon, cover, priority) and from what the page's detail contributes as data: a subtitle, its actions,
its facts (a date, a place, people, a line of text) and a banner. A cover is core's for every kind that takes one, 244 px
wide in every header. Memories keeps its banner, its date, place and participant editors and its body; Depot names the
uploader as a people fact; no application draws a header of its own.

## Context & constraints

- [ADR 0110](../docs/decisions/0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md) (gains decision 10),
  [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 3 (around a node is core's).
- `src/web/CLAUDE.md` § One header for every node, § Covers are core's, § What an application registers, § People and
  participants, § A share view is the member view.
- Web only: no contract, no backend change.

## Decisions this CR takes

1. `NodePage` renders `.veyq-node-page` (an inline-size container): the contributed banner, core's `NodeHeader`, the detail.
   `LevelView` draws the folder's header from FOLDER's descriptor through the same component.
2. Static per kind → the descriptor: `icon` (exists), `cover` (exists), new `header: { order }` (`KindHeader`).
3. Dynamic → `contributeNodeHeader(() => ({ subtitle?, actions?, facts?, banner? }))`, `core/node/nodeHeaderContribution.ts`,
   a no-op without core's page.
4. Facts are typed data core draws (`NodeHeaderFact`: `date` | `place` chip, `people`, `text`); people are core's
   `core/people/PersonGroup.vue`. `NodeHeader.linkPeople` defaults to false; `NodePage` sets it for a member's access only.
5. The header's leading visual is core's: the cover (244 px wide, 16:9; 64 px compact) or the icon tile (4rem; 42 px
   compact), a button opening core's cover dialog where `coverRights` allows. The `leading`/`meta` slots go.
6. Guard: `validate_web_core_imports_no_applications.py` gains the `header` rule (no `features/<owner>` file imports
   `core/node/components/NodeHeader.vue`), pinned by guard-gaps case 107.

## Inventory (locators)

- Header renderers: `grep -rn "<NodeHeader" src/web/src` - after: `core/node/LevelView.vue`, `core/node/components/NodePage.vue`.
- Tests on the old shape: `grep -rlE "veyq-node-header|memory-date-chip|memory-cover-preview|participants-group|headerButtonIds|file-uploader" src/web/src tests/e2e`.
- Docs: `git grep -nE "leading slot|meta slot|180 px|7\.125|header's meta line" -- docs site src/web/CLAUDE.md`.

## Phases

- [x] 1 web — core header + contribution API, each application moved, tests · Done when: `cd src/web && npm run check` green
- [x] 2 guard — the `header` rule, bitten · Done when: `python3 scripts/validate_web_core_imports_no_applications.py` red on
      a probe, green without; `python3 scripts/validate_guard_gaps.py --force` green
- [x] 3 docs — ADR 0110/0079, web CLAUDE.md, apps docs, styleguide, use cases, coverage, site (EN+DE) · Done when: the doc
      guards green, `site/` `npm run check` green

## Definition of done

`npm run check` (web) green; every `scripts/validate_*.py` green; guard-gaps forced green; `docs/coverage/` names the new
core tests where a criterion moved to them; the site's set-a-cover and people pages (EN+DE) say what changed.

## Retrospective

- The uploader's spoken line lost its space ("Uploaded byAnna"): Vue condenses whitespace between elements, and the old
  template's explicit `{{ ' ' }}` was not carried into the core component. Its test (`spoken`) caught it. Next time: port a
  component's deliberate text nodes with its markup, not only its classes.
- A negative test kept asserting the absence of `data-testid="file-uploader"` after the markup moved - green on a selector
  nothing draws. Found only by grepping the old names. Next time: after a move, grep every old test id and class.
- What a detail contributes reaches the header one render after the mount; tests that read the header synchronously went
  red until they awaited a tick. Expected, and written into `src/web/CLAUDE.md`.
