# UC: Search every space for a node, a Memory or a photo

- **ID:** uc.platform.search
- **Status:** built (web and backend; browser passes 2026-11-09), [ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)'s first
  stage and decision 8a: names and Memory meta across every space, every photo and video of every space, and (decision
  9) the tenant's members and the settings sections as results of their own. Decisions 8b and 8c (journal full text,
  semantic search) are not built.
- **Primary actor:** A member whose keys are unlocked.
- **Goal:** Find a folder, a Memory, a Chapter, a file, a drop box, a Pot or a photo by its name, date, place, people or
  kind, in any space the member belongs to in the tenant they are in - and a member of the tenant or a settings section -
  without the server learning what was searched.
- **Preconditions:**
  - Signed in and unlocked, in the member view (not a share).
  - The member belongs to at least one space.
- **Postconditions (success):**
  - The member opens a result where it lives.
  - The server has seen only the tree reads it would see when the member enters each space, and each space's media
    listing read (see Quality notes).

## Main flow

1. **Open the search.** The member focuses the search field in the app shell. A keyboard shortcut does the same; on
   a phone, the field is reached from the top bar. Focusing opens nothing: the list opens with the first typed
   character - or at once when a chip is set, since the field then already asks something (owner, 2026-11-09), and the field keeps its focus colour while the pointer rests on it (owner, 2026-11-09: a click into
   the field showed a panel and flickering states before anything was typed).
2. **Load every space.** On the first focus of the session, the client opens the tree of every space the member
   belongs to in this tenant (personal, the tenant's, every group) that it does not hold yet. Each tree is fetched and opened with that space's key, as entering the space
   would, but **the active space does not change**. Then each space's media listing is read (`listScopeMedia`: every
   live medium on a live node of the space, in pages) and opened under its nodes' keys while they are held. The keys are
   zeroed once its documents are built; the next focus revalidates each tree and each listing by its ETag - a photo's
   change moves no tree revision, so the listing is asked even when the tree answers `304`. The active space and the
   open Memory are indexed first. A space still loading is named as loading in the results, but only once the member has typed and the loading has
   lasted longer than a moment (about 300 ms); shorter loads show no intermediate state.
3. **Build the documents.**
   - Core turns every node into a document: its space, its kind, its id, its revision and its name.
   - Each application adds the fields of its own kinds through the registry. Memories adds a Memory's date range, its
     place name and its participants' names.
   - Each application adds the objects of its own inside a space's nodes through the registry. Memories adds one
     document per photo and video of every Memory and chapter, with its original's name, its capture time and its place,
     from the space's media listing; a blocked medium is no document. For the open Memory the same documents come from
     its already-opened listing and replace the listing's.
   - Core adds one document per member of this tenant (the directory's name, and their `~username` as a second name),
     from the roster the client already read, and one per settings section the settings rail offers this member now:
     the rail and the search read one list with one set of gates (`features/settings/sections.ts`), and while a gate's
     answer is out no section is offered (ADR 0125 decision 9).
4. **Type the query.** The member types. Each token that reads as a known facet is offered as a chip: a year, a month with its year ("Mai 2024",
   "May 2024" - a Memory matches when its range overlaps the month, a photo when captured in it), a member's name (person), a place a document carries, a space's name, a kind's word -
   every kind has one, core's *Person* and *Setting* included -, and the name of a node that holds something (a folder, a
   Memory, a Chapter) as *In: <name>*, which narrows to what lies below that node in its space (a photo by its Memory).
   Choosing a suggestion turns the token into a chip; the rest stays text. A chip is a predicate over every document: a
   person or a settings section satisfies it only where it carries what the chip asks (a year chip lists no person, *Kind:
   Person* only people, and *Person: Kevin* means content Kevin takes part in, not his profile). The chip suggestions stand under a heading of their own
   (*Filter by*), apart from the results, because a person suggestion and a person result can carry the same name
   while one filters and the other opens. The first entry of the list is highlighted, a suggestion or a result, and
   **Enter** takes the highlighted one. Once the member moves the highlight (arrow keys or pointer), it stays on that
   entry while they type on, as long as the entry is still in the list. When it drops out, the first entry of its
   area - the suggestions or the results - is highlighted, or the first entry of the list when that area is empty. The moved-to entry is still
   remembered, and when it is listed again (e.g. after Backspace) it is highlighted again. A new move replaces it;
   choosing an entry or closing the list forgets it (owner, 2026-11-09).
