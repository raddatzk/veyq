---
id: cr-android-core-ui-through-the-registry
title: Android's core UI reaches the core applications only through its registry — each application holds its own models in a `Holding` it registers, the UI direction guard reads core application packages
status: in-progress
satisfies: []
adrs: ["0154", "0156", "0079"]
invariants:
  - "the core UI reaches an application only through the platform's one registry (ADR 0154 decision 3): no file of Android's core UI names `me.raddatz.veyq.mobile.<application>` or `…mobile.api.<application>` but the composition root"
  - "an application never names another (its UI package nor its core module's package)"
  - "behaviour-preserving: each moment of the tenant's life closes what it closed before, every flow answers as before"
  - "operator-blind: nothing about what is sealed, sent or stored changes; no key or plaintext leaves where it was"
---

## Goal

Inbox #1056, decided by [ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md)'s
decision log entry 27 against [ADR 0154](../docs/decisions/0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)
decision 3: Android's core UI (`src/mobile/android/…/android/core/`) names core application packages in 11 files —
`AppModel` holds each application's screen models, the guest screens their pages, the tray Depot's uploads, the formats
a Memory's date. After this CR each application holds its own models in a `Holding` it registers with its
`ApplicationUi`, the core UI asks every holding the moments and flows it used to run itself, and
`scripts/validate_mobile_ui_direction.py` holds it. Beside it, inbox #1064: an application's list (`ArrangedListScreen`)
opens core's row menu as the level does.

## Context & constraints

- The registry is the web's `registry.ts` on Android (`core/Registry.kt`); the composition root `Applications.kt` is the
  one file that names the applications' UIs. The Kotlin core's `:composition` may name applications; the core UI may
  call it, but may not hold or name what it returns of an application.
- Another agent changes `:core` navigation now: `src/mobile/core/` and `src/mobile/apps/` stay untouched unless the
  registry has no answer (it had one for everything; decision 6).
- Apple's core UI names the same Kotlin types through Swift; Linux and Windows reach applications through the bridge.
  Neither is this CR's (decision 8).

## Decisions (each one's why and cost in § Decision log)

1. One `Holding` per application (`core/Holding.kt`), registered as `ApplicationUi.holding` (a typed `HoldingKey`),
   built once by `AppModel` in registration order, reached by the application as `model.held(key)` through an extension
   property it declares (`model.memories`, `model.depot`, `model.expenses`).
2. The tenant's moments are hooks: `leave`, `shown`, `locked`, `signedOut`, `personOpened`, `listsRead`.
3. The core flows an application answers are hooks too: `rowLines`, `trashed`, `moveSheet`, `addHere`, `drop`,
   `takesDrop`, `levelUploads` (the tray and New's uploads), `deriver`; the guest bar's per-kind actions and the
   naming's follow-up are `KindPage.guestActions` and `KindPage.guestNamed`; *Add here* on a page is an `AddHerePage`
   the page sets; "Send a copy" is a `SendCopy` the file's header hands the shell.
4. The files only Memories uses move into `apps/memories/` (`AudioEnds`, `MediaDerive`, the date text, the world view).
5. The guard gains rule (c) (no core UI file names a core application's package or generated API) and widens (b) to core
   application packages; two `validate_guard_gaps.py` cases pin them.

## Phases

1. **#1064** — `ArrangedListScreen(list, model, …)`, each row and tile in `WithRowMenu` over `ObjectRef.at`. Done when:
   `:android:compileDebugKotlin` and `validate_mobile_structure.py` green.
2. **#1056** — the holdings, the hooks, the moves, the guard. Done when: `:android:assembleDebug`, `:composition:jvmTest`
   and every validator green; the guard red on the tree before (11 files) and on each planted import; `validate_guard_gaps.py` green.
3. **Harvest** — the decision log into ADR 0156's, the as-built into ADR 0154's Consequences, the inbox rows; this file deleted.

## Decision log

Kept by the implementer as the decisions were taken (the owner's mandate of 2026-11-21: decided autonomously, written
down so the owner can retrace each one).

1. **2026-11-22 — A CR or none.** The work moves ~1000 lines out of `AppModel` into three applications, adds a registry
   member and a guard rule: CR-sized by `docs/ai/README.md` § The pipeline (a work order with phases). Chosen: this CR,
   harvested at the end. Cost: one file written and deleted.
2. **2026-11-22 — The mechanism.** Options: (a) keep `AppModel`'s per-application members and type them through core
   interfaces (the core would still build each application's model through the composition); (b) one `AndroidViewModel`
   per application (each its own scope; the tenant's moments would need a bus between view models); (c) one `Holding` per
   application inside the one `AppModel`, registered through the registry. Chosen (c): one lifecycle and one scope as
   before (a rotation keeps the same objects), the registry stays the only edge, and the call sites stay `model.<app>.x`.
   Evidence: remembered (Apple's `AppModelHeld.swift` already holds Memories' state apart, in core). Cost: `AppModel`
   opens seven members as `internal` (`shellModel` and `tenantSession` read-only, `readRowDetails`, `refreshLists(t)`,
   `appLanguage`, `followLevelView`, `rowDetail`), and `held` casts once by its key.
