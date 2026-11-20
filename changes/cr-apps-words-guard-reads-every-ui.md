---
id: cr-apps-words-guard-reads-every-ui
title: The words guard reads every place an app shows text — Android's Kotlin, accessibility hints and values, dialog buttons, help texts, the manifests generated in both languages
status: planned
satisfies: []
adrs: ["0154", "0136"]
invariants:
  - "no word in an app but from src/mobile/words/words.json (ADR 0136 decision 3)"
  - "a guard is bitten before it is trusted: each new rule seen red on a planted literal"
---

## Goal

ADR 0154 decision 6, the words half (the audit's group D). The words guard reads Android's resources but not one line of
its Kotlin, and on the other platforms misses a handful of properties that show text; the two manifests that show text
to a person (Apple's usage descriptions, the Flatpak's metainfo) are English only and outside the catalog. After this CR
the guard reads every source a platform shows text from, and both manifests are generated from the catalog in English
and German. Independent of the other CRs of ADR 0154; cheap, so first or second.

## Context & constraints

- **Read first:** `scripts/validate_mobile_words.py` (its docstring is the rule list and its limits), `src/mobile/words/
  generate_words.py`, ADR 0154 decision 6.
- **Out of scope:** a literal reaching a shown property through a variable (the guard reads literals where they are
  shown; that limit stays, named); structure and formats (the later CRs).

## Measured at refine (2026-11-20)

Branch `claude/core-owns-refine` at `a75188eb8`. `M=src/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | The guard's scan: Android only `res/values*/*.xml`; its docstring names Compose `Text("…")` unguarded. | `grep -n "android\|values" scripts/validate_mobile_words.py` |
| R2 | Android Kotlin literals shown today: this probe finds 10, most of them templates that join words in code (`"${piece(r.label)} (${r.mediaCount})"`, `"$label: $shown"`) — word order the core will hand (cr-apps-notices-and-formats-from-the-core); the guard's own run is the true count. | `grep -rnE '(Text|contentDescription|label|title|placeholder)\s*[=(]\s*"[^"]*[A-Za-z]{2}' $M/android/src/main --include='*.kt'` |
| R3 | Swift properties the list lacks. | `grep -rnE 'accessibility(Hint|Value)\(|Text\(verbatim:' $M/apple/Veyq` |
| R4 | C# and GTK calls the lists lack. | `grep -rnE 'SetHelpText\(|(Primary|Secondary|Close)ButtonText' $M/windows/Veyq --include='*.cs'`; `grep -rn 'add_response' $M/linux/src` |
| R5 | The guard's docstring lists InfoBar `Message` both as checked (`WINDOWS_SHOWN`) and as unseen (its limit) — the limit line is stale. | `grep -n "Message" scripts/validate_mobile_words.py` |
| R6 | The manifests: English-only usage descriptions, no `InfoPlist` strings; metainfo without `xml:lang`, read by no script. | `grep -nE 'UsageDescription' $M/apple/project.yml`; `grep -n 'xml:lang' $M/linux/flatpak/metainfo.xml`; `grep -rln "metainfo\|project.yml" scripts $M/words` |
| R7 | The MSIX manifest shows only the name `veyq` (untranslated), so Windows needs nothing. | `grep -n "DisplayName\|Description" $M/windows/Veyq/Package.appxmanifest.in` |

## Decisions

- **W1 — Android Kotlin is read.** A new rule `android-literal`: no Kotlin file under `android/src` hands a literal
  with letters to `Text(`, `contentDescription =`, `label =`/`placeholder =`/`title =` lambdas' `Text`, `semantics {
  contentDescription / stateDescription }`, `Toast.makeText(`, `AlertDialog`'s `title`/`text`, `SnackbarHostState.
  showSnackbar(`. Read with comments blanked (`kotlin_lexer.mask`). Red when it reads no Kotlin.
- **W2 — the missing properties join their lists:** Swift `.accessibilityHint`, `.accessibilityValue`,
  `Text(verbatim:)` (a verbatim literal with letters is a word of the app's own); C# `AutomationProperties.SetHelpText(`,
  `PrimaryButtonText`/`SecondaryButtonText`/`CloseButtonText`; GTK `add_response(`'s label (its second argument) and
  `AdwEntryRow`'s `.text(`. The stale limit lines in the docstring go.
- **W3 — the manifests are generated.** `generate_words.py` gains `--apple-infoplist` (an `InfoPlist.xcstrings` with the
  usage descriptions, keys `usage_camera`, `usage_photos`, … in the catalog) and `--metainfo` (the Flatpak's
  `metainfo.xml` summary and description with `xml:lang="de"` entries); the hand-written texts leave `project.yml` and
  the checked-in metainfo, which becomes a template. A rule `manifest-literal` refuses a usage description in
  `project.yml` and a `<summary>`/`<p>` in the template.

## Phases

- [x] **1 the guard** — W1, W2, and W3's rule; the existing literals the new rules find moved into the catalog (EN + DE,
  the German drafted, the owner's to change). · Done when: `/usr/bin/python3 scripts/validate_mobile_words.py` green;
  `validate_guard_gaps.py` run once (a validator changed). **Bites:** one planted literal per new rule (Kotlin `Text`,
  `accessibilityHint`, `SetHelpText`, `PrimaryButtonText`, `add_response`, a usage description) each seen red alone.
  **Built** 2026-11-20 (`claude/words-guard`): `validate_mobile_words.py` — `android-literal` (W1; template `$x`/`${…}`
  stripped before the letters test), `manifest-literal` (W3's rule: a `NS…UsageDescription` in `project.yml` or a checked-in
  `Info.plist`, a `<summary>`/`<p>` in the metainfo template), W2's properties on their lists (Swift `.accessibilityHint`,
  `.accessibilityValue`, `Text(verbatim:` with `\(…)` stripped; C# `SetHelpText`, XAML `AutomationProperties.HelpText`, the
  three `…ButtonText`; GTK `.add_response(`'s label, each `add_responses` pair's, `.text(`), the docstring's stale limits
  gone. **What the new rules found:** no source literal on any platform (measured: the guard green on the unchanged tree;
  Android's 11 literals at a shown call are all templates or symbols) — only the manifests' four hand-written texts, moved
  into words.json (`usage_camera`, `usage_photos`, `metainfo_summary`, `metainfo_about`, `metainfo_preview`, German drafted)
  and staged by a `MANIFEST_PENDING` list (red on a stale entry) that phase 2 empties. **Bites** (planted one at a time,
  `/bin/cp -f` restore, each red with its own rule's label alone): Kotlin `Text(`, `contentDescription`,
  `stateDescription` if/else, `Toast.makeText`, `showSnackbar`; Swift `accessibilityHint`, `accessibilityValue`,
  `Text(verbatim:`; C# `SetHelpText`, `PrimaryButtonText`, `CloseButtonText`, XAML `PrimaryButtonText`; Rust
  `add_response`, an `add_responses` pair, `EntryRow.text`; a usage description in `project.yml` and in `Info.plist`; a
  stale pending entry; no Kotlin read. Green controls: a Kotlin template, a commented literal, a Swift verbatim
  interpolation, an `add_responses` of words. `validate_guard_gaps.py` run once: green, 104 pinned cases hold.
- [ ] **2 the generator** — W3: the two outputs, wired into the Apple build (`project.yml`) and the Flatpak manifest;
  the generator's own test. · Done when: both Apple builds, `python3 linux/run.py build` and the Flatpak's metainfo
  validation (`appstreamcli validate` in the run image) green.
- [ ] **3 Android / Apple / Linux / Windows** — the moved words drawn through each platform's `words`/`say`/`Words`
  (one commit per platform). · Done when: each platform's build green, the guard green.
- [ ] **4 harvest** — the guard's docstring is its rule list (no copy elsewhere); `src/mobile/CLAUDE.md`'s words section
  points at it; ADR 0154's `Built:` (decision 6, the words). · Done when: every guard but guard_gaps green.

## Passes

A German device or session on each app opens the camera and photo pickers (Apple: the usage description in German) and
one screen per moved word group; the Flatpak's metainfo shown by `appstreamcli` in German.

## Owner questions

None — the German of the moved words is drafted in phase 1 and the owner's to change, as every catalog word is.

## Definition of done

Phases 1–4 checked; every bite seen red and green; every guard but guard_gaps green (guard_gaps run once in phase 1).

## Retrospective

_(filled at the end of the implementation)_
