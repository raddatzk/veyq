---
id: cr-composition-tests-one-application
title: The composition's tests that name one application move to that application's module; the played server's platform half moves to :testing
status: in-progress
satisfies: []
adrs: ["0156", "0155", "0079"]
invariants:
  - "behaviour unchanged: no production source changes; the executed test cases per target are the same before and after"
  - "the direction holds: :testing reaches :core alone, an application's tests reach :core and :testing alone"
---

## Goal

Inbox #1260: about 40 of `:composition`'s test files name a single application, older than ADR 0156's rule (decision 6:
a test of one application runs in its module over `TestApplications.install`). Move them, with the played fixtures they
ride (`PlayedTenant`, `PlayedUploads`), so the rule holds for the old tests too; keep in `:composition` only what spans
applications or the composed registry, each with its reason; hold it with `validate_mobile_module_graph.py`.

## Plan

1. Measure: executed test cases per target (`build/test-results/*/*.xml`) after
   `./gradlew --continue compileKotlinJs compileTestKotlinJs jvmTest macosArm64Test`, and the files the guard's new rule
   names.
2. `PlayedTenant` and `PlayedUploads` to `:testing` (`offline/`), naming no application; Memories' banner and Expenses'
   Pots and receipts become extensions in their tests.
3. Each single-application test to its module, `AppComposition.install()` → `TestApplications.install(<its part>)`; one
   that fails over the inert parts goes back, with that as its reason.
4. Rule (g) of the guard, bitten; guard-gaps cases.
5. Measure again: the same cases per target, by name.

## Decision log

- **Fixtures to `:testing`, split by owner** (the preferred direction). `:testing` reaches `:core` alone, so the played
  server's application parts became test extensions: Memories' banner in `:apps:memories`, Expenses' Pots in
  `:composition` (five of six readers span applications). `PotRevision` carries raw sealed bytes, `Blob.role` is public,
  `Node.metaServed` takes the banner sealed at serve time (its wrap follows the node's key).
- **`OfflinePotTest` stays** beside the Pot half rather than over a second copy in Expenses' tests.
- **Move, then measure**: every single-application test moved with `TestApplications.install`; `GuestReportTest` went red
  over Memories alone (a Depot drop box) and went back.
- **Stays by earlier decisions**: `ImportScreenTest` (ADR 0156 entry 16), `ShareSheetTest`/`KindsContractTest` (composed
  registry), `ScopeRelockLiveTest` (relock of every application's roots).
- **Live cases moved unrun**; only the relock fails over inert parts, and only `ScopeRelockLiveTest` relocks.
- **Guard rule (g)** with a reasoned allow-list; bitten by guard-gaps 137–139.
- Harvested into ADR 0156 entry 78; this CR is deleted.
