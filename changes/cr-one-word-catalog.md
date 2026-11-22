---
id: cr-one-word-catalog
title: The web's messages and the apps' words.json say one meaning one way — paired, guarded, and the notices' words generated for the web
status: in-progress
satisfies: []
adrs: ["0136", "0086", "0154", "0155"]
invariants:
  - "a meaning both catalogs say is paired in src/mobile/words/web.json and worded alike, or named apart / open with why (scripts/validate_web_words.py)"
  - "the web reads the notices' words from words.json, never a copy of its own (src/web/src/i18n/words.generated.json, generated)"
---

## Goal

Inbox #1060: the web's messages (`src/web/src/i18n/index.ts`, each application's `features/<owner>/i18n.ts`) and the
apps' one catalog (`src/mobile/words/words.json`, ADR 0136 decision 3) are two catalogs nothing compares. Owner mandate
2026-11-21: align them autonomously; where they word one meaning apart, the web wins (ADR 0154), unless the app's text
is the better fit for a platform's convention; every reworded string is logged; a true copy question goes to the owner.
It also unblocks the notices' move to the web (ADR 0155 decision 7.2: "notices after one word catalog").

## Measurement (before any change)

All `measured` by the sweep's scripts (kept out of the tree): a node `eval` of each web catalog's `const en` /
`const de: typeof en` blocks, then a Python comparison of the flattened catalogs.
- Web: 4 catalog files, **2,529** keys (index.ts 1,843, memories 333, expenses 219, depot 134); vue-i18n 11, named
  placeholders `{name}`, plurals `a | b` (31, 8 with a zero form).
- Apps: **1,503** keys, flat snake case, `{n}` / `{n:int}`, CLDR plural objects (26), `apple_en` on 96 keys.
- Overlap by meaning (English equal after placeholders are normalised; near-equal by case/punctuation/quotes):
  **1,134** app keys and **1,242** web keys; 1,522 pairs identical in both languages. Only 427 web keys derive an app
  key's name, so a name-based pairing would miss two thirds.
- Conflicts: 102 non-identical pairs, **60** false (a sibling key or a homograph: "Delete" the key name `Entf`, "Open"
  the state), **42 genuine** — 15 English case only ("pot" vs "Pot"), 3 quote style (`'` vs `’`), 13 German wording,
  1 German spacing, 6 trailing punctuation, 2 column heads' case, 2 mixed.
- Web-only 1,287 keys, app-only 369.
- Notices: the core's notices tables (`*Notices.kt`) name **110** keys; 76 of them have a web twin, 93 web keys match,
  2 conflicts (both "Pot" case); 34 have no web text at all.

## Decision log

### D1 — The mechanism: a guard over an explicit pairing now, generation for the notices, the full move staged
- **Question.** One source (the core's catalog generates the web's messages), a guard comparing shared keys, or both?
- **Options.** (a) generate every web message from words.json; (b) a guard over the pairs; (c) both, staged.
- **Choice: (c).** This CR builds (b) for every shared meaning and (a) for one area, the notices' words; the rest of
  (a) is a staged plan (below).
- **Why.** (a) whole is a rewrite of the web's catalog: the 1,287 web-only keys would join words.json (it would grow
  from 1,503 to ~2,800 keys, the apps generating resources for words they never show), and the web's 5,092 `t('…')`
  calls in 202 files would either be renamed to snake keys or go through a 2,529-row key table. Two agents edit both
  catalogs in parallel today; that churn would not merge. (b) makes drift unmergeable at the cost of one data file and
  one guard; (a) for the notices is what ADR 0155 decision 7.2 waits on, and its size is 110 keys.
- **Evidence.** measured: `grep -rhoE "\bt\(['\"\`]" src/web/src --include='*.vue' --include='*.ts' | wc -l` → 5092;
  `grep -rlE … | grep -v '\.test\.ts$' | wc -l` → 202; key counts above.
- **Cost.** Until the staged move, a shared meaning has two texts held equal, not one text: a rewording must touch
  both, and the guard says which.

