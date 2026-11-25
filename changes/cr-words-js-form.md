---
id: cr-words-js-form
title: The words' enum off every first load — Msg a class of one getter per word
status: in-progress
satisfies: []
adrs: ["0136", "0155"]
invariants:
  - every native client behaves as before: the same words, the same wire, the same Swift and Kotlin names
  - the web's notices' words stay registered from words.json (ADR 0136 § The notices' move)
---

## Goal

Inbox #1341 (owner go 2026-11-25). `generate_words.py --kotlin` wrote `enum class Msg` with 1,574 entries; a Kotlin/JS
enum builds every entry the first time one is read, so the platform module every web entry imports carried all of
them (~27 KB gz of every first load since the gallery model's move, ADR 0155). Find the lightest form for JavaScript
that keeps every other client unchanged; measure before and after.

## Decision log

- **D1 — The form: a class of one getter per word, on every target** (no expect/actual). *Options:* (a) keep the enum,
  slim each entry (the key derived from the name, `args`/`plural` from one string by ordinal); (b) an expect/actual
  split, JS light, the apps an enum; (c) a class whose companion has a getter per word. *Evidence:* (a) measured on the
  bundled platform module by stripping the key and the shape from each entry: 144,937 → 134,877 B gz (−10 KB) — the
  entries' names and constructions stay. (b) is not expressible: an `expect class` cannot be actualised by an enum
  (the class kinds must match) and the common code's `Msg.X` needs one declaration every compilation sees. (c) the
  Kotlin/JS link drops every getter nothing reads: 25 words remain in the platform bundle. *Cost:* a word read is an
  allocation (three fields) and is equal by its key, not by identity — no site compares by identity
  (`git grep -nE "=== *Msg\.|Msg\.[A-Z_]+ *==="` finds none).
- **D2 — What the enum gave the apps stays, generated.** `key`, `args`, `plural`, `name` (the key in capitals),
  `toString()` (the name), `equals`/`hashCode` (the key), `Msg.entries` (the catalog's order, built once on first use:
  a top-level `lazy` the JS link drops with its only reader). `CoreText.of` and Android's `Context.words` switch over
  `msg.key` (a string `when`), with an `else` that cannot be reached — every `Msg` is generated from the same catalog.
- **D3 — The wire keeps the enum** (`MsgName`): two bridge answers (`ReceivedProblem`, `BookmarkChecked`) carried a
  `Msg` by the plugin's enum serializer, and `Bindings` makes a Rust and a C# enum from that descriptor. `MsgName` builds
  the same descriptor (kind ENUM, serial name `me.raddatz.veyq.mobile.words.Msg`, one OBJECT element per word in the
  catalog's order — `buildSerialDescriptor`, opted into `InternalSerializationApi`, the only public-enough way to make an
  ENUM descriptor without a second enum). *Evidence:* `:bridge:generateBindings` before and after, `bridge.rs` and
  `Bridge.g.cs` byte-identical. `MsgKey` reads through `MsgName`'s key map instead of a scan.
- **D4 — Swift keeps `Msg.trashMoved` through a generated extension** (`--apple-swift`, `Msg+Words.swift` in both
  Apple targets, project.yml). Kotlin/Native exports a companion as `Msg.companion.TRASH_MOVED`; 1,082 Swift sites say
  `say(.x)` / `Msg.x`. The generator names each as the Objective-C export named the enum's entry: camel case, `the`
  before an Objective-C method family (`copy_done` → `theCopyDone`). *Evidence:* the generator's 1,574 names equal the
  1,574 class properties of `VeyqCoreMsg` in the enum build's `VeyqCore.h`, in order.
- **D5 — Measured** (each against `d2a34455b`, its own build; `pnpm run build` + `slice.test.ts`; the web's `vite build`
  and the gallery agent's `paths.py`, gzip -9 per chunk): see the ADR section the harvest writes (ADR 0136 § The words'
  form for JavaScript).
