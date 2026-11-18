---
id: cr-app-search
title: The web's search in the apps — the core's port of core/search over every space, its chips, its one ordered list, people and settings, answering to vectors the web answers to as well
status: planned
satisfies: []
adrs: ["0125", "0122", "0136", "0140", "0079", "0105", "0144", "0123"]
invariants:
  - "operator-blind E2EE: the apps' search sends the server nothing it does not send the web's (ADR 0125 decision 1): no query, no index, no token; the only reads are the trees the shell already reads and one listScopeMedia per space per focus, by its tag"
  - "every rule of ADR 0125 decisions 4, 5 and 9 is the core's, ported from src/web/src/core/search once, and both answer to contracts/platform/search/v1-vectors.json; a platform draws the core's SearchShown and decides nothing (ADR 0136)"
  - "the index holds no key; every key the search unwraps itself (a medium's K_blob, a drop-box uploader's key) is zeroed in a finally before its walk returns; the node keys it reads are the shell's tree's and are never copied"
  - "core's search/ package names no application (a guard): an application's part reaches it through AppKinds, as on the web through the registry (ADR 0079)"
  - "the bridge carries names, places, words and ids only — never a key, a seal or a token (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json, the web's copy where the web has it; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "the backend is unchanged; the web gains one test file and no behaviour"
---

## Goal

ADR 0125 built one search on the web: a field over every space of the tenant, chips that are predicates over every
document, one list ordered by the match and then by where a result lies, every photo and video of every space through
`listScopeMedia`, the tenant's members and the settings sections as results, no guest search. The apps have a name-only
search: `ShellWords.search` filters the shell's objects by `contains` (R1). Block E of the V2 order (inbox #21) brings
the web's search to the apps **as the web's**, not as a second design: one port into the shared Kotlin core, held to the
web by vectors both run, drawn by each platform in its own search control.

## Context & constraints

