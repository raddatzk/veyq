# ADR 0146 — The apps search with the core's port of the web's search; both answer to one set of vectors
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows), the name-only search removed; passed on Android,
  macOS, Linux and Windows (§ Consequences, the passes); the iPhone's and the iPad's passes, Orca's and the Mac's keys
  frontmost are the owner's ([native-apps](../crosscutting/native-apps.md) § Open)
- Date: 2026-11-18
- Model: [uc.platform.search](../features/platform/use-cases/uc.platform.search.md)

## Context

[ADR 0125](0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md) built one search on the web: a
field over every space of the tenant, chips that are predicates over every document, one list ordered by the match and
then by where a result lies, every photo and video of every space through `listScopeMedia`, the tenant's members and
the settings sections as results, no guest search. The apps had a name-only search: the shell's objects whose name
contains the typed text, no folders, no facets, no order, no diacritic folding, called from each platform's UI.

An app's logic is the Kotlin core's, and each platform draws only its own UI
([ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)). The core already walks every
space for the shell: the drawer, Home and every application list every space
([ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)), so each space's tree is
opened at the session's start and held (`TenantSession.trees()`, with `fromTree` for what is derived from it). The web's
search is a measured module of about 1,600 lines (`src/web/src/core/search/`, Memories' `search.ts`).

## Decision

1. **One algorithm, two implementations, one set of vectors.** The core ports `core/search` to Kotlin
   (`core/…/search/`), rule by rule, and both the web and the core run `contracts/platform/search/v1-vectors.json`, as
   the crypto suite answers to `contracts/crypto/vectors` and the ledger to `contracts/expenses/v1-vectors.json`. The
   vectors are the one truth; neither implementation is the other's test.
   - **What they hold**, each case with a `rule` note naming the ADR 0125 decision or the web function it pins: `fold`
     (NFD marks, ß, İ, a ligature that NFD keeps, a supplementary-plane letter); `tails` and `wordPrefix` (a no-break
     space and U+FEFF as JavaScript's `\s` reads them, a boundary after `²`); `suggest`; `match` (documents, query,
     context, limit → the ordered ids and the total); `matcher` (a typed sequence whose answers equal fresh queries);
     `nameParts`; `facets` (a Memory's plaintext meta and a fixed `now`) and `medium` (a listed tier and blob meta → a
     photo, a video or no gallery medium).
   - **Written from the web, read by eye:** the inputs are chosen (the web's own test cases plus the Unicode cases); the
     outputs are recorded by running the web's functions once (`VEYQ_WRITE_VECTORS=1`), and each recorded output is
     checked against its `rule` before it is committed. Every `capturedAt` is 12:00 UTC on a month's 15th, so every time
     zone reads the same day, month and year.
   - **Kept in step:** a change to a rule changes the vectors, and the web's and the core's tests go red together until
     both follow (`src/web/src/core/search/vectors.test.ts`, core's `SearchVectorsTest` on the JVM and `macosArm64`).
2. **What the port owns.** `core/…/search/`, platform-generic as the web's `core/search/`: the fold and the name order
   (decision 3), the documents, the query (chips, suggestions, predicates, ranks, the matcher with its narrowing and
   history, the bold part), the results (places, the order, the first ranked), the node documents, the index (decision
   6), people and settings (decision 9), the open request and the open Memory's contribution (decision 8), and the
   field's model (decision 7). It keeps the web's names and constants (`MIN_TAIL` 2, `PER_TYPE` 3, `MIN_MONTH` 3,
   `MIN_INSIDE` 2, `MIN_KEYWORD` 3, `MATCH_HISTORY` 64, a page of 10, revalidation every 20 s) so a reader moves between
   the two.
   - **No regular expression in the text rules.** JavaScript's `\s`, `\d` and `\p{L}\p{N}` are spelled as predicates
     named after them (`isJsSpace`: Zs, tab, line feed, vertical tab, form feed, carriage return, U+FEFF, U+2028,
     U+2029; `isAsciiDigit`; `isLetterOrNumber` by the Unicode categories L* and Nd, Nl, No), because the JVM's `\s` is
     ASCII and Kotlin's `trim` and `isDigit` read other sets. The vectors hold the cases where they differ.
   - **Off the UI thread, so no slices.** The web slices its build into 40 ms steps because it runs on the main thread;
     the core's runs on `Dispatchers.Default` and checks for cancellation per node and per medium, so a sign-out or a
     closed tenant stops it.
3. **Folding and the name order, one for every platform.**
   - **The fold is the web's:** NFD, drop every character of category Mn, Mc or Me, lowercase, ß → ss; and its map back
     to the name's own characters for the bold part. Core gains `nfd` beside `nfc` and `nfkd`. The participants
     picker's fold becomes this one, so the picker and the search read one rule, as on the web.
   - **The name order is the core's own comparator:** the folded names compared with runs of ASCII digits as numbers
     ("Trip 2" before "Trip 10", leading zeros aside), every other character by its UTF-16 unit, then the id — names
     equal folded are equal, as `sensitivity: 'base'` makes them on the web, so the id decides there too. It stands where the web's
     `Intl.Collator(locale, {numeric: true, sensitivity: 'base'})` stands: the third key after the rank and the place,
     so it orders only equal matches in one place. The vectors' order cases use names on which both agree.
4. **What each application contributes: `AppKinds.search`, as the web's registry.**
   - **Per kind** (`KindSearch`): the kind chip's word, its icon, optional facets — handed the node's meta as core's own
     node-meta opener opened it, never the key (a tighter door than the web's hook, which is handed the node's key and
     its parent's) — and the document kinds its application's space search makes (word, icon). The words are the web's
     labels: folder, bookmark, file, drop box, Pot, Memory, Chapter, photo, video; core adds *Person* and *Setting*.
   - **Per application** (`ApplicationSearch.scopeDocuments`): handed the tenant, the space, the last tag, the previous
     documents by id and the shell's opened nodes of the space, whose keys it reads in place. Memories ports `scopeMediaDocuments`: `listScopeMedia` by `If-None-Match`, 1000 per
     page, a medium kept unopened at an unchanged `metaRevision:blobId`, a blocked one and one that is no gallery
     medium (a GPX track, a music track) no document, its rows opened with `MemoryMedia`'s own openers.
   - **Memories' facets** port the web's `memorySearchFacets`, `yearsOfDate` and `monthsOfDate` over the core's
     `MemoryDate`, `MemoryLocation` and `Participants`, dates in the device's time zone, as the web reads local time.
   - **Depot and Expenses** contribute their kind words only, as on the web.
   - **The direction is a guard:** `scripts/validate_mobile_search_names_no_application.py` fails on a reference from
     `core/…/search/` to an application's package, the packages derived from `ApplicationId`'s entries. `AppKinds` is
     the one place that wires an application's part in.
5. **The key lifetime.**
   - **The index holds no key:** its documents are names, ids, places, years and user ids.
   - **The node keys are the shell's tree's.** The search opens no tree of its own and reads each node's key in place —
     never copied, never zeroed by the search, since the shell still holds it. ADR 0125 decision 2's "each tree's keys
     are zeroed at once" is the web's lifetime, which holds only the active space's tree; in the apps every space's
     tree is the shell's, held for the session and dropped with it.
   - **What the search unwraps itself is zeroed before its walk returns:** each medium's `K_blob` (opened to read the
     blob meta's name) and a drop-box uploader's key, in a `finally`.
   - **Sign-out, a lock, a tenant switch** drop the index: the node documents are a `fromTree` value, which
     `forgetTree` and `close` drop, and the media documents go with the search's model, which the tenant's close drops.
6. **The index: the shell's trees, the listings by tag, revalidated while the field is used.**
   - **Built on the first focus** of the field, not at sign-in: node documents from the trees of every searched space
     (decision 10), each node's facets read once per seal (a node whose `wrappedMeta` did not change keeps its facets),
     then each application's `scopeDocuments` per space.
   - **Revalidated on every later focus and every 20 s while the list is open:** each searched space's tree by its
     revision (a `304` keeps everything) and each application's listing by its tag. A space whose tree no longer reads
     loses its documents and is named *not searched*; one whose listing fails keeps its nodes and is named too.
   - **The active space and the open node** come from the shell: the active space is the space Home or the open object
     stands in, the open node the open object (not a folder level).
7. **The field's state is the core's: `SearchModel`.** One per tenant, built by the shell and dropped with the tenant.
   Its state `SearchShown`: the chips (word, label, icon kind); the suggestions under *Filter by*, each with its second
   line; one list of results under *Results*, the first 10, *Show {count} more* and the exact total; per result its
   icon kind, its name in parts (bold where matched), its second line (another space's name and path, this space's
   path, a person's `~username`, a settings section's word and, where a settings word is the reason, that word; a photo
   its Memory and the day it was taken); the status (spaces still loading after 300 ms, not searched, offline's line of
   decision 11, the empty state with the hint); and the announcement for a screen reader. Its actions: focus, text,
   choose a suggestion, remove a chip, remove the last chip (Backspace in an empty field), more, open a result, close. A
   keystroke cancels the previous answer's job (the latest wins); the matcher's memory clears at close and when the
   documents change.
8. **Opening a result, and the open Memory's photos.**
   - **A node** opens where it lives, through the shell: an object by the shell's open (its space becomes the active
     one), a folder on Home at that folder. **A person** opens their profile. **A settings section** opens the
     platform's settings page, scrolled to that section (decision 9).
   - **A photo or video another space's listing found** opens its Memory and leaves a one-shot request (the web's
     `openRequest.ts`): the Memory's gallery takes it once its listing holds the medium and opens the lightbox on it; a
     request its gallery cannot answer once it has read its listing is dropped, and a newer choice replaces it.
   - **The open Memory's photos** are the gallery's own documents (the web's `contribution.ts`): the open gallery
     contributes its listing's media at no fetch; they win over the same medium from the space's listing and open the
     lightbox in place.
9. **People and settings.**
   - **People:** one document per member of the tenant's roster, named as the directory names them, found by
     `~username` too, opened on the profile. Nothing is fetched for it.
   - **Settings sections are the apps' own** (ADR 0125 decision 9: exactly the sections the settings page offers): core
     names the sections (id, word, keywords) and which it offers now, over the gates its account settings already
     expose; each platform's settings page anchors its sections by these ids, and the search offers what the core
     offers — one list, one set of gates. The second line is the settings word (*Settings*): the apps' page has no rail
     headings. The web's sections the apps do not offer (profile, password, security, moderation, erase, the tenant's)
     arrive with those sections.
10. **What is searched: the visible spaces of the current tenant.** Every space of the current tenant the member has not
    hidden on this device — the apps' own rule, which the web does not have. One tenant at a time, as ADR 0125 decision
    3 (owner, 2026-11-18): a member's tenants are separate places, and a second tenant's results would need their own row line and a
    switch on opening. A space the core could not open is named *not searched*.
11. **Offline: what the store keeps is searched; the photos of every space are not.** Offline the trees are the store's,
    so the nodes, their names and their Memories' facets are searched as kept, and the roster is the kept members'.
    `listScopeMedia` is not a kept read: a whole space's listing would be the store's largest listing and needs its own
    sweep rule, an amendment to [ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
    that belongs with offline search over every space's photos (inbox #956). So offline the media documents this
    session built while online stay; a start offline has none but the open Memory's, and the status says *Photos are
    searched once you are online* (the owner's words, 2026-11-18, with *Search all spaces* as the field's hint and
    *Settings* as a settings result's second line).
12. **Where the field lives, per platform** ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
    decision 9: the platform's guideline):

    | Platform | The field | Chips | Lists | Keyboard |
    |---|---|---|---|---|
    | Android | the full-screen M3 `SearchBar` (Search the bar's last place) | `InputChip`s in a row under the input field | *Filter by* as `ListItem`s, a divider, *Results* as a header, results as `ListItem`s with the bold part, *Show more* a `TextButton` | — |
    | iOS, iPadOS | `.searchable`: the iPhone's search tab, the split view's toolbar field | the field's tokens (a chosen `.searchCompletion(token)` keeps the rest of the typed text, measured on the macOS build 2026-11-18, so no row-list fallback) | *Filter by* through `.searchSuggestions`; the results a `List` with a *Results* section | iPad with a keyboard: as macOS |
    | macOS | the toolbar's `.searchable` | tokens | as iOS | ⌘F focuses the field; Esc closes; Return takes the first entry |
    | Linux | the `gtk::SearchBar` + `SearchEntry` under the sidebar's title | a wrap box of chip buttons under the entry | while a query stands, the sidebar's place is a `gtk::ListBox`: *Filter by*, the suggestions, a separator, *Results*, rows of a title with the bold part and a dim subtitle, *Show more* | Ctrl+F, Esc closes, Enter takes the first entry, Backspace on an empty entry removes the last chip |
    | Windows | the `AutoSuggestBox` of the `NavigationView` | a row of chip buttons at the top of the content frame while a chip is set | the box's suggestion list: *Filter by*, the suggestions, a separator, *Results*, the rows with the bold part, *Show more* | Ctrl+E and Ctrl+F, Esc closes, Enter takes the first entry, Backspace on an empty box removes the last chip |

    - The web's highlight memory (a moved highlight stays on its entry while typing) is the web's combobox and is not
      ported: each platform's own list navigation applies, and Enter takes the first entry. The web's `/` and Ctrl+K are
      the browser's; the apps keep their platforms' find keys.
    - The announcement of decision 7 goes through each platform's own channel: TalkBack's live region, VoiceOver's
      announcement, GTK's `gtk_accessible_announce`, UI Automation's notification event.
    - **A calm focus** holds: focusing builds the index and shows no list; the list opens with the first character or a
      chip.
    - **As built, each platform's keys** (2026-11-18): **Android** — Enter hides the keyboard (the list is touched, not
      entered), a hardware keyboard's Backspace in an empty field removes the last chip, and the status lines (offline's
      among them) stand above the lists. **Linux** — Esc first clears the text, a second Esc closes; Down moves from the
      entry into the list. **Windows** — Enter takes the highlighted entry, else the first chip offered, else the first
      result; the settings sections have no appearance section, since the Windows app has no appearance setting. **Linux's**
      `AdwWrapBox` exists from libadwaita 1.7 (the run image carries 1.9.4, the crate built with `v1_9`), and
      `AdwSidebarItem` takes no markup, so the results are a `gtk::ListBox` whose rows carry the bold part; GTK's
      `gtk_accessible_announce` exists from 4.14 (each read from the SDK's `.gir`, 2026-11-18).
13. **The bridge (Linux, Windows)** carries the model as calls: `Call.SearchOpen` (`Search.Open`) →
    `Answer.SearchOpened(id)`; `Watched.Search(id)` → `Update.Search(id, SearchShown?)`; `Search.Focus|Text|Choose|
    RemoveChip|RemoveLastChip|More|OpenResult|Close`, each with the search's id (a result's open is `Search.OpenResult`:
    `Search.Open` opens the model). `SearchShown` is the core's own serializable state and crosses as names, parts, words
    and ids — no key, no seal. The words the search matches are the host's (`Words.Say`, asked once per word). The model
    is disposed with the tenant. The name-only search (`Call.SearchQuery`, `Answer.Hits`, `ShellWords.search`) is removed once its last
    caller has moved (removed 2026-11-18 with Windows, its last caller).
    - **Added while building** (2026-11-18): `SearchShown.openSettingsCount`, the number of settings results opened — the
      same section chosen twice in a row left the state equal to the last one, which a state flow never delivers again,
      so a platform opens the page whenever the count moves and `openSettings` keeps its meaning; a photo of another
      Memory reaches a bridged host as `GalleryShown.searchOpened`, answered `Gallery.SearchOpenedSeen` once its lightbox
      shows; a chip's remove button is named by the word `search_remove_chip` (the web's `ui.removeTag`); and
      `ShellModel.searchModel` takes an optional `trace` (counts and milliseconds, never a name) through which a platform
      times the index's walks (Android logs it as `veyq-search`).

## Consequences

- **The server learns nothing new.** The apps' search sends what the web's sends: the trees the shell already reads and
  one `listScopeMedia` per space per focus, by its tag. No query, no index, no token.
- **The collation residual:** at an equal rank and place, names led by punctuation or a letter a language collates
  apart (Swedish å) may order differently in the apps than on the web.
- **The bounds** (measured in the core over synthetic documents shaped as the volume harness seeds them — 7,000 nodes
  over three spaces, 10,000 photos in one Memory, 445 members; `SearchVolumeMeasure`, `VEYQ_MEASURE=1`, on the JVM and
  on `macosArm64`):
  - **A keystroke** ("K", "Ke", "Kev", a year chip, Backspace — matched, ranked, cut to 10, put into a `SearchShown`):
    bound 100 ms. The UI thread runs no matching, so ADR 0125's main-thread bounds hold by construction; what remains is
    latency.
  - **The build** (the documents of every space from opened trees and listing pages, the network stubbed): bound 2.6 s,
    the ADR 0125 unthrottled reading that included the network.
  - **The index's memory:** recorded, not gated (ADR 0125's 9.6 MB is JS heap; the JVM's is no like-for-like number).
  - **Measured** (2026-11-18, `VEYQ_MEASURE=1 ./gradlew :core:jvmTest --tests '*SearchVolumeMeasure*' --rerun`, and
    `:core:macosArm64Test` the same, from `src/mobile/`; this Mac, a 1-minute load of 3.8–15.7, not a quiet machine; real
    seals: 700 Memories' metas and 10,000 media's listed tiers, blob keys and blob metas opened by the core's own openers):
    - **B2:** JVM 371 and 525 ms; `macosArm64` 1,414 and 1,419 ms — within 2.6 s.
    - **B1:** JVM "K" 18 and 87 ms (the first keystroke, the JIT cold), "Ke" 12 and 34, "Kev" 3, a year typed 1–2, the year
      chip chosen 19 and 28, Backspace 6 and 13; `macosArm64` 0–31 ms — within 100 ms.
    - **B3:** 10 MB of used JVM heap for the index, recorded.
  - **The first focus live** (2026-11-18, the Android emulator `veyq_pixel` against the dev instance, timed from the
    model's `trace` in logcat; a host load of 6–19, not a quiet machine): the process's first focus 1,453 ms in all, its
    node documents 880 ms; a later process's first focus 388 and 325 ms; a revalidation 133–634 ms; offline 24 ms.
- **The passes** (2026-11-18, each by an agent against the dev instance; what each could not prove is the owner's,
  [native-apps](../crosscutting/native-apps.md) § Open):
  - **Android** (the emulator, read through `uiautomator` and TalkBack's verbose log): a Memory's name typed from another
    space, a year chip, a person chip and the person's result, *In:*, *Kind: Photo*, a photo of another Memory opened in
    its lightbox, a settings section opened scrolled to it, *Show more*, airplane mode's line, TalkBack speaking the count.
  - **macOS** (the build, read through its accessibility tree): the same steps, the chips as the field's tokens. Not
    proven: ⌘F, Return and Esc with the app frontmost; a settings result chosen twice is proven by the core's test only.
  - **Linux** (Broadway in the Fedora container): the same steps with Ctrl+F, Enter, Esc and Backspace in an empty entry;
    the announcement, which Broadway's test accessibility context cannot carry, proven on X11 through `dbus-monitor`
    (an `Object.Announcement` signal).
  - **Windows** (the ARM64 VM through UI Automation): the same steps with Ctrl+E; the announcement read as UIA's
    notification event (`ActionCompleted`, `MostRecent`, activity `veyq-search`).
  - **The owner's:** the iPhone and the iPad on a device (VoiceOver's count, the tokens), Orca in a real GNOME session,
    and ⌘F, Return and Esc on the Mac frontmost.
- **Rejected alternatives:**
  - **The core's own tests mirroring the web's by hand:** two suites drift silently, as the participants picker's NFKD
    fold had already drifted from the web's NFD one.
  - **One implementation for both** (Kotlin/JS compiled into the web, or the web's TypeScript run in an engine inside
    the apps): a rewrite of a built and measured web module, or a JavaScript engine in four apps for one feature.
  - **Each platform's collator** (`java.text.Collator` has no numeric mode, Apple's `localizedStandardCompare` has, the
    bridged platforms have none): three orders where ADR 0125 wants one.
  - **The search walking Memories' meta itself**, as the participants index does: core would name an application, and
    that index's 500-per-scope cap is no rule of the search's.
  - **A persistent index** (ADR 0125 decision 2): nothing decrypted on disk (ADR 0140 decision 1).
  - **Searching every signed-in tenant at once:** ADR 0125's cross-origin reason does not hold in the apps, which hold
    every tenant; but a tenant is a separate place, and its results would need a line and a switch of their own. Revisited
    when a member asks.
  - **WinUI Community Toolkit's `TokenizingTextBox`:** a new dependency for one control, and it does not sit in the
    `NavigationView`'s box slot.
  - **Results in the Linux content pane:** GNOME's own apps (Settings) list a sidebar search's results in the sidebar,
    where the apps' search already shows them.

Explained for users on the docs site (§ In the app): [Search all your spaces](../../site/src/content/docs/spaces/search.mdx).