### D2 — The pairing lives beside words.json, not in it
- **Question.** Where does "app key X is web key Y" live?
- **Options.** A `web` field on each words.json entry; a separate `src/mobile/words/web.json`.
- **Choice.** `web.json`, one line per entry, sorted.
- **Why.** ~820 entries would gain a line in words.json while two parallel agents add words there; a line next to an
  edited `de` conflicts. A separate file merges by line. The pairing is many-to-many (one app "Choose an image" for two
  web keys), which a list per app key carries.
- **Evidence.** remembered: the brief's parallel-agents note. measured: 37 app keys pair with more than one web key
  (`web.json`'s twins whose list has two or more).
- **Cost.** A reader of words.json does not see the web twin in place; the guard's message names the file.

### D3 — What is paired: an explicit table, seeded by text, kept complete by the guard
- **Question.** Pair by name, by automatic text match, or by an explicit table?
- **Choice.** Explicit table (`twins`), seeded once from the identical pairs of three words or more, plus the pairs
  whose names agree and the resolved conflicts; the guard's `unpaired` rule then demands every future loose match of
  three words or more be paired (twins, apart or open).
- **Why.** Names agree for 427 of 2,529 keys only. Automatic matching pairs homographs ("Open", "Delete": ~60 false
  pairs measured); short texts are left to the table.
- **Evidence.** measured: `python3 scripts/validate_web_words.py` green with 819 app keys / 860 pairs twinned,
  10 apart, 2 open.
- **Cost.** A shared meaning worded apart in English entirely, or under three words, is not offered for pairing.

### D4 — The web's catalogs are read by a TypeScript object-literal reader in Python
- **Question.** How does a Python guard read `const en = { … }`?
- **Options.** Run node; regex; a small tokenizer.
- **Choice.** A tokenizer that accepts strings and nested objects only and refuses anything else (exit 2).
- **Why.** The trust chain runs `/usr/bin/python3` with no node; a regex mis-reads nesting silently.
- **Evidence.** measured: the reader's 2,529 keys equal the node `eval`'s flattening key for key and value for value
  (0 differences).
- **Cost.** A catalog that gains a spread or a computed value turns the guard to exit 2 until the reader learns it.

### D5 — The notices' words: generated JSON, the web's `words` namespace, list placeholders
- **Question.** How does the web read the core's notices' words from words.json?
- **Choice.** `generate_words.py --web` writes `src/web/src/i18n/words.generated.json` (committed): the keys
  `web.json`'s `read` names, `{n}` as vue-i18n's list index `{n-1}`, plurals as ` | ` forms, syntax characters as
  literal interpolations. `read` is held equal to the `Msg`s the `*Notices.kt` tables name; the file to the generator's
  output; `words.test.ts` renders every word through vue-i18n and compares it with the catalog's text.
- **Why.** A `Notice` reaches a client as a key and positional arguments; list interpolation takes them as they come.
  JSON needs no lint or format pass. The web does not register the namespace yet: the notices' move (ADR 0155 decision
  7.2) switches the toasts' call sites to it — registering it now would ship ~110 unread words in the eager set.
- **Evidence.** measured: `npx vitest run src/i18n/words.test.ts` → 3 passed (every word in en and de).
- **Cost.** A notice word changed in words.json needs a regeneration the guard asks for.

