---
id: cr-composition-holds-only-the-root
title: The Kotlin composition holds only its root — the shell's model and the drop's intake into the platform, each application's pages beside its own code
status: planned
satisfies: []
adrs: ["0156", "0155", "0079", "0138", "0154", "0150", "0122", "0136"]
invariants:
  - "an application uses the platform, never another application; the platform names no application (ADR 0079, ADR 0156) — held by the build and validate_mobile_module_graph.py rules (b) and (f)"
  - "what the platform opens to the applications (@PlatformApi) stays hidden from Objective-C and closed to the clients (rules (d), (e))"
  - "no behaviour a person sees changes on any client: every drop, Add here, page, upload and share-handoff path answers as before"
  - "the desktops' wire does not move: bridge.h, the bridge's wire classes (DropOn among them), the Rust and C# hosts unchanged"
---

> **Status: refined 2026-11-24, awaiting the owner's go** (inbox #1215). No production code is written. The technical
> choice below — option (a) for `ShellModel`, a platform intake for the drop and *Add here*, `AppleSharing` into the
> platform — is decided here (it is a technical question); the go, and § Questions for the owner, are the owner's.

## Goal

ADR 0156 entry 71 left four files in `:composition` that name more than one application and handed them back as #1215:
`ShellModel` (the shell's model, whose page factories hand each application's page its model over the shell's state),
`Drop` and `AddHere` (the drop's and the pick mode's targets: a level, Depot's upload, and a page, Memories' gallery and
buckets), and `AppleSharing` (Swift's door into a received link). After this CR `:composition` holds `AppComposition.kt`
alone — the web's `applications.ts`, which only installs. The shell's model and the intake are the platform's;
each application's page factories are extension functions in its own module over a `@PlatformApi` view of the shell;
the drop and *Add here* reach a page through one platform interface (`PageIntake`) and a level's upload through another
(`LevelUploads`). Swift's call sites of the factories do not change (Kotlin/Native exports an extension on a Kotlin class
as an Objective-C category method, measured below), Android and the bridge change imports, the desktops nothing.

## Context & constraints

- **The substrate.** Branch `claude/refine-1215` at `4baf0e54d` (= `claude/windows-cr-f6c73c` HEAD, 2026-11-24), clean.
  Nothing was built for this refinement. The generated Objective-C header read below is the one the main session's
  worktree built the same day (`…/worktrees/optimistic-meninsky-0d2f20/src/mobile/composition/build/bin/macosArm64/debugFramework/VeyqCore.framework/Versions/A/Headers/VeyqCore.h`,
  dated 2026-11-24 04:18) — a build of the post-split tree, not of this branch; the export rule it shows is the
  compiler's, not the branch's.
- **The rules.** [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (an
  application uses core, never the reverse, never another application);
  [ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md)
  decision 3 (the composition) and decision log 67–72 (#1048: what left the composition, rule (f), entry 71's hand-back);
  [ADR 0138](../docs/decisions/0138-the-apps-take-what-the-system-shares-the-chosen-nodes-kind-decides-what-it-becomes.md)
  decisions 3 and 9 (*Add here*, a drop onto a level); [ADR 0154](../docs/decisions/0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)
  decision 1 (the one drop, its one gate); [ADR 0150](../docs/decisions/0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md)
  (the received link's guest door, which `AppleSharing` is).
- **The guard.** `scripts/validate_mobile_module_graph.py` rule (f) and its `COMPOSITION_FILES`; rules (d) (only the
  core's modules opt into `PlatformApi`) and (e) (every `@PlatformApi` declaration of a common/native set carries
  `@HiddenFromObjC`). `src/mobile/CLAUDE.md` § The core's modules and § Traps (Objective-C names) bind the move.
- **The precedent.** ADR 0156 entry 69 already put an application's Apple door as extensions on a platform object
  (`AppleSessions.downloadPot`/`openPot`/`openReceipt` in Expenses' `appleMain`), and `ShellDoor`
  (`core/navigation/ShellDoor.kt`) is already a platform interface `ShellModel` implements so Expenses' `PotModel`
  copies and creates through the shell without naming it.

### What the row's question was, re-derived

The row asked "(a) or (c) for `ShellModel`, the drop follows". Measured against the tree, three of its premises move:

1. **`AppleSharing` names one application, not two.** Its only Memories reference is an unused import
   (`import me.raddatz.veyq.mobile.memories.MediaUpload`, line 16; `grep -n "MediaUpload" …/AppleSharing.kt` → the import
   alone). Rule (f) counts it as naming two because imports are code. What it really holds is the received-link guest
   door (all platform: `GuestView`, `GuestScreen`, `BookmarkPage`, libsodium, URLSession, the Keychain) plus two Depot
   calls (`guestUploadFolder` → `DepotGuest.uploadFolder`, `saveFile` over Depot's `FileModel`). It is not "Swift's share
   handoff" either: the share extension (`apple/VeyqShare/ShareViewController.swift`) does not call it. So it is the
   cheap first slice, independent of the rest, and the guard's reason for it is stale.
2. **"~40 factories" is 25.** The public `ShellModel` members that name an application (measured with
   `scratch members.py`, § Inventory): `personMemories`, `addHere`, `drop`, `moveSheet`, `fileModel`, `depotUploads`,
   `depotUploadsImporting`, `mediaUploads`, `createMemoryNode`, `createMemory` ×3, `offersAutoPlace`, `offersWasThere`,
   `contributions`, `galleryModel`, `placeNames`, `mapServices` (it sets Memories' `placeNames`), `dateEditor`,
   `placeEditor`, `memoriesMap`, `memoryDates`, `trackList`, `musicModel`, `dropBoxPage`, `galleryAt` (and the companion's
   `galleryAt`, `found`). Beside them, seven non-factory places name an application inside the class: `moveDoor`'s
   `pot: PotModel?` and the `potCopyRefused` import (Expenses), `shareDoor.createDropBox` (Depot's `DropBoxes`), `found`'s
   `GalleryModel.rowPage` and `galleryAt`'s `GalleryModel.MEMORY/CHAPTER` (Memories), `queuedTiles`/`queuedHere`
   (Memories' `UploadTile`, `GalleryModel.QueuedHere`), `memoryAccess`/`memoryRights` (Memories, with the literal
   `"memories"`), and `drop`/`addHere` themselves (through `DropTarget`/`AddHere`).
3. **The desktops are not affected by any option.** No Rust or C# file names a `ShellModel` member or the core's
   `DropTarget`/`AddHere` (§ Inventory: 0 hits; Linux's `gtk::DropTarget` and Windows' `OleDropTargetInterface` are the
   toolkits' own). The desktops reach the shell through the bridge's `Tenant` class (`bridge/…/Bridge.kt` 227–403), whose
   lambdas call the factories, and through the wire class `DropOn`, which the bridge maps to `DropTarget` in three lines
   (3532–3537). Both are internal to the bridge; `bridge.h` and the wire do not move under (a) or the intake.

### How Swift sees a Kotlin extension (measured, not assumed)

- **The compiler's rule** (kotlinlang.org, *Interoperability with Swift/Objective-C* § Extensions and category members):
  an extension on a "regular" Kotlin class is exported as a Swift extension / Objective-C category member; an extension
  on an interface, `String`, a collection, a primitive, an inline class, `Any`, a function type or an Objective-C type
  is exported as a top-level function with an extra receiver parameter (on the file's `…Kt` class).
- **The header shows it on our types.** `grep -n "^@interface VeyqCore[A-Za-z]* ([A-Za-z]*)" VeyqCore.h` → nine
  categories, among them `@interface VeyqCoreShellModel (Extensions)` holding `download(ref:deletedName:nowMs:path:completionHandler:)`
  (`NodeDownload.kt`'s `ShellModel.download`, an extension from another source set) and `@interface VeyqCoreAppleSessions (Extensions)`
  holding Expenses' `downloadPot`/`openPot`/`openReceipt`/`picked`/`receiptKind`/`receiptMediaType` — an extension
  declared in an **application's** module on a **platform** object, called from Swift as `AppleSessions.shared.openPot(…)`.
  A `suspend` extension exports with its `completionHandler` as a member does.
- **What follows for the options.** (a) keeps Swift's call sites only because `ShellModel` stays a *class* and moves
  whole into the platform: the extensions in `:apps:memories` and `:apps:depot` become category methods on
  `VeyqCoreShellModel`, so `m.galleryModel(ref:uploads:music:calendar:)` reads as today. The variant "keep `ShellModel`
  in the composition, put the factories on `ShellDoor`" would export each factory as `ShellPagesKt.galleryModel(_:ref:…)`
  — every Swift call site changes. That variant is therefore not (a).

## Decisions (technical, settled here)

1. **(a), not (b), not (c).**
   - *(b) hooks per page kind* (`ApplicationKinds.page(kind)` handing a model) does not fit what the factories take:
     their parameters differ per page (`uploads`, `music`, `calendar`; `queue`, `deriver`, `left`, `changed`;
     `measure`), so one hook signature means an untyped argument bag, and every typed call site on Apple (19 factory
     calls), Android (16) and the bridge (17) becomes a lookup plus a downcast. It is the web's shape because the web's
     pages are routes; the apps' pages are typed models a native screen holds. Rejected.
   - *(c) keep* is legitimate and costs nothing now. Against it: `ShellModel` (1,757 lines, `wc -l`) is the platform's
     shell, and as long as it lives in the module that names every application, every platform change to the shell is
     made beside 25 application factories, a new application adds its pages to a file it does not own, and the tests of
     one application's pages run in `:composition` instead of over `TestApplications.install(<its part>)` in its module.
     The guard keeps the list from growing, but not from staying. With (a) the composition becomes the root alone and
     rule (f) collapses to one line. The cost is bounded and mechanical (§ Per-client change), behaviour-preserving, and
     held by the existing suites. **(a) is chosen**, in three slices that each leave the tree green — and slice 1 is
     worth landing even if the owner chooses (c) for the rest.
2. **`AppleSharing` (slice 1).** The unused Memories import goes. `AppleSharing`, `GuestOpener`, `AppleOpenLink` and
   `KeychainGuestSessions` move to the platform's `appleMain` (package `me.raddatz.veyq.mobile.sharing`, rule (c): the
   `composition` package lives only in `:composition`). `guestUploadFolder` and `saveFile` become extensions on the
   `AppleSharing` object in Depot's `appleMain` (`AppleDepot.kt`), entry 69's pattern; Swift keeps
   `AppleSharing.shared.guestUploadFolder(…)`/`saveFile(…)`. Objective-C names come from simple class names, so no Swift
   type name changes (checked in the header diff, decision 7).
3. **The intake (slice 2): two platform interfaces, `Drop` and `AddHere` into the platform.**
   - `navigation/PageIntake` — a page that takes files put onto it (a drop, *Add here*):
     ```kotlin
     interface PageIntake {
         val node: NodeAt                                  // the page's node (AddHere.at matches the shell's open object)
         val answer: ShareAnswer                           // what of a drop or a share it keeps (MemoryShare.answer today)
         fun intake(): Intake                              // TAKES, Refused(notice), NONE — GalleryModel.takesUploads, Contributions.intake
         fun upload(sources: List<UploadSource>): Int      // gallery::upload, contributions::upload
         fun notTaken(files: Int, folders: List<String>): List<Notice>  // MediaUploads.notTaken, MEMORY_DROP_FOLDER
         fun offers(inbox: ShareInbox): Boolean = false    // MemoryShare.offered (Add here); a bucket offers none
         fun take(inbox: ShareInbox): ShareInbox.Taken? = null  // MemoryShare.addHere
         sealed class Intake { … }
     }
     ```
     Memories' `GalleryModel` and `Contributions` implement it. The member list is exhaustive for today's two
     implementers; the implementer may merge `offers`/`take` into one where the code shows it reads better.
   - `blob/LevelUploads` — a level's upload, what `Drops.ontoLevel` and `AddHere.Level` call of `DepotUploads` today:
     `upload(scopeId, parentId, label, sources, left): Int`, `screen(…)`, `screenFolder(…)`, `uploadFolder(…)` and the
     result type `Screened` (today `DepotUploads.Screened`, which becomes `LevelUploads.Screened`). Depot's
     `DepotUploads` implements it. Depot's guest level upload (`DepotGuest`) is a guest door, not a drop target today,
     and stays out.
   - `FolderUpload.rootName` (Depot) moves to `blob.DirectoryWalk.rootName` (entry 68 moved the walk; the name of a
     walked folder is the walk's), so `Dropped.Folder.name` names no application.
   - `Drop.kt` and `AddHere.kt` move to the platform's `navigation/`. **Public shape:** `DropTarget.Gallery(gallery)` and
     `DropTarget.Bucket(contributions)` become `DropTarget.Page(intake: PageIntake)`; `AddHere.Gallery(gallery)` becomes
     `AddHere.Page(intake)`; `DropTarget.Level`/`Row` and `AddHere.Level` keep their shape, their `depot` parameters
     typed `LevelUploads?` (a `DepotUploads` passes unchanged). `ShellModel.addHere(inbox, shell, gallery:)` becomes
     `addHere(inbox, shell, page:)`.
   - The per-application holdings on the phones that claim a target by its variant (`MemoriesHolding`: `is
     DropTarget.Gallery || is DropTarget.Bucket`, `as? AddHere.Gallery`) claim `DropTarget.Page`/`AddHere.Page` — only
     Memories implements `PageIntake` today, so the claim is unchanged in effect. A second implementer later claims by
     `intake is GalleryModel`; that is that CR's question.
4. **The shell (slice 3): `ShellModel` into the platform whole.**
   - It moves to `core/navigation/ShellModel.kt` (package `navigation`), keeps its name, its constructor and every
     member that names no application. The wrappers `AppleQueued.kt`, `FileDownloads.kt` (`subtree`) and
     `NodeDownload.kt` then name nothing a platform file may not and move with it (their `wraps=True` entries go).
   - **The factories become extensions in their application's module** — Memories' (`personMemories`, `mediaUploads`,
     `createMemoryNode`, `createMemory` ×3, `offersAutoPlace`, `offersWasThere`, `contributions`, `galleryModel`,
     `placeNames`, `dateEditor`, `placeEditor`, `memoriesMap`, `memoryDates`, `trackList`, `musicModel`, `galleryAt` and
     the private `queuedTiles`, `queuedHere`, `memoryAccess`, `memoryRights`) in `apps/memories/…/memories/ShellPages.kt`;
     Depot's (`fileModel`, `depotUploads`, `depotUploadsImporting`, `dropBoxPage`) in `apps/depot/…/depot/ShellPages.kt`.
     Same names, same parameters, same defaults, same overloads (the three `createMemory` stay three: Swift sees no
     Kotlin default). Expenses has no factory.
   - **The view they read** is `ShellModel`'s own members opened `@PlatformApi @HiddenFromObjC` — not a second
     interface: `tenantId`, `queues(q)`, `wallClock()`, `now`, `madeOffline(nodeId)`, `offlineNow()`,
     `scopeNodesWithQueued(scopeId)`, `noRoom`, `readOnly` (the implementer adds what compiling the move asks for, each
     with both annotations; rule (e) is red on a missing one). Already public and read as they are: `tenant`, `online`,
     `state`, `refresh`, `reportOffered`, `inIdentityFolder`, `open`, `viewModes`, `openRequests`, `searchContributions`.
   - **Per-shell state an application keeps.** `placeNames` is Memories' state on the shell today (set by
     `mapServices`, read by `offersAutoPlace`, the settle and the editors). The shell keeps what is the platform's —
     the `MapServices` it built and the language function — and offers an application one keyed slot per shell
     (`@PlatformApi fun <T : Any> part(key: ApplicationId, make: () -> T): T`, dropped with the shell); Memories'
     `placeNames` extension property derives `PlaceNames(mapServices, lang)` once through it. `mapServices` stays a
     platform member; its signature does not change.
   - **The seven non-factory places get the platform's form:**
     - `moveSheet(node, copying, pot: PotModel?, …)` → `moveSheet(node, copying, open: OpenCopy?, …)`, where
       `navigation/OpenCopy` is a `fun interface` copying the open object by its own model
       (`suspend fun duplicate(shell: ShellDoor, ref: ObjectRef, to: String?, deletedName: String, uuid: () -> String): Notice?`);
       Expenses' `PotModel` implements it (its `duplicate` already takes a `ShellDoor`; the `potCopyRefused` mapping moves
       into Expenses with it). Kotlin callers pass the Pot model positionally and do not change; Swift's label `pot:`
       becomes `open:` (two sites).
     - `shareDoor.createDropBox` asks a registry hook `ApplicationKinds.dropBoxMaker` that Depot registers (ADR 0156
       entry 70's pattern: `rowParts`, `queuedShown`, `shareShape`).
     - `found`'s `GalleryModel.rowPage` and the companion `galleryAt`'s kinds ask a hook `ApplicationKinds.rowPage(node)`
       (the object a non-object row opens as — a chapter its page) that Memories registers; `ShellModel.galleryAt`
       (instance) is Memories' extension.
     - `queuedTiles`/`queuedHere`/`memoryAccess`/`memoryRights` move with the gallery factory.
     - `drop`/`addHere` read the platform's intake (decision 3).
   - **Kind literals stay out of scope.** `MAP_TAB = "memories.map"` and `KNOWN_APPLICATIONS` are literals the
     platform already spells elsewhere (`MapServices.MAP`, `Applications.kt`'s `BarTab("memories.…")`, `Account.kt`);
     a literal guard over `:core` would be red on its first run (`grep -rnE '"(memories|depot|expenses)[".]'
     src/mobile/core/src/*Main` → 8 hits outside comments). That is a separate question (whether `Applications.kt`
     is the platform's registry by design, as the web's `registry.ts`), not #1215's; filed by this CR's harvest if the
     owner wants it.
5. **The guard follows each slice.** After slice 1 `COMPOSITION_FILES` loses `appleMain/AppleSharing.kt`; after slice 2
   `Drop.kt` and `AddHere.kt`; after slice 3 every entry but `AppComposition.kt`, and rule (f) is simplified to "the
   composition's main sources are `AppComposition.kt` alone" (the `wraps` flag and its branch go; the list-only-shrinks
   check stays, now trivially). Its docstring's (f) paragraph and its limit sentence are rewritten to say so.
6. **One PR** (owner 2026-11-23: one PR per feature), three slice commits in the order below; main does not need a slice
   before the next.
7. **Swift's surface is proved by the header, not by reading Swift.** Before slice 1, save the generated header's
   `swift_name(...)` selectors of every class this CR touches (`ShellModel` and its categories, `AppleSharing`,
   `DropTarget*`, `AddHere*`, `DepotUploads`, `GalleryModel`, `Contributions`, `PotModel`); after each slice diff them.
   The only differences allowed are the ones this CR names: `DropTarget.Gallery`/`Bucket` → `DropTarget.Page`,
   `AddHere.Gallery` → `AddHere.Page`, `addHere(…gallery:)` → `(…page:)`, `moveSheet(…pot:…)` → `(…open:…)`, the new
   protocols `PageIntake`/`LevelUploads`/`OpenCopy` and their conformances. An unplanned difference (a `_`-mangled
   selector from a category clash, a renamed class) is a stop: Kotlin/Native renames a clashing category member silently.

## Per-client change (measured at refine, `scratch sites.py` and the greps of § Inventory)

| Client | (a) `ShellModel` factories | (a) other `ShellModel` changes | Intake (slice 2) | `AppleSharing` (slice 1) |
|---|---|---|---|---|
| **Apple (Swift)** | 0 of 19 factory call sites change (category methods; `DepotHolding` 4, `MemoriesHolding` 14, `PersonMemoriesSection` 1) | `moveSheet` label `pot:` → `open:`: 2 sites (`AppModel.swift` 1452, `ExpensesHolding.swift` 46) | `DropTarget.Gallery/Bucket` constructions 3 (`MemoryScreen.swift` 340–341), the holdings' claims 3 (`MemoriesHolding.swift` 76, 87, 97), `addHere(…gallery:)` → `page:` 2 (`AppModel.swift` 913, `MemoriesHolding.swift` 70) — ~8 | 0 (Swift calls `AppleSharing.shared.…` 21×, unchanged) |
| **Android** | 16 factory calls unchanged; imports added in 2 files (`DepotHolding.kt`, `MemoriesHolding.kt`) | `ShellModel`'s import path in 5 files; `moveSheet` positional, unchanged | `DropTarget.Gallery/Bucket` 4 sites, `AddHere.Gallery` 1, the `is` checks in `MemoriesHolding.kt` (104, 110) and `MemoryScreen.kt` 229; the `composition.Drop*`/`AddHere` import path in ≤ 9 files | — |
| **Bridge (Kotlin)** | 17 factory calls in `Bridge.kt` unchanged; their imports added (1 file) | `ShellModel`'s import path in 6 files | `DropOn` → `DropTarget` mapping 2 lines (3532–3533), `jvmTest` 2 | — |
| **Linux (Rust)** | 0 | 0 | 0 (`DropOn` on the wire unchanged) | — |
| **Windows (C#)** | 0 | 0 | 0 | — |
| **`:composition` tests** | the tests of one application's pages move to that application's module over `TestApplications.install`; those across applications stay (§ Inventory: 13 + 14 files import `ShellModel`) | — | `DropTarget`/`AddHere` cases 9 sites | `appleTest` 1 file |

## Inventory (locators — run at refine, read as lists; re-run at implement)

The two scratch scripts are re-written at implement from their description; their output at refine is quoted above.

| What | Locator (from the repo root) | At refine |
|---|---|---|
| `ShellModel` members that name an application, with their visibility | a member splitter over `ShellModel.kt` (members at indent 4 and 8, each body grepped for `mobile\.(api\.)?(depot\|expenses\|memories)\b`) — `members.py` | 25 public (decision 4's list) + private `queuedTiles`, `queuedHere`, `moveDoor`, `depotUploads`, `memoryAccess`, `memoryRights`; 3 hits are KDoc spill (`chooseView`, `musicPlayer`, two `val` locals) |
| Second angle: what the class names of each application, by symbol | `grep -noE "me\.raddatz\.veyq\.mobile\.(api\.)?(depot\|expenses\|memories)[A-Za-z.]*" ShellModel.kt \| awk -F: '{print $2}' \| sort \| uniq -c` plus `grep -n '^import' ShellModel.kt` | Depot 21 symbols, Expenses 2 (`PotModel`, `potCopyRefused`), Memories ~80 |
| Call sites per client (factories, `drop*`, `addHere`, `moveSheet`, `openFound`) | `grep -rnoE "[A-Za-z_.]*\.(personMemories\|addHere\|drop\|dropTarget\|dropLevel\|moveSheet\|fileModel\|depotUploads\|depotUploadsImporting\|mediaUploads\|createMemory\|createMemoryNode\|offersAutoPlace\|offersWasThere\|contributions\|galleryModel\|mapServices\|dateEditor\|placeEditor\|memoriesMap\|memoryDates\|trackList\|musicPlayer\|musicModel\|dropBoxPage\|galleryAt\|placeNames\|openFound)\b *[({]" src/mobile/android/src/main src/mobile/bridge/src src/mobile/apple`, read as a list (a Swift `model.memories.x(` is the holding's own method, not the shell's) | § Per-client change |
| The desktops name none of it | the same grep over `src/mobile/linux/src` and `src/mobile/windows`, and `grep -rn "DropTarget\|AddHere" src/mobile/linux/src src/mobile/windows src/mobile/bridge/bridge.h` | 0 of ours (toolkit `DropTarget`s only) |
| The intake's variants per client | `grep -rnE "(DropTarget\|AddHere)\.(Gallery\|Bucket\|Level\|Row\|Offered)\|is AddHere\|as\? AddHere\|takeOrOffer\|dropOrOffer" src/mobile/android/src src/mobile/apple src/mobile/bridge/src src/mobile/composition/src` | Android 5 + 3 checks, Apple 8, bridge 4, composition tests 9 |
| `AppleSharing`'s users and its one Memories reference | `grep -n "MediaUpload" src/mobile/composition/src/appleMain/…/AppleSharing.kt`; `grep -rn "AppleSharing.shared\.[a-zA-Z]*" -o src/mobile/apple \| sed 's/.*shared\.//' \| sort \| uniq -c`; `grep -rn AppleSharing src/mobile/apple/VeyqShare` | the import alone; 17 members called, 2 of them Depot's; the share extension 0 |
| Files that import `ShellModel` or the composition package | `grep -rlE 'mobile\.composition\.(ShellModel\|\*)' <dir>` and `grep -rl 'mobile\.composition' <dir>` per client and test set | Android 5/9, bridge 6/6, composition commonTest 13/56, jvmTest 14/24, appleTest 1/2 |
| Objective-C export of extensions | `grep -n "^@interface VeyqCore[A-Za-z]* ([A-Za-z]*)" VeyqCore.h` on a freshly built framework | 9 categories, `ShellModel (Extensions)` and `AppleSessions (Extensions)` among them |
| Kind literals the platform spells (out of scope, decision 4) | `grep -rnE '"(memories\|depot\|expenses)[".]' src/mobile/core/src/*Main` | 8 |
| Docs that name the moved symbols | `grep -rln "ShellModel\|AppleSharing\|COMPOSITION_FILES\|composition/" docs src/mobile/CLAUDE.md site/src` | `src/mobile/CLAUDE.md` (§ The core's modules), `docs/apps/README.md` (§ The apps' Kotlin core), `docs/crosscutting/native-apps.md`, ADRs 0123/0136/0138/0142/0146/0150/0152/0153/0154/0155/0156, `uc.sharing.keep-a-received-link` + its coverage YAML; `site/` none |
| Coverage YAMLs citing tests this CR moves | `grep -rn "ShellModelTest\|DropTest\|AddHereTest\|<each moved test class>" docs/coverage` | re-run at implement once the moved test list is known |

## Phases

**Phase 0 — baseline.** On the untouched tree: `./gradlew jvmTest macosArm64Test compileKotlinJs compileTestKotlinJs`
(from `src/mobile`), `./gradlew :composition:assembleVeyqCoreDebugXCFramework`, the Apple build (`xcodebuild … -sdk macosx
… CODE_SIGNING_ALLOWED=NO`), `./gradlew :android:assembleDebug` (or the Android unit tests the surface's CLAUDE.md
names), `/usr/bin/python3 scripts/validate_mobile_module_graph.py`. Save decision 7's selector set and the list of
executed JVM test cases (`testcase` names under every `build/test-results/jvmTest`, changes/README § A move of tests
compares the cases that ran). Done when: every one green and both lists saved in the scratchpad.

**Phase 1 — `AppleSharing` (decision 2).** Guard first: drop the unused import alone and run the guard — **red** on
`AppleSharing.kt: names the application depot alone` (the live defect the stale reason hid). Then move as decision 2
says and drop its `COMPOSITION_FILES` entry. Done when: the guard green; `:composition` and `:apps:depot` `macosArm64`
compile; the Apple build green; the selector diff empty for `AppleSharing` (its two Depot members now in an
`AppleSharing (Extensions)` category, same selectors).

**Phase 2 — the intake (decision 3).** `PageIntake`, `LevelUploads`, `DirectoryWalk.rootName` in the platform;
`GalleryModel`, `Contributions`, `DepotUploads` implement them; `Drop.kt`/`AddHere.kt` to `core/navigation/`; the
clients' sites of § Per-client change; the bridge's `DropOn` mapping. Bite before the move: with the interfaces in and
`Drop.kt` still in `:composition`, re-pointing its imports to the interfaces makes rule (f) **red** ("names no
application — it belongs in :core"); green once moved. Done when: every suite of phase 0 green with the same executed
cases (renamed classes mapped), the drop and *Add here* tests in `:core` over `TestApplications` (a gallery and a bucket
intake, a level, a row, the refused paths), the Apple and Android builds green, the selector diff exactly decision 7's
intake lines.

**Phase 3 — the shell (decision 4).** In this order, each step compiling: (i) the hooks `dropBoxMaker` and `rowPage`,
`OpenCopy`, the per-shell part slot, with `ShellModel` still in `:composition` and every existing test green; (ii) the
view members opened `@PlatformApi @HiddenFromObjC`; (iii) the factories out to `memories/ShellPages.kt` and
`depot/ShellPages.kt` as extensions (the class still in `:composition`, so a failure names the factory, not the move);
(iv) `ShellModel` and the three wrappers into `:core`; (v) the tests re-homed. Bite for the guard's new form: a stub file
added to `:composition` main → red; `ShellPages.kt` copied back into `:composition` → red (it names memories alone).
Done when: `COMPOSITION_FILES` is `AppComposition.kt` alone and rule (f) is decision 5's; every suite of phase 0 green;
the executed JVM case names before/after equal modulo moved packages; the selector diff exactly decision 7's list;
`git diff --stat 4baf0e54d -- src/mobile/bridge/bridge.h src/mobile/linux src/mobile/windows` empty.

**Phase 4 — passes.** No behaviour changes, but the drop and *Add here* paths and every page factory moved, so the
passes that proved them are re-run (changes/README § A refactor that moves an existing flow re-runs that flow's pass):
on the Mac app and the Android emulator — a file and a folder dropped onto a level and a folder row, photos dropped
onto a Memory and onto a Contributions bucket (one refused as not own), *Add here* from the pick mode into a Memory and
into a folder, a Memory and a chapter made, a file page and a drop box page opened, a Pot copied from its page, a
received link opened and a guest's file saved, a guest's folder uploaded. Linux and Windows: the bridge's `jvmTest`
(`DispatchTest`, `LiveBridgeTest`) is their proof, the wire being unchanged; no VM pass owed. Done when: each listed
step passes on both, recorded in the decision log.

**Phase 5 — docs and harvest.** ADR 0156: decision 3's sentence on what the composition holds, and decision-log
entries for slices 1–3 (with decision 7's measured selector diff and the export rule); `src/mobile/CLAUDE.md` § The
core's modules (the composition installs only; the shell is the platform's; an application's pages are its
extensions on `ShellModel`; `PageIntake`/`LevelUploads`), `docs/apps/README.md` § The apps' Kotlin core,
`docs/crosscutting/native-apps.md` where it names `navigation/ShellModel` and `ShellModel.drop`; the coverage YAMLs
whose cited tests moved; inbox #1215 deleted. Done when: the trust-chain guards green
(`/usr/bin/python3 scripts/validate_*.py`, guard-gaps only if a validator changed — rule (f)'s does, so it runs, with
cases 133–135 re-pointed and the phase-3 bites added as cases).

## Risks

- **A category clash renames a selector silently.** Two extensions with one Objective-C selector on `ShellModel` (from
  two modules, or an extension against a remaining member) get a `_` suffix with no warning. Decision 7's diff is the
  catch; the names are distinct today (§ Inventory list).
- **Kotlin shadows an extension by a member.** A factory left as a member while its extension is added compiles and the
  extension is never called. Step (iii) deletes the member in the same edit that adds the extension.
- **The view grows.** Each `@PlatformApi` member is surface the applications may read. Step (ii) opens only what the
  moved factories call; a member opened and unused afterwards is closed again (the build tells: remove it, recompile).
- **Test re-homing drops cases.** Measured by the executed-case diff, not the count.
- **The bridge's `Tenant` lambdas and the phones' holdings keep naming applications.** Correct by ADR 0156 (a client
  reaches every application through the composition); not this CR's to change.

## Questions for the owner

1. **Go for all three slices, or slice 1 alone?** Slice 1 (`AppleSharing`, a stale guard reason and a misfiled
   platform door) is cheap and right under (c) too. Slices 2–3 are the real choice: they cost one design-heavy,
   behaviour-neutral PR across `:core`, two applications, the phones and the bridge (≈ 8 Swift edits, ≈ 15 Android, ≈ 5
   bridge, the composition's tests re-homed), and buy a composition that only installs. My recommendation is all three.

## Definition of done

Rule (f) of `validate_mobile_module_graph.py` admits `AppComposition.kt` alone and is bitten by the phase-3 cases (added
to `validate_guard_gaps.py`); `:composition` holds no other main file; every core module's JVM, native and JS-compile
suites, the bridge's `jvmTest`, the Apple build and the Android build green, the executed JVM cases equal before/after;
the Objective-C selector diff exactly decision 7's list; the desktops' sources and `bridge.h` untouched; the phase-4
passes recorded; ADR 0156, `src/mobile/CLAUDE.md`, `docs/apps/README.md`, `docs/crosscutting/native-apps.md` and the
coverage YAMLs updated; no `satisfies:` use case (structural, no acceptance criterion changes) and no `site/` page
affected (none names these types).

## Decision log

Built 2026-11-24 on `claude/composition-root-1215` from `0e922e641` (owner go 2026-11-24: "alle drei, los").

1. **Phase 0 — baseline measured.** `./gradlew --continue compileKotlinJs compileTestKotlinJs jvmTest macosArm64Test
   :composition:assembleVeyqCoreDebugXCFramework` exit 0 (28 min, a fresh worktree builds libsodium and libwebp);
   3,177 executed JVM and macOS test cases saved, and the header's selectors (every `@interface`/`@protocol` member of
   `VeyqCore.h`, 22,922 lines) — the whole header, not only the touched classes, so an unplanned rename anywhere shows.
2. **Slice 1 — `AppleSharing` to the platform.** The guard bit first: with the unused Memories import dropped alone,
   rule (f) went red on exactly `AppleSharing.kt: names the application depot alone`. Moved to core's `appleMain`
   (`sharing/AppleSharing.kt`, with `GuestOpener`, `AppleOpenLink`, `KeychainGuestSessions`); `guestUploadFolder` and
   `saveFile` became extensions on it in Depot's `AppleDepot.kt`. Selector diff: exactly those two members moved from
   `AppleSharing` to the category `AppleSharing (Extensions)`, same Swift names; nothing else in the header moved.
   macOS app build green; no Swift edit.
3. **Slice 2 — the intake.** The bite first: `PageIntake`/`LevelUploads` in core and `Drop.kt`'s imports re-pointed to
   them while it still sat in `:composition` → rule (f) red on exactly `Drop.kt: names no application`; green once moved.
   Where reality differed from the CR, decided here:
   - **`PageIntake`'s members are named for the interface, not after Contributions'.** `Contributions` already has
     `intake(): Contributions.Intake` (its four-way enum, read by the apps); one class cannot hold a second `intake()`
     with another return type, so the platform's is `dropIntake(): PageIntake.Intake` (`Takes`, `Refused(notice)`,
     `NotHere`). `notTaken(files, folders)` split into `foldersNotTaken(names)` and `filesNotTaken(count)`: the drop says
     the folders first, then what did not read, then what the answer left — one merged call would have reordered the
     notices. `offers`/`take` are `offersShare`/`takeShare` (a bare `take` reads as the drop's).
   - **`Drops` and `DropOutcome.notHere` opened `@PlatformApi @HiddenFromObjC` for this slice only:** the composition's
     `ShellModel` still calls them; slice 3 makes them `internal` again.
   - **`DropTarget.Page` judges by the page's `dropIntake()` and sorts by its `answer`** — the gallery's and the bucket's
     answer both `MemoryShare.answer`, as `Drops.sorted` used for both; the outcome and its notices' order unchanged.
   - **Tests.** `BucketDropTest` named Memories and the drop alone, so it moved to `:apps:memories`. The platform's drop
     and *Add here* are held in core by `PageIntakeTest` over a fake page and a fake level (sorting, notice order, a
     refusal that keeps and hands back nothing, an unqueued file handed back, a level's screen then each folder, *Add
     here* only on the open page). `FolderUploadTest.theRootIsTheDirectorysOwnName` moved with `rootName` to core's
     `DirectoryWalkTest`. Executed cases before/after: the same 3,177 plus the five new `PageIntakeTest` cases (×2
     targets), the moved one renamed — nothing dropped.
   - **Clients.** The bridge's `DropOn` maps to `DropTarget.Page` (2 lines), its `DispatchTest` tells gallery from bucket
     by `intake is GalleryModel`; Android 7 files (import paths, `MemoriesHolding`'s claims as `AddHere.Page` with a
     `GalleryModel` intake), Swift 3 files (`DropTarget.Page(intake:)`, `addHere(…page:)`, the holding's claim through
     `page.intake as? GalleryModel`). Desktops and `bridge.h`: no diff.
   - **Selector diff:** exactly the planned set — `DropTarget.Gallery`/`Bucket` → `DropTarget.Page(intake:)`,
     `AddHere.Gallery` → `AddHere.Page(intake:)`, `addHere(…gallery:)` → `(…page:)`, the protocols `PageIntake` and
     `LevelUploads` with `GalleryModel`'s and `Contributions`' conformance members, `DepotUploads.Screened` →
     `LevelUploadsScreened`, `rootName(shown:path:)` from `FolderUpload` to `DirectoryWalk`, and the `depot:` parameters
     typed `id<LevelUploads>`; no selector renamed.

## Retrospective

(filled at implement)