5. **Match.**
   - A document matches when it satisfies every chip, and the remaining text matches its name or its place,
     ignoring case and diacritics. A settings section also matches by a few words of its own ("E-Mail" finds the
     mail section, "Kennwort" the password), kept with the section in the one list the rail reads; such a word matches
     from a word's start only and from the third typed character on, and where it is the reason a section is listed,
     the row shows it on its second line with the matched part bold - a result never stands there for a reason the
     member cannot see (owner, 2026-11-09: "Passwort ändern" was listed for "Ke" through its hidden word "Kennwort").
   - A single typed character matches word starts only (a name's, a place's); a match inside a word counts from the
     second character on, so the list for "K" holds no "Reiselink" and typing on only narrows it.
   - An exact match of the name ranks first, then a prefix, then a word's start, then a match inside a word, then a
     match of a place or of a settings section's word.
6. **Show the results as one list** after the suggestions, under the heading *Results* (de *Treffer*), always shown,
   with a line between it and the suggestions (ADR 0125 decision 5). It is
   ordered by the match (step 5), then by where the result lies - below the open node (what its page contributed and
   the nodes below it, while a node's page is open, not a folder), then the active space, then the other spaces, then
   people and settings -, then by the name's collation: a strong match elsewhere comes before a weak one here. Each row
   says where it lies: its space and its path - the space on screen too, so a node at a space's top level still names
   it (owner, 2026-11-09) - (a photo its Memory's path and its day), a person their picture or initial and `~username`, a settings section its heading on the rail. The part of
   a name the text matched is shown in bold. The first 10 results are shown; "show more" adds 10, and the count is
   exact. A member's name offers them as a person chip and lists them as a person - the chip filters, the person opens
   their profile.
7. **Open a result.** The member chooses one. A node opens where it lives, switching the active space when it lies in
   another. A photo of the open Memory opens in its lightbox; a photo of another Memory opens that Memory (in its space)
   with the photo in the lightbox. A person opens their profile page. A settings
   section opens on the settings page, the active space kept.

## Alternate / exception flows

- **A1 — a tree changes while the search is open:** the space's revision moved (the poll the client already runs). The
  tree is re-read, its documents are replaced, and the results update without losing the query.
- **A2 — no result:** the field says nothing was found and names what was searched: names, dates, places and people
  in every space - a photo's own place and date included - and members and settings.
- **A3 — two nodes of one name:** the text matches the names of two nodes that hold something - *Reisen* in the
  tenant's space and *Reisen* in a group, or two in one space. Each is its own *In:* suggestion, with where it lives as
  its second line (its space and the path above it), and each chip narrows to its own node's subtree: the chip names the
  node by its id, never its name.
- **E1 — a space cannot be opened** (its key is not on this device yet, a fetch fails, a rotation is in flight): its
  results are left out, and the result list names that space as not searched. The other spaces are unaffected.
- **E2 — the member is removed from a space, or the space is rotated:** its tree no longer fetches or opens, or it moved.
  Its documents are dropped, or rebuilt from the re-opened tree. Sign-out drops every document.
- **E3 — a share view:** there is no search field ([ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)
  decision 7).

## Quality notes

- **Operator-blind, also about the query.** Nothing about the query leaves the device. The tree reads of step 2 are
  those of entering each space. The one read the search adds, a space's media listing, tells the server that a member
  read every listing of that space - ids, sizes, times, the fact opening each Memory reveals today - and nothing about a
  query ([ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)
  § Consequences).
- **In memory only.** No index, query or result is written to IndexedDB, localStorage, Cache Storage or the server.
  No key is held for search: each tree's keys are zeroed once its documents are built, and so is every node key a
  space's listing was opened under.
- **The build never blocks the page.** A space's tree is opened and indexed in slices of at most 40 ms, so a keystroke
  or a paint gets in between, even at 5,000 nodes on a slow CPU ([ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md) § Consequences has the measured numbers).
- **One source of truth.** A document is derived from an object and its revision. The index never holds a value the
  tree or the listing does not.
- **Accessible.** The field is a combobox a screen reader can drive: the number of results is announced politely as
  it changes, the suggestions are named by their heading and the results by their own name, and adding or removing a
  chip is announced.
- **Core never imports an application.** What an application contributes reaches core through the registry
  ([ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## User impact

You can find any folder, Memory, Chapter, file or photo across all your spaces by name, date, place or the people in
it, without the server learning what you searched for.

## Related

- Feature: [Platform](../_index.md)
- Decisions: [ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md),
  [ADR 0081](../../../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) (the tree revision),
  [ADR 0105](../../../decisions/0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)
  (the opened listing)
- Use-cases: [uc.memory.date](../../memory/use-cases/uc.memory.date.md),
  [uc.memory.place](../../memory/use-cases/uc.memory.place.md),
  [uc.memory.participants](../../memory/use-cases/uc.memory.participants.md) (the fields Memories contributes)
- End-user view (docs site): [Search all your spaces](../../../../site/src/content/docs/spaces/search.mdx)