- **Read first:** ADR 0125 whole; `src/web/src/core/search/` (`model.ts`, `query.ts`, `results.ts`, `documents.ts`,
  `searchIndex.ts`, `peopleAndSettings.ts`, `openRequest.ts`, `contribution.ts`, `SearchField.vue`) and
  `src/web/src/features/memories/search.ts`; `src/web/src/shared/text/fold.ts`; ADR 0136 (logic in the core), ADR 0122
  (the shell: Search is the bar's last place), ADR 0140 decision 5 (the kept reads); `src/mobile/CLAUDE.md` whole;
  `docs/crosscutting/native-apps.md` § Spaces and navigation; [uc.platform.search](../docs/features/platform/use-cases/uc.platform.search.md).
- **The web is the reference implementation.** Where this CR and the web differ it says so and why (D3's comparator, D6's
  tree lifetime, D9's settings sections, D10's hidden spaces, D12's desktop keyboard).
- **Where what runs:** from `src/mobile/`: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest
  :core:macosArm64Test :android:assembleDebug`; the Apple builds `xcodebuild -project apple/Veyq.xcodeproj -target Veyq -sdk
  macosx build CODE_SIGNING_ALLOWED=NO` (and `-sdk iphonesimulator`) after `./gradlew :core:assembleVeyqCoreDebugXCFramework`;
  `/usr/bin/python3 linux/run.py test|bridge|build|run`, `/usr/bin/python3 windows/run.py test|bridge|build|run` (over
  `VEYQ_WINDOWS_SSH`); live tests with `VEYQ_LIVE=http://veyq.localhost:8080` (in a worktree plus `VEYQ_ENV_FILE=<main
  checkout>/.run/.env`) after `./gradlew backend:seed`, as `LiveSignInTest` does. The web: `pnpm run check` in `src/web/`.
- **Machine hints (hold for the planned shape; re-derive them):** the iOS simulators do not boot under this Mac's Xcode
  (#928) — the iPhone and iPad passes are the owner's on a device; the macOS build runs here. The Linux pass draws in the
  Fedora container through Broadway (`run.py run`); the Windows pass runs on the ARM64 VM through UI Automation.
- **Out of scope** (each its own row): the open Pot's entries and every Pot's (#838, #839); a negated chip and OR within a
  type (#832); full text over the journal (ADR 0125 8b, #855 brings the journal first); semantic search (8c); a received
  link's guest view in the apps (#435 — when it lands, its page has no field: decision 7); searching while offline over
  every space's photos from the store (#956, D11).

## Measured at refine (2026-11-18)

Branch `claude/block-e` at `6947f5048` (cut from `claude/windows-cr-f6c73c`), clean before this CR. Commands from
`src/mobile/` unless they name the repo root; `C=core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | **The apps' search today is names only, and each platform calls it.** `ShellWords.search(objects, query)` keeps the objects whose name `contains` the trimmed query, ignoring case — no folders, no facets, no order, no diacritic folding. `Shell.searching` is the shell's state; Android and Apple call `ShellWords.search` from the UI, Linux and Windows through `Call.SearchQuery` → `Answer.Hits`. | `sed -n 24,28p $C/navigation/ShellWords.kt`; `grep -rn "ShellWords.search\|SearchQuery\|Answer.Hits" --include='*.kt' --include='*.swift' --include='*.cs' --include='*.rs' . \| grep -v /build/` |
| R2 | **Where each platform's field already sits:** Android a full-screen M3 `SearchBar` (`ShellScreen.kt` `SearchScreen`, Search the bar's last place, Back leaves it); Apple `.searchable(placement: .toolbar)` on the split view and a `Tab(role: .search)` on the iPhone (`ShellView.swift` `SearchList`, `SearchHits`); Linux a `gtk::SearchBar` + `SearchEntry` in the sidebar's header, hits in the sidebar, `<Control>f`; Windows the `NavigationView`'s `AutoSuggestBox`, hits as its suggestions, Ctrl+E and Ctrl+F. No other field collides: the other search fields are the participants picker's (three platforms) and Windows' currency box. | `grep -rn "searchable\|SearchBar\|SearchEntry\|AutoSuggestBox" android/src/main apple/Veyq linux/src windows/Veyq --include='*.kt' --include='*.swift' --include='*.cs' --include='*.rs'`; `grep -n "Control>f" linux/src/shell.rs`; `grep -n "VirtualKey.E, VirtualKey.F" windows/Veyq/ShellView.cs` |
| R3 | **The web's search, the port's source:** core `model.ts` (documents, `PERSON_DOCUMENT`/`SETTINGS_DOCUMENT`, `yearsOf`/`monthsOf` in local time), `query.ts` (chips, `suggestChips` with `MIN_TAIL` 2, `PER_TYPE` 3, `MIN_MONTH` 3; `satisfies`; `Rank` exact/prefix/wordStart/inside/place; `MIN_INSIDE` 2, `MIN_KEYWORD` 3; `createMatcher` narrowing from 3 characters, `MATCH_HISTORY` 64; `matchedRanges`/`nameParts`), `results.ts` (`Place` open/space/other/nowhere, `inOrder`, `firstRanked`), `documents.ts` (node documents by seal, `placeOf`/`placeWithin`), `searchIndex.ts` (per-space entries by ETag, `SLICE_MS` 40), `peopleAndSettings.ts`, `openRequest.ts`, `contribution.ts`; the field's `REVALIDATE_MS` 20 000 and `PAGE` 10. | `wc -l ../web/src/core/search/*.ts`; `grep -n "export const\|^const [A-Z_]* =" ../web/src/core/search/query.ts ../web/src/core/search/results.ts ../web/src/core/search/searchIndex.ts`; `grep -n "REVALIDATE_MS =\|const PAGE" ../web/src/core/search/SearchField.vue` |
| R4 | **The web's application hooks:** `KindDescriptor.search {label, facets?, documents?}` and `ApplicationRegistrar.search({scopeDocuments})`; labels: folder `node.kindFolder`, bookmark `node.bookmark.kind`, file `depot.kindFile`, drop box `depot.dropBox.kind`, Pot `expenses.kindPot`, Memory and Chapter `memory.kindMemory`/`memory.kindChapter` with Memories' facets (date range as years and months, place, participants) and its photo/video document kinds; Memories' `scopeMediaDocuments` reads `listScopeMedia` by tag, 1000 per page, opens only media whose `metaRevision:blobId` moved, skips blocked media and anything not a gallery medium (GPX, music). | `sed -n 319,395p ../web/src/core/applications/registry.ts`; `grep -rn "search: {\|search: memorySearch\|app.search" ../web/src --include='*.ts' \| grep -v test`; `grep -n "export" ../web/src/features/memories/search.ts` |
| R5 | **The core walks every space already, and holds the opened trees for the session.** `TenantSession.trees()` reads every scope's `listNodes` as a kept, conditional read (a `304` keeps the copy; offline the store's copy, `offline: asOf`), caches the opened map, and `fromTree(key, derive)` holds a value derived per tree read; `treeMoved(scopeId)` revalidates one scope. `OpenNode.key` is `internal val key: ByteArray`; `forgetTree()` drops the map without zeroing. `ParticipantIndex` and `MemoryFacts.dates` already derive from it, opening each Memory's `wrappedMeta` (`ParticipantIndex` capped at 500 Memories per scope, never the personal space). | `grep -n "suspend fun trees\|fun fromTree\|suspend fun treeMoved\|fun forgetTree" $C/session/Account.kt`; `grep -n "internal val key" $C/session/Tree.kt`; `sed -n 1506,1510p $C/session/Account.kt`; `grep -n "MAX_MEMORIES_PER_SCOPE\|fromTree" $C/memories/people/ParticipantIndex.kt` |
| R6 | **Nothing in the apps reads `listScopeMedia`**; the generated `MediaApi` carries it. `MemoryMedia` opens a node listing's rows (`openMeta` under the node key, `openBlobKey` → `K_blob`), its `Meta` has `capturedAt`, `capturedAtOverride`, `placeName`, `effectiveCapturedAt`. | `grep -rn listScopeMedia core/src bridge/src \| wc -l` → 0; `grep -n "operationId: listScopeMedia" ../../contracts/api/memories/media.openapi.yaml`; `grep -n "fun openMeta\|fun openBlobKey\|class Meta\|effectiveCapturedAt" $C/memories/MemoryMedia.kt` |
| R7 | **Folding and collation differ from the web.** Core has `nfc` and `nfkd` (`expect`; JVM `Normalizer`, Apple `NSString`, Linux/Windows utf8proc) and one fold, `Participants.fold` = NFKD, drop U+0300–U+036F only, lowercase, ß→ss; the web's `fold` is NFD, drop every `\p{M}`, lowercase, ß→ss. No `Collator` anywhere in core; the web orders by `Intl.Collator(locale, {numeric: true, sensitivity: 'base'})`. | `grep -n "expect fun" $C/crypto/Bytes.kt`; `grep -n "fun fold" $C/memories/Participants.kt`; `sed -n 10,12p ../web/src/shared/text/fold.ts`; `grep -rnI "Collator" core/src \| wc -l` → 0; `grep -n "Collator" ../web/src/core/search/SearchField.vue` |
| R8 | **Shared vectors have a precedent.** `contracts/expenses/v1-vectors.json` is read by the web's tests and by the core's `ExpensesVectorsTest` through `veyq.repoRoot` (`core/build.gradle.kts` sets `veyq.vectors`, `veyq.repoRoot`, and `VEYQ_VECTORS`/`VEYQ_REPO_ROOT` for the native tests). | `grep -rln "v1-vectors" ../web/src`; `grep -n "repoRoot\|VEYQ_REPO_ROOT" core/build.gradle.kts`; `grep -rn "v1-vectors" core/src/*Test` |
| R9 | **The application hooks in the apps live in `AppKinds`** (the composition root of kinds: words, creates, copies, pins, …); core's `navigation/ShellModel.kt`, `ShareSheet.kt`, `session/LinkForm.kt` and `words/Notices.kt` name application packages, and no guard holds a direction on the mobile side (only `validate_web_core_imports_no_applications.py`, web-only). | `grep -n "fun \|val " $C/AppKinds.kt \| head -40`; `cd $C && grep -c "mobile\.\(memories\|depot\|expenses\)" navigation/*.kt session/*.kt \| grep -v ":0"`; `grep -n "mobile\|kotlin" scripts/validate_web_core_imports_no_applications.py` (repo root) → none |
| R10 | **People and settings in the apps:** the roster is `TenantSession.people` (`Person(userId, username, displayName, …)`, real names under the tenant key, read from the kept `listScopeMembers` offline too); a profile opens by `ShellModel.openPerson(userId)` on all four platforms. Settings is one page per platform outside `Shell`, sections without routing (Android: account, this device, sessions, saved keys, add device, storage, maps, plus appearance), gated by `AccountSettings` (add device on `DEVICE_LINKING`, storage when an offline store exists, maps when offered). | `grep -n "val people\|fun readPeople" $C/session/Account.kt`; `grep -n "fun openPerson" $C/navigation/ShellModel.kt`; `grep -n 'header("' android/src/main/kotlin/me/raddatz/veyq/mobile/android/SettingsScreen.kt`; `grep -n "addDevice\|storage\|maps" $C/session/AccountSettings.kt \| head` |
| R11 | **Offline reads:** `KeptRead` allowlists `listMyScopes`, `listNodes`, `listNodeMedia`, `listFiles`, `listApplicationBlobs`, `listScopeMembers` (and key material, tenants) — not `listScopeMedia`. Offline the trees come from the store and `treeMoved` answers false. | `grep -n '("list\|("get' $C/offline/KeptRead.kt`; `grep -rn "\.kept(" $C \| grep -v offline/` |
| R12 | **Hidden spaces:** `Hidden.visibleSpaces`/`visibleObjects` leave a space the member hid on this device out of "the whole app … Search" (owner 2026-11-08). | `sed -n 3,10p $C/navigation/Hidden.kt` |
| R13 | **The web's words** for the field are `shell.search.*` (EN+DE, 22 keys: placeholder, results, filterBy, showMore, loading, notSearched, empty, hint, the chip words, `kindPerson`, `kindSetting`, the announcements) plus `memory.search.photo`/`video`; the apps' `words.json` has `tab_search`, `search_hint` ("Search names"), `search_close`. | `sed -n 1607,1640p ../web/src/i18n/index.ts`; `grep -n '"tab_search"\|"search_hint"\|"search_close"' words/words.json` |
| R14 | **ADR 0125's bounds**, measured on the web at the volume harness's 7,000 nodes / 10,000 photos / 445 members: build 1.8–2.6 s (3.3–5.0 s at 4×), its longest main-thread task ≤ 200 ms; a keystroke's longest task ≤ 100 ms at 4×; index heap 9.1–9.6 MB. The harness is still in the tree. | ADR 0125 § Consequences; `ls scripts/measure_volume.py` (repo root) |
| R15 | **The gates on this branch before any edit:** `./gradlew :core:jvmTest :bridge:jvmTest` exit 0 (§ Gates at refine). `pnpm run check` in `src/web/` and the native platforms' builds were not run at refine. | `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest; echo $?` |