### D6 — Conflicts: the web's wording wins, apart where a platform's convention or a different meaning says so
- **Question.** Which wording stands where the two differed?
- **Choice.** The web's (ADR 0154), applied to words.json — 49 field changes over 44 keys (one of them the Apple form `cover_choose_image` keeps), listed below. Where the same
  web wording was a term (Pot, Bereich), the app's other uses of the term followed too, so the app does not say both.
  Apart: the ellipsis on a command that opens a picker (Apple's, Microsoft's and GNOME's menu convention), and pairs
  of different meaning. Open: two pairs where the web's wording would be wrong or an owner's earlier choice stands.
- **Why.** The mandate's order; ADR 0086 writes **Pot** with a capital, as the web does in all 23 of its uses.
- **Evidence.** measured: before, the app wrote `\bpot\b` in 18 English texts, the web 0; the app's German wrote
  "Space(s)" in 6 texts, the web 0 (it writes "Bereich" in 135).
- **Cost.** The apps' wording changes on every platform (generated, no hand edits).

### The reworded strings (words.json; the web wins)

| key | field | before | after |
|---|---|---|---|
| `pot_loading` | en | 'Loading the pot…' | 'Loading the Pot…' |
| `pot_ledger_unknown_entry` | en | 'An entry this pot no longer shows' | 'An entry this Pot no longer shows' |
| `entry_stale` | en | 'Someone else changed the pot meanwhile. It was read again; save once more.' | 'Someone else changed the Pot meanwhile. It was read again; save once more.' |
| `people_remove_confirm` | en | 'No entry names them, so they disappear from the pot. This can’t be undone.' | 'No entry names them, so they disappear from the Pot. This can’t be undone.' |
| `pot_busy` | en | 'The pot’s key is being renewed. Try again in a moment.' | 'The Pot’s key is being renewed. Try again in a moment.' |
| `pot_blocked` | en | 'The operator blocked this pot.' | 'The operator blocked this Pot.' |
| `settings_missing` | en | 'This pot has no base currency yet. Pick one to record entries.' | 'This Pot has no base currency yet. Pick one to record entries.' |
| `settings_missing_read_only` | en | 'This pot has no base currency yet, so it shows no entries.' | 'This Pot has no base currency yet, so it shows no entries.' |
| `settings_currency_fixed` | en | 'Fixed: the pot holds entries.' | 'Fixed: the Pot holds entries.' |
| `settings_categories_hint` | en | 'Added here, they can be picked for every entry of this pot. They can be renamed, not removed.' | 'Added here, they can be picked for every entry of this Pot. They can be renamed, not removed.' |
| `create_pot` | en | 'New pot' | 'New Pot' |
| `create_pot_name_taken` | en | 'There is already a pot with this name here.' | 'There is already a Pot with this name here.' |
| `create_pot_people_failed` | en | 'The pot was created, its people were not. Add them in its settings.' | 'The Pot was created, its people were not. Add them in its settings.' |
| `create_pot_failed` | en | 'The pot was not created. Please try again.' | 'The Pot was not created. Please try again.' |
| `ed_blocked` | en | 'Blocked by the operator: this pot or entry takes no changes. Nothing was saved.' | 'Blocked by the operator: this Pot or entry takes no changes. Nothing was saved.' |
| `problem_unknown_participant` | en | 'The entry names someone who is not in this pot.' | 'The entry names someone who is not in this Pot.' |
| `history_unreadable` | en | 'A version this pot cannot read' | 'A version this Pot cannot read' |
| `pot_open_failed` | en | 'This pot did not open' | 'This Pot did not open' |
| `memory_date_invalid_range` | en | "The end can't be before the start." | 'The end can’t be before the start.' |
| `memory_place_name_from_map` | en | "Use the map's name" | 'Use the map’s name' |
| `settings_maps_receive` | en | "The map service receives the part of the map you view, the place-name service the point of a place, and both this device's IP address." | 'The map service receives the part of the map you view, the place-name service the point of a place, and both this device’s IP address.' |
| `cover_choose_image` | en | 'Choose an Image' | 'Choose an image' |
| `cover_choose_image` | apple_en | None | 'Choose an Image' |
| `pot_expense_by` | en | 'Expense by {1}' | 'expense by {1}' |
| `pot_refund_to` | en | 'Refund to {1}' | 'refund to {1}' |
| `ed_paid_column` | en | 'paid' | 'Paid' |
| `ed_paid_column` | de | 'bezahlt' | 'Bezahlt' |
| `ed_received_column` | en | 'received' | 'Received' |
| `ed_received_column` | de | 'erhalten' | 'Erhalten' |
| `list_empty` | en | 'Nothing here yet' | 'Nothing here yet.' |
| `list_empty` | de | 'Hier ist noch nichts' | 'Hier ist noch nichts.' |
| `memory_upload_switched_off` | en | 'Memories is switched off here, so nothing can be added.' | 'Memories is switched off here, so nothing can be added' |
| `memory_upload_switched_off` | de | 'Erinnerungen ist hier abgeschaltet, deshalb kann nichts dazukommen.' | 'Erinnerungen ist hier abgeschaltet, deshalb kann nichts dazukommen' |
| `share_link_share` | de | 'Teilen …' | 'Teilen…' |
| `cover_change` | de | 'Titelbild ändern' | 'Cover ändern' |
| `cover_remove` | de | 'Titelbild entfernen' | 'Cover entfernen' |
| `cover_set` | de | 'Titelbild festlegen' | 'Cover festlegen' |
| `drawer_spaces` | de | 'Spaces' | 'Bereiche' |
| `space_switch` | de | 'Space wechseln' | 'Bereich wechseln' |
| `switcher_open` | de | 'Space, Tenant oder Server wechseln' | 'Bereich, Tenant oder Server wechseln' |
| `manage_hint` | de | 'Ausgeblendete Tenants und Spaces bleiben auf dem Server und werden nur auf diesem Gerät weggelassen.' | 'Ausgeblendete Tenants und Bereiche bleiben auf dem Server und werden nur auf diesem Gerät weggelassen.' |
| `unlock_remembered_hint` | de | 'Bestätige, dass du es bist, um deine Spaces zu öffnen.' | 'Bestätige, dass du es bist, um deine Bereiche zu öffnen.' |
| `device_unlock_prompt` | de | 'Deine Spaces öffnen' | 'Deine Bereiche öffnen' |
| `offer_not_now` | de | 'Nicht jetzt' | 'Jetzt nicht' |
| `pot_unreadable` | de | 'nicht lesbar' | 'unlesbar' |
| `search_words_password` | de | 'Passwort, Passphrase, Anmeldung' | 'Kennwort, Passwort, Anmeldung' |
| `search_words_profile` | de | 'Benutzername, echter Name, Profilbild, Avatar' | 'Benutzername, Name, Profilbild, Avatar' |
| `signin_working` | de | 'Anmeldung läuft…' | 'Wird angemeldet…' |
| `bookmark_delete_confirm` | de | '„{1}“ wird in den Papierkorb verschoben. Von dort kannst du es wiederherstellen, bis es endgültig gelöscht wird.' | '„{1}“ kommt in den Papierkorb. Dort lässt es sich wiederherstellen, bis es endgültig entfernt wird.' |

## Owner's copy questions

1. `kit_save_title` ↔ `tenant.join.saveKitTitle`: "Speichere dein Notfall-Kit" (the apps' step, its note cites a CR
   owner question) against the web's "Notfall-Kit sichern". One wording — which?
2. `link_sign_in_scan_hint` ↔ `pairing.signInScanHint`: the web quotes the screen's title lower case (“add a device”),
   the app as the title reads (“Add a device”). The web-wins rule would quote a title wrongly; fix the web instead?
3. German "Cover" vs "Titelbild": both catalogs use both (web `node.cover.*` "Cover", its copy-loss texts "Titelbild";
   the apps the same after this CR). One word?
4. German "Titel" vs "Track" for a piece of music: both catalogs say "Titel" in `memory.music.*` / `music_*` and
   "Track" in `memory.tracks.*` / `track_*`. One word?
5. "Tenant" stays English in German on both (`switcher_open`, the web's moderation texts). Intended?

## Staged rest

1. **The notices' move** (ADR 0155 decision 7.2): the web registers `words.generated.json` as its `words` namespace and
   its toasts render the core's `Notice(msg, args)` through it; the web's own twin keys of those words are deleted.
2. **Application by application**, as ADR 0155 decision 7.3 moves each onto the core: its shared words join `read`
   and its web twins are deleted, until the web's own catalog holds only web-only words.
3. **The web-only words** (1,287) stay the web's; whether the core's catalog should hold them too is decided when an
   app needs one.

## Done when
- `python3 scripts/validate_web_words.py` green; its bites red; `validate_guard_gaps.py` pins them.
- The web check, `:core:jvmTest`, `:composition:jvmTest`, `:bridge:jvmTest`, `:android:assembleDebug`, the Apple
  XCFramework and macOS build, Linux `run.py build`, Windows `run.py test`, every validator green.
- Harvested into ADR 0136; this CR deleted; inbox #1060 updated.
