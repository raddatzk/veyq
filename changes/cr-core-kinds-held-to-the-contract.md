---
id: cr-core-kinds-held-to-the-contract
title: The Kotlin core's kinds answer contracts/platform/kinds/ in a test, as the backend and the web do
status: in-progress
satisfies: []
adrs: ["0156", "0079"]
invariants:
  - "a kind's facts are written once, in its owner's kinds file; each client keeps its own code and is held to the file by a test"
  - "the facts are asked through the members the apps read, never through a copy kept for the test"
---

## Goal

Inbox #1061: placement, cover, share roles, the guest's creates and the guest's container live in
`contracts/platform/kinds/*.json`; the backend (`KindsContractTest`) and the web (`kindsContract.test.ts`) answer them,
the Kotlin core spells them by hand (`AppKinds`, each application's `ApplicationKinds`, `ShareSheet`, `GuestLevel`,
`MovePlan`) and no core test reads the files. After this CR a `:composition` JVM test (where every application is
installed, ADR 0156 decision 2) answers every kind's facts from the files and is red on drift on either side.

## Plan (one phase)

1. `composition/src/jvmTest/.../KindsContractTest.kt`: every registered kind in its owner's file and back; placement
   (`AppKinds.facts`), cover (`AppKinds.header`, `coverSettable`), share roles (the header's share, `AppKinds.guestDoor`
   per role, the share sheet's form through `LinkRow.mint`), creation (a member's New, the folder sheet's drop box under
   `ShareLinks.Rules.of` with and without `createNeeds`), the guest's creates in a folder (`GuestLevel.createsFor`) and
   its container (core's level door, `MovePlan.hereBlock` in a guest's mode).
2. The files as an input of every module's `Test` task (`src/mobile/build.gradle.kts`), so a changed file reruns it.
3. Bite it from both sides; fix any drift it finds.
4. Harvest: ADR 0156, `docs/apps/README.md`, the schema's description; #1061 closed; this CR deleted.

Done when: `./gradlew :core:jvmTest :composition:jvmTest :android:assembleDebug` green, the bites red as named.

## Decision log

1. **2026-11-22 — A test, not generation.** Options: generate a Kotlin table from the files; a test over the code as it
   is. Chosen the test: the facts are not one table in the core but answers spread over the registry, the share sheet,
   the guest level and the move plan — a generated table would be read by none of them unless each were rewritten
   onto it, and the backend and the web chose the same shape (a test per client). Evidence: remembered (the files'
   schema description names both tests). Cost: a fact the core does not ask anywhere has nothing to hold.
2. **2026-11-22 — Where.** `:composition`'s `jvmTest`: the one module where every application is installed
   (`AppComposition.install()`), as `PlansAreTheWebsTest` reads the web's source; the files read by
   `veyq.repoRoot`. Not `commonTest`: `readRepoFile` throws on JavaScript, and the facts are platform-free.
3. **2026-11-22 — `guest.writeOnlyCreates` is not answered.** The schema names it the backend's alone (the drop page is
   the application's own), and the web does not answer it either; the core's drop page creates `"FILE"` by a literal in
   `DepotGuest`. Left as it is.
4. **2026-11-22 — The Test task's inputs.** The first bite (the files mutated) ran green: Gradle took the test as up to
   date, since no `Test` task declared the files. Added `contracts/platform/kinds` to every module's `Test` inputs
   beside the import cases.
5. **2026-11-22 — No new guard or CI job.** The core's JVM tests run in no CI job today (`grep -n jvmTest
   .github/workflows/*.yml`: none); wiring the whole suite into CI is a decision of its own, filed as #1085, not this
   CR's. No guard either: the test is the check (docs/ai/README.md rule 3).