## Decisions

Phase 1 writes D1–D13 as a new ADR ("The apps search with the core's port of the web's search; both answer to one set of
vectors"); each "rejected" is its Alternatives.

### D1 — One algorithm, two implementations, one set of vectors

The core **ports** `core/search` to Kotlin (`core/…/search/`), rule by rule, and **both** the web and the core run
`contracts/platform/search/v1-vectors.json` — as the crypto suite answers to `contracts/crypto/vectors` and the ledger to
`contracts/expenses/v1-vectors.json` (R8). The vectors are the one truth; neither implementation is the other's test.

- **What the vectors hold** (each case with a `rule` note naming the ADR 0125 decision or the web function it pins):
  `fold` (NFD marks, ß, İ, a ligature that NFD keeps, a supplementary-plane letter); `tails` and `wordPrefix` (a
  no-break space and U+FEFF as JavaScript's `\s` reads them, a boundary after `²`); `suggest` (documents, sources, chips,
  text → the ordered suggestions with their `text`); `match` (documents, query, context, limit → the ordered ids and the
  total); `matcher` (a typed sequence "K", "Ke", "Ken", "Ke", "K" whose answers equal fresh queries); `nameParts`;
  `facets` (a Memory's plaintext meta and a fixed `now` → years, months, places, people) and `medium` (a listed tier and
  blob meta → name, `capturedAt`, places, photo / video / not a gallery medium).
- **Written from the web, read by eye.** Inputs are chosen (the web's own test cases in `query.test.ts` and
  `results.test.ts`, plus the Unicode cases above); outputs are recorded by running the web's functions once (the web
  test's write mode, `VEYQ_WRITE_VECTORS=1`), and each recorded output is checked against its `rule` before the file is
  committed. Every `capturedAt` is 12:00 UTC on a month's 15th, so every time zone reads the same day, month and year.
- **Kept in step:** a change to a rule changes the vectors, and the web's and the core's tests go red together until
  both follow — the same bite proves it (phase 2).
- Rejected: **the core's own tests mirroring the web's by hand** — two suites drift silently, which is what R7's two folds
  already did. Rejected: **one implementation for both** (Kotlin/JS compiled into the web, or the TS run in an engine
  inside the apps) — a rewrite of a built, measured web module, or a JavaScript engine in four apps for one feature.

### D2 — What the port owns, and where

`core/…/search/` (platform-generic, as the web's `core/search/`): `Fold` (D3), `SearchDocument`/`SearchFacets`/
`ScopeDocument`/`ContributedDocument`, `Query` (`SearchChip`, `chipKey`, `suggestChips`, `satisfies`, `textRank`,
`matchAll`, `Matcher` with its narrowing and history, `matchedRanges`, `nameParts`, `reasonKeyword`), `Results` (`Place`,
`placeOf`, `inOrder`, `firstRanked`), `Documents` (node documents by seal, `placeOf`/`placeWithin`), `SearchIndex` (D6),
`PeopleAndSettings` (D9), `OpenRequest` (D8), `SearchContribution` (D8), and `SearchModel` (D7). The port keeps the web's
names and constants (R3) so a reader moves between the two.

- **No `Regex` in the port's text rules.** JavaScript's `\s`, `\d` and `\p{L}\p{N}` are spelled as predicates named
  after them (`isJsSpace`: Zs, tab, line feed, vertical tab, form feed, carriage return, U+FEFF, U+2028, U+2029;
  `isAsciiDigit`; `isLetterOrNumber` by the Unicode categories L* and Nd/Nl/No), because the JVM's `\s` is ASCII and
  Kotlin's `trim`/`isDigit` read other sets; the vectors hold the cases where they differ.
- **Off the UI thread, so no slices.** The web slices its build into 40 ms steps because it runs on the main thread; the
  core's runs on `Dispatchers.Default` and checks for cancellation per node and per medium (`ensureActive`), so a
  sign-out or a closed tenant stops it. Nothing of `SLICE_MS` is ported.

### D3 — Folding and the name order, one for every platform

- **`Fold.fold`** = the web's: NFD, drop every character of category Mn, Mc or Me, lowercase, ß → ss; and `foldMap`
  for the bold part. Core gains `expect fun nfd` beside `nfc`/`nfkd` (JVM `Normalizer.Form.NFD`, Apple
  `decomposedStringWithCanonicalMapping`, Linux/Windows utf8proc's NFD — `NormalizationTest` gains its cases).
  `Participants.fold` (R7) becomes `Fold.fold`, so the participants picker and the search read one rule, as on the web.
- **The name order is the core's own comparator**, `NameOrder`: the folded names compared with runs of ASCII digits as
  numbers ("Trip 2" before "Trip 10"), then the names' code points, then the id. It stands where the web's
  `Intl.Collator(numeric, base)` stands — the third key after the rank and the place, so it only orders equal matches in
  one place. The vectors' order cases use names on which both agree (letters, accents, digits). **Residual:** at an
  equal rank and place, names led by punctuation or a letter a language collates apart (Swedish å) may order differently
  from the web. Rejected: **each platform's collator** (`java.text.Collator` has no numeric mode, Apple's
  `localizedStandardCompare` has, the bridged platforms have none) — three orders where the ADR wants one.

### D4 — What each application contributes: `AppKinds.search`, as the web's registry

- **Per kind** (`KindSearch`): the kind chip's word (a `Msg`), optional `facets(ctx)` — handed the node, its key and its
  parent's for the call only — and the document kinds its page or its application's space search makes (word, icon).
  The words are the web's labels (R4): folder, bookmark, file, drop box, Pot, Memory, Chapter, photo, video; core adds
  *Person* and *Setting*.
- **Per application** (`ApplicationSearch.scopeDocuments(ctx)`): ctx holds the space, the last tag, the previous
  documents by id, `keys()` (the node keys, opened only when asked) and cancellation. Memories ports
  `scopeMediaDocuments` (R4): `listScopeMedia` by `If-None-Match`, 1000 per page, a medium kept unopened at an unchanged
  `metaRevision:blobId`, a blocked one and a non-gallery one (GPX, music: `GalleryMedium.shows`) no document; its rows
  opened with `MemoryMedia`'s own openers, never a second copy of them.
- **Memories' facets** port `memorySearchFacets`, `yearsOfDate`, `monthsOfDate` over the core's `MemoryDate`,
  `MemoryLocation` and `Participants.read`; dates in the device's time zone (`TimeZone.currentSystemDefault()`), as the
  web reads local time.
- **Depot and Expenses** contribute their kind words only, as on the web (a file, a drop box, a Pot by its name).
- **The direction is a guard:** `scripts/validate_mobile_search_names_no_application.py` reads every file of
  `core/…/search/` and fails on a reference to `me.raddatz.veyq.mobile.memories|depot|expenses`; the anchors derive from
  `ApplicationId`'s entries, not a list. `AppKinds` is the one place that wires an application's part in.
- Rejected: **the search walking Memories' meta itself** (as `ParticipantIndex` does) — core would name an application,
  and the 500-per-scope cap there is no rule of the search's.

### D5 — The key lifetime

- **The index holds no key** (ADR 0125 decision 2): documents are names, ids, places, years and user ids.
- **The node keys are the shell's tree's** (R5): the apps open every space's tree for the shell at the session's start
  (the drawer, Home and every application list every space, ADR 0122), so the search opens **no tree of its own** and
  reads `OpenNode.key` in place — never copied, never zeroed by the search (zeroing a key the shell still holds is the
  cr-0089 trap). ADR 0125 decision 2's "each tree's keys are zeroed at once" is the web's lifetime, which holds only the
  active space; the new ADR says so.
- **What the search unwraps itself is zeroed before its walk returns:** each medium's `K_blob` (opened to read the blob
  meta's name) and a drop-box uploader's key, in a `finally`. A test runs the scope walk over a recording suite and
  asserts every key it unwrapped is all zeroes after the walk, a thrown walk included.
- **Sign-out, a lock, a tenant switch** drop the index with the tree: it is a `fromTree` value (dropped by `forgetTree`)
  plus the media documents, which `closeTenant()` drops with the `SearchModel`.

### D6 — The index: the shell's trees, the listings by tag, revalidated while the field is used

- **Built on the first focus** of the field, not at sign-in (ADR 0125 decision 3): node documents from `trees()` (every
  visible space, D10), each node's facets read once per seal (a node whose `wrappedMeta` did not change keeps its facets,
  `documents.ts`'s rule), then each application's `scopeDocuments` per space.
- **Revalidated on every later focus and every 20 s while the list is open** (the web's `REVALIDATE_MS`): each visible
  space's `treeMoved` (a `304` keeps everything) and each application's listing by its tag. A space whose tree no longer
  reads loses its documents and is named *not searched*; one whose listing fails keeps its nodes and is named too.
- **The active space and the open node** come from the shell: the active space is the space Home or the open object
  stands in, the open node the open object (not a folder level), as `results.ts`'s `SearchContext`.
- **Rejected: a persistent index** (ADR 0125 decision 2, unchanged for the apps: nothing decrypted on disk, ADR 0140
  decision 1).

### D7 — `SearchModel`: the field's state is the core's

One per tenant, built by `ShellModel` beside `galleryModel` and dropped by `closeTenant()`. Its state `SearchShown`:
the chips (word, label, its icon kind); the suggestions under *Filter by* (each with its second line, `chipWhere`); one
list of results under *Results*, the first 10, *Show {count} more*, the exact total; per result its icon kind, its name
in parts (bold where matched), its second line (another space's name and path, this space's path, a person's
`~username`, a settings section's word and, where a settings word is the reason, that word; a photo its Memory and the
day it was taken); the status (spaces still loading after 300 ms, not searched, offline's D11 line, the empty state with
the hint); and the announcement for a screen reader. Its actions: `focus`, `text`, `choose(suggestion)`,
`removeChip(index)`, `removeLastChip` (Backspace in an empty field), `more`, `open(result)`, `close`. A keystroke cancels
the previous answer's job (the latest wins); the matcher's memory clears at `close` and when the documents change.

### D8 — Opening a result, and the open Memory's photos

- **A node** opens where it lives, through the shell: an object by `ShellModel.open` (its space becomes the active one),
  a folder on Home at that folder. **A person** by `openPerson`. **A settings section** by the platform's settings page,
  scrolled to that section (D9).
- **A photo or video another space's listing found** opens its Memory and leaves a one-shot request (`OpenRequest`, the
  web's `openRequest.ts`): the Memory's `GalleryModel` takes it once its listing holds the medium and opens the
  lightbox on it; a request its gallery cannot answer once it has read its listing is dropped; a newer choice replaces it.
- **The open Memory's photos** are the gallery's own documents (`SearchContribution`, the web's `contribution.ts`): the
  open `GalleryModel` contributes its listing's media at no fetch; they win over the same medium from the space's listing
  and open the lightbox in place.

### D9 — People and settings

- **People:** one document per member of `TenantSession.people`, named as the directory names them, found by
  `~username` too, opened on the profile. Nothing is fetched for it.
- **Settings sections are the apps' own** (ADR 0125 decision 9: "exactly the sections the settings rail offers"): core
  gains `SettingsSection` (id, word, keywords) and `offered(settings)` over the gates `AccountSettings` already exposes
  (R10); each platform's settings page anchors its sections by these ids, and the search offers what `offered` offers —
  one list, one set of gates. Keywords: the web's for the sections both have (sessions, maps, appearance, …), the apps'
  own for the rest, EN+DE in `words.json`. The second line is the settings word (*Settings*): the apps' page has no rail
  headings. Not in the apps: the web's profile, password, security, moderation, erase and tenant sections, since the apps
  do not offer them (#863, #949, block F) — they arrive with the sections.

### D10 — What is searched: the visible spaces of the current tenant

Every space of the current tenant the member has not hidden on this device (R12 — the apps' own rule, which the web does
not have). One tenant at a time, as ADR 0125 decision 3 — owner question 1. A space the core could not open (`closed`)
is named *not searched*.

### D11 — Offline: what the store keeps is searched; the photos of every space are not

Offline the trees are the store's (R11), so the nodes, their names and their Memories' facets are searched as kept, and
the roster is the kept members'. **`listScopeMedia` is not added to the kept reads:** a whole space's listing (about one
row per medium) would be the store's largest listing and needs its own sweep rule (ADR 0140 decision 6) — an amendment to
ADR 0140 that belongs with block J (#956). So offline: the media documents this session built while online stay; a
start offline has none but the open Memory's (its gallery reads the kept `listNodeMedia`), and the status says
*Photos are searched once you are online* (owner question 2).

### D12 — Where the field lives, per platform (ADR 0123 decision 9: the platform's guideline)

| Platform | The field | Chips | Lists | Keyboard |
|---|---|---|---|---|
| Android | the existing full-screen M3 `SearchBar` (Search the bar's last place) | `InputChip`s in a row under the input field, each with its close icon | *Filter by* as `ListItem`s, a `HorizontalDivider`, *Results* as a header, results as `ListItem`s (leading icon, headline an `AnnotatedString` with the bold part, supporting text the second line), *Show more* a `TextButton` | — |
| iOS, iPadOS | the existing `.searchable`: the iPhone's search tab, the split view's toolbar field | the field's **tokens** (`searchable(text:tokens:…)`) | *Filter by* through `.searchSuggestions` with `.searchCompletion(token)`; the results a `List` with sections *Results* | iPad with a keyboard: as macOS |
| macOS | the toolbar's `.searchable` | tokens | as iOS | ⌘F focuses the field (`searchFocused`); Esc closes; Return takes the first entry (`onSubmit(of: .search)`) |
| Linux | the existing `gtk::SearchBar` + `SearchEntry` under the sidebar's title | `AdwWrapBox` of chip buttons (label and a close icon) under the entry | while a query stands, the sidebar's place is a `gtk::ListBox` (`.navigation-sidebar`): a *Filter by* header, the suggestions, a separator, *Results*, rows of a markup title (escaped, the bold part `<b>`) and a dim subtitle, *Show more* | Ctrl+F (kept), Esc closes, Enter takes the first entry (`activate`), Backspace on an empty entry removes the last chip |
| Windows | the existing `AutoSuggestBox` of the `NavigationView` | a row of chip buttons at the top of the content frame while a chip is set (the box's slot holds one control) | the box's suggestion list: a *Filter by* header item, the suggestions, a separator, *Results*, the result rows (a `TextBlock` with a bold `Run`), *Show more* — non-selectable headers by a template selector | Ctrl+E and Ctrl+F (kept), Esc closes, Enter (`QuerySubmitted` without a chosen item) takes the first entry, Backspace on an empty box removes the last chip |

- **The web's highlight memory** (a moved highlight stays on its entry while typing, `chips-and-text`) is the web's
  combobox and not ported: each platform's own list keyboard navigation applies, and Enter takes the first entry.
- **The web's `/` and Ctrl+K** are the browser's (the browser owns Ctrl+F); the apps keep their platforms' find keys.
- **Screen readers:** the announcement of D7 through each platform's own channel — TalkBack's live region, VoiceOver's
  `AccessibilityNotification.Announcement`, GTK's `gtk_accessible_announce`, UIA's `RaiseNotificationEvent`.
- **A calm focus** (uc step 1) holds: focusing builds the index and shows no list; the list opens with the first
  character or a chip.
- Rejected: **WinUI Community Toolkit's `TokenizingTextBox`** — a new dependency for one control, and it does not sit in
  the `NavigationView`'s box slot. Rejected: **results in the Linux content pane** — GNOME's own apps (Settings) list a
  sidebar search's results in the sidebar, where the apps' search already shows them.

### D13 — The bridge (Linux, Windows)

`Call.SearchOpen` → `Answer.SearchOpened(id)`; `Watched.Search(id)` → `Update.Search(id, SearchShown?)`; calls
`Search.Focus|Text(text)|Choose(index)|RemoveChip(index)|RemoveLastChip|More|Open(index)|Close`. `SearchShown` is D7's
state as wire types (names, parts, words as `Piece`s, ids — no key, no seal); the model is dropped by `closeTenant()`
with the other registries. **`Call.SearchQuery`, `Answer.Hits` and `ShellWords.search` (R1) are removed once their last
caller has moved** — they stay through phases 3–7 so every phase ends with every platform building, and phase 8 (the last
caller, Windows) deletes them with `LiveBridgeTest`'s, `WireTest`'s and `DispatchTest`'s uses.

## Performance — how the apps hold ADR 0125's bounds

- **The UI thread runs no matching.** The model answers on `Dispatchers.Default`; a platform draws at most the 10 results
  and the suggestions of one `SearchShown`. ADR 0125's main-thread bounds hold by construction; what remains is latency.
- **Bounds, measured in phase 3 and recorded in the new ADR** (on a quiet machine — a timing waits for one; the load at
  both ends of a run recorded, one re-run on a red under load):
  - **B1, a keystroke:** the answer to each of "K", "Ke", "Kev", a year chip, Backspace — matched, ranked, cut to 10, put
    into a `SearchShown` — within **100 ms** at the volume (7,000 nodes over three spaces, 10,000 photos in one Memory,
    445 members), on the JVM and on `macosArm64` (Kotlin/Native, the bridged and Apple path).
  - **B2, the build:** the documents of every space from opened trees and listing pages (the network stubbed) within
    **2.6 s** on the JVM and on `macosArm64` (the ADR's unthrottled upper reading, which included the network).
  - **B3, the index's memory:** recorded (the ADR's 9.6 MB is JS heap; the JVM's is no like-for-like number), not gated.
- **Where:** `SearchVolumeMeasure` (core `commonTest`, opt-in by `VEYQ_MEASURE=1`), over synthetic documents shaped as
  the harness seeds them; and once live, the first focus against the dev instance on the Android emulator, timed from the
  model's own trace (logcat), recorded with the instance's counts.

## Inventory (locators — run, read as lists)

| What | Command (from `src/mobile/` unless named) |
|---|---|
| Every caller of the name-only search, to move (D13) | `grep -rn "ShellWords.search\|SearchQuery\|Answer.Hits\|Answer\.Hits\|class Hits" --include='*.kt' --include='*.swift' --include='*.cs' --include='*.rs' . \| grep -v /build/` |
| Every user of `Participants.fold`, to re-point (D3) | `grep -rn "Participants.fold" --include='*.kt' .` |
| Every platform's settings section, to anchor (D9) | `grep -rn 'header("' android/src/main`; `grep -n "Section" apple/Veyq/SettingsView.swift`; `grep -n "PreferencesGroup\|title(" linux/src/account_settings.rs`; `grep -n "Header\|Expander" windows/Veyq/AccountSettingsPage.cs` |
| The kind words already in `words.json` (D4) — none spelled as a bare kind at refine | `grep -n '"\(folder\|memory\|chapter\|file\|drop_box\|pot\|bookmark\|photo\|video\)":' words/words.json` → none; `grep -n "fun applicationWord\|fun tabWord" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/AppKinds.kt` |
| The web's words to carry (D7, R13) | `sed -n 1607,1640p ../web/src/i18n/index.ts`; `sed -n 3846,3880p ../web/src/i18n/index.ts`; `grep -n "search: {" -A4 ../web/src/features/memories/i18n.ts` |
| The web's test cases the vectors start from (D1) | `grep -c "^\s*it(" ../web/src/core/search/query.test.ts ../web/src/core/search/results.test.ts ../web/src/shared/text/fold.test.ts ../web/src/features/memories/search.test.ts` |
| The site pages that tell the apps' search (phase 9) | `git grep -n -i "search\|suche" -- site/src/content/docs/spaces/search.mdx site/src/content/docs/de/spaces/search.mdx site/src/content/docs/getting-started/install-the-app.mdx site/src/content/docs/de/getting-started/install-the-app.mdx` (repo root) |
| Docs naming the apps' search as names only (phase 9) | `git grep -n "Search (names only)\|ShellWords.search\|search_hint" -- docs src/mobile/CLAUDE.md site` (repo root) → `native-apps.md` § Spaces and navigation at refine |

## Gates at refine

`ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest :bridge:jvmTest` from `src/mobile/` on `6947f5048`
(the docs-only edits of this refine aside): **exit 0**, `BUILD SUCCESSFUL in 10m 37s` (2026-11-18). Every
`/usr/bin/python3 scripts/validate_*.py` but guard_gaps: exit 0 with this CR and the inbox edit in place. Not run at
refine: `:core:macosArm64Test`, `:android:assembleDebug`, the Apple builds, `run.py` on Linux and Windows,
`pnpm run check` in `src/web/` — phase 2 and phase 3 record theirs before their first edit.

## Phases

- [ ] **1 the ADR and the docs it changes** — the new ADR (the next free number, `ls docs/decisions | tail -3`; 0146 at
  refine) with D1–D13, the bounds of § Performance as planned, and its Alternatives; ADR 0125's header and Consequences
  point at it (the apps' key lifetime, D5; the apps' collation residual, D3); uc.platform.search names the apps as
  planned; `native-apps.md` § Open's search line points at this CR; `src/mobile/CLAUDE.md` names the package `search/`.
  · Done when: every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py` exits 0.

- [ ] **2 the vectors (contracts) and the web's test of them** — `contracts/platform/search/v1-vectors.json` (D1's
  sections) and `src/web/src/core/search/vectors.test.ts`, which reads it (and in write mode records it); the web's
  coverage file for uc.platform.search gains the test as a ref on the criteria its sections pin. No web behaviour
  changes. · Done when: `pnpm run check` in `src/web/` green; **bite**: one recorded order in a scratch copy of the
  vectors swapped (snapshot and restore, never `git checkout`) → `vectors.test.ts` red at that case.

- [ ] **3 the core** — (a) `Fold`/`nfd`/`NameOrder` and the pure port (`Query`, `Results`, `Documents`' pure part) with
  `SearchVectorsTest` over the vectors on the JVM and `macosArm64`; (b) `KindSearch`/`ApplicationSearch` in `AppKinds`,
  Memories' facets and scope media, `SearchIndex`, `PeopleAndSettings`, `SettingsSection`, `OpenRequest`,
  `SearchContribution` from `GalleryModel`, `SearchModel` in `ShellModel` (`ShellWords.search` stays until phase 8, D13);
  the guard `validate_mobile_search_names_no_application.py`; (c) live tests (`VEYQ_LIVE`): two spaces, a
  Memory with a date, a place and a participant, a photo in it — a year chip, a person chip, *In:* the Memory, the photo
  found from the other space and its `OpenRequest` taken by the gallery; a rename seen after `treeMoved`; a second focus
  with nothing moved costs one `304` per space and listing (counted at the transport); (d) `SearchVolumeMeasure` (§
  Performance). · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green, the live tests green, B1 and B2
  recorded and held; **bites**, each by snapshot and restore, each red at its own assertion: (a) `fold` by NFKD → the
  ligature vector red; (b) `isJsSpace` without U+FEFF → its `tails` vector red; (c) `MIN_INSIDE` 1 → "K lists no
  Reiselink" red; (d) the matcher narrowing from a 2-character text → the "K, Ke, Ken" sequence red; (e) a medium's
  `K_blob` not zeroed → the recording-suite test red; (f) a `memories` import in `search/` → the guard red; (g) a hidden
  space's node in the documents → D10's case red; (h) the gallery's own document not winning over the listing's → its
  case red.

- [ ] **4 the bridge** — D13's calls, `Watched.Search`, `SearchShown`; `closeTenant()` drops the model; `WireTest`
  samples, `DispatchTest` and `LiveBridgeTest` moved from `SearchQuery`. · Done when: `./gradlew :bridge:jvmTest` green;
  **bites**: (a) a `K_blob` field added to `SearchShown` → `SecretFreeSchemaTest` red; (b) the model left out of
  `closeTenant()` → a watch delivering after the sign-out, red.

- [ ] **5 Android** — D12's row; the words (`scripts/validate_mobile_words.py` names a hand-written one). · Done when:
  `./gradlew :android:assembleDebug` and the words guard green; the emulator pass (agent): type a Memory's name in
  another space, a year chip, a person chip and the person result, *In:*, *Kind: Photo*, open a photo of another Memory
  (the lightbox on it), a settings section (the page scrolled to it), *Show more*, airplane mode (D11's line), TalkBack
  reads the count.

- [ ] **6 Apple (iOS, iPadOS, macOS)** — D12's rows; first, measured on the macOS build: whether choosing a
  `.searchCompletion(token)` can also set the text to the suggestion's rest (else the suggestions are rows of the result
  list that call `choose`, and the ADR says so). · Done when: the macOS and
  iOS-simulator SDK builds green; the macOS pass (agent): phase 5's steps with ⌘F, Return and Esc; the iPhone and iPad
  pass is the owner's (§ Passes).

- [ ] **7 Linux** — D12's row over the bridge. First, measured against libadwaita's `.gir` in the GNOME 51 SDK: that
  `AdwWrapBox` exists, whether `AdwSidebarItem` takes markup (if it does, the results stay sidebar items), and that
  GTK's `.gir` carries `Accessible.announce` (remembered as GTK 4.14+, not read at refine). · Done
  when: `run.py test`, `bridge`, `build` green; the Broadway pass (agent): phase 5's steps with Ctrl+F, Enter, Esc and
  Backspace on an empty entry.

- [ ] **8 Windows, and the old search removed** — D12's row over the bridge; then `Call.SearchQuery`, `Answer.Hits` and
  `ShellWords.search` deleted (D13; the inventory's first locator answers empty). · Done when: `run.py test`, `bridge`,
  `build` green on the VM, and `./gradlew :core:jvmTest :bridge:jvmTest :android:assembleDebug`, the Apple builds and
  Linux's `run.py build` green after the deletion; the UIA pass (agent): phase 7's steps with Ctrl+E.

- [ ] **9 harvest** — `spaces/search.mdx` (EN+DE) tells the apps (where the field is, the keys per platform, hidden spaces
  left out, offline's photos), `install-the-app.mdx` (EN+DE) unchanged unless it names search; the ADR's bounds as
  measured, the passes recorded; ADR 0125's header names the apps built; uc.platform.search from planned to built for
  the apps; `native-apps.md` § Spaces and navigation ("Search (names only)") and § Open; the lessons to
  `changes/README.md` or the lines they bite; inbox #21's row back to its later stages; this CR deleted. · Done when:
  `pnpm run check` in `site/` green and every guard but guard_gaps green.

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android emulator (`veyq_pixel`) | agent | phase 5's steps, TalkBack's count |
| macOS build | agent | phase 6's steps, ⌘F |
| Linux Broadway | agent | phase 7's steps, Orca not available in the container — the announcement is a person's (below) |
| Windows VM | agent | phase 8's steps, Narrator's notification read through UIA's event log |
| iPhone and iPad on a device | **owner** | the search tab and the split view's field with tokens, VoiceOver's count (the simulators do not boot, #928) |
| A screen reader on Linux | **owner** | Orca reads the count and a chip added (a real GNOME session, the Fedora VM) |

## Owner questions

1. **One tenant or every signed-in tenant?** ADR 0125 decision 3 limits the search to the current tenant because the web
   would need a cross-origin read; the apps hold every signed-in tenant at once, so that reason does not hold here.
   *Recommendation:* the current tenant only, as the web — a member's tenants are separate places, and a second tenant's
   results would need their own row line ("on {tenant}") and a switch on opening; revisit when a member asks.
2. **Copy for the apps' new words** (EN / DE; the web's `shell.search.*` and `memory.search.*` are reused as they are).
   *Recommendation:* `search_offline_photos`: "Photos are searched once you are online." / "Fotos werden durchsucht,
   sobald du online bist."; `search_hint` (today "Search names") becomes the web's placeholder "Search all spaces" /
   "Alle Bereiche durchsuchen"; the settings results' second line `search_settings`: "Settings" / "Einstellungen".

## Inbox rows

#21 (this topic: block E refining into this CR), #956 (new: offline search over every space's photos, a kept
`listScopeMedia` — with block J), #832, #838, #839, #855, #435 (out of scope above).

## Definition of done

Phases 1–9 checked with their Done-when met; the vectors run by the web and by the core on the JVM and `macosArm64`;
the bites above each seen red and green again; B1 and B2 recorded and held in the new ADR; the agent's passes recorded
there and the owner's named as open where not yet run; the Use pages in EN and DE updated with `pnpm run check` in
`site/` green; every `scripts/validate_*.py` but guard_gaps green. No backend change, and the web's only change is its
vectors test: `git diff --stat` over `src/backend/` is empty and over `src/web/` names `vectors.test.ts` alone.

## Retrospective

_(filled at the end of the implementation)_
