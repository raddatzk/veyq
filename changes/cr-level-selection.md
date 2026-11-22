---
id: cr-level-selection
title: A level's Select on the four apps — a core level selection and `LevelTool.SELECT` (inbox #1049)
status: planned
satisfies: []
adrs: ["0154", "0122", "0110"]
invariants:
  - "a plan is drawn whole and in order (ADR 0154 decision 2): `LevelTool.SELECT` first in the tools, the selection bar's entries a plan enum every platform draws exhaustively"
  - "a selection acts only within each selected row's own rights (the web's `LevelRow.selection`, fail closed): an action is offered where every selected row grants it"
---

Refined 2026-11-22 while the plans were aligned (ADR 0154 § As built: the plans aligned, P12: too large to ride along). The web's level tools are
Select, Trash and the view switch (`src/web/src/core/node/LevelView.vue`, inbox #421); the core's `LevelPlan` holds Trash and
the view switch only, because no app selects on a level.

**What the web does** (the source to follow, ADR 0154's rule): *Select* turns the level into a pick mode; a bar shows the
count, *All*, *None*, and the actions every selected row's `LevelRow.selection` grants — move, copy, report, delete
(`deleteMany`: one question, each node with its kind's own write where it has one); *Cancel* leaves the mode.

**Measured on 2026-11-22** (the Explore map of `src/mobile`): no selection model in the core; `MoveSheet` takes one node,
`NodeTrash.delete` one node; `tree/MovePlan` already plans over a list (`hereBlock`, `plan`). Each platform's level
(`ShellScreen.kt`/`LevelTiles.kt`, `ShellView.swift`/`LevelTiles.swift`, `shell.rs`/`tiles.rs`, `ShellView.cs`/`LevelTiles.cs`)
needs a pick mode on rows and tiles and an action bar. Estimate: 300–500 lines of core with tests, 150–300 per platform.

## Phases

1. **Contracts:** a `selection` section in `contracts/platform/plans/v1-vectors.json` — rows' selection rights in, the
   bar's entries out (the web's rule over `LevelRow.selection`), read by `PlanVectorsTest` and the web's
   `plans.vectors.test.ts`.
2. **Core:** `navigation/LevelSelection` (ids, *All*/*None*, the bar's plan `SelectionAction`, `@Plan`), `MoveSheet` over a
   list, a batch trash with one Undo (`NodeTrash`), the bridge's calls; `LevelTool.SELECT` first in `LevelPlan.tools`.
3. **The four apps:** the pick mode, the bar drawn whole; the guard (`validate_mobile_structure.py`) holds the new enum.
4. **Harvest:** ADR 0154 § Consequences, inbox #1049 deleted; #1063 (a guest's move) reads the selection's move next.
