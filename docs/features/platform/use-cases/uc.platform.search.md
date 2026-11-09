# UC: Search every space for a node, a Memory or a photo of the open Memory

- **ID:** uc.platform.search
- **Status:** built (web; browser pass 2026-11-09), the first stage of [ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md): names and Memory
  meta across every space, plus the media of the open Memory, and (decision 9) the tenant's members and the settings
  sections as results of their own. Decision 8 (the photos of every space, journal full text, semantic search) is not
  built.
- **Primary actor:** A member whose keys are unlocked.
- **Goal:** Find a folder, a Memory, a Chapter, a file, a drop box, a Pot or a photo by its name, date, place, people or
  kind, in any space the member belongs to in the tenant they are in - and a member of the tenant or a settings section -
  without the server learning what was searched.
- **Preconditions:**
  - Signed in and unlocked, in the member view (not a share).
  - The member belongs to at least one space.
- **Postconditions (success):**
  - The member opens a result where it lives.
  - The server has seen only the tree reads it would see when the member enters each space.

## Main flow

1. **Open the search.** The member focuses the search field in the app shell. A keyboard shortcut does the same; on
   a phone, the field is reached from the top bar.
2. **Load every space.** On the first focus of the session, the client opens the tree of every space the member
   belongs to in this tenant (personal, the tenant's, every group) that it does not hold yet. Each tree is fetched and opened with that space's key, as entering the space
   would, but **the active space does not change**. The keys are zeroed once its documents are built; the next focus
   revalidates each tree by its ETag. The active space and the open Memory are indexed first. A space
   still loading is named as loading in the results.
3. **Build the documents.**
   - Core turns every node into a document: its space, its kind, its id, its revision and its name.
   - Each application adds the fields of its own kinds through the registry. Memories adds a Memory's date range, its
     place name and its participants' names. For the open Memory it adds one document per photo and video of its
     already-opened listing, with capture time and place.
   - Core adds one document per member of this tenant (the directory's name, and their `~username` as a second name),
     from the roster the client already read, and one per settings section the settings rail offers this member now:
     the rail and the search read one list with one set of gates (`features/settings/sections.ts`), and while a gate's
     answer is out no section is offered (ADR 0125 decision 9).
4. **Type the query.** The member types. Each token that reads as a known facet is offered as a chip: a year, a month with its year ("Mai 2024",
   "May 2024" - a Memory matches when its range overlaps the month, a photo when captured in it), a member's name (person), a place a document carries, a space's name, a kind's word. Choosing a suggestion
   turns the token into a chip; the rest stays text. The chip suggestions stand under a heading of their own
   (*Filter by*), apart from the results, because a person suggestion and a person result can carry the same name
   while one filters and the other opens. The first entry of the list is highlighted, a suggestion or a result, and
   **Enter** takes the highlighted one. Once the member moves the highlight (arrow keys or pointer), it stays on that
   entry while they type on, as long as the entry is still in the list. When it drops out, the first entry of its
   group is highlighted, or the first entry of the list when that group is gone. The moved-to entry is still
   remembered, and when it is listed again (e.g. after Backspace) it is highlighted again. A new move replaces it;
   choosing an entry or closing the list forgets it (owner, 2026-11-09).
5. **Match.**
   - A document matches when it satisfies every chip, and the remaining text matches its name or its place,
     ignoring case and diacritics. A settings section also matches by a few words of its own ("E-Mail" finds the
     mail section, "Kennwort" the password), kept with the section in the one list the rail reads.
   - Within a group, an exact or prefix match of the name ranks above a match inside a word, and a name match above a
     place match.
6. **Show the results in groups,** each ranked within itself: the open node's - headed by its name, *In “Rom 2024”*,
   holding what its page contributed and the nodes below it (when a node's page is open, not a folder) -, **In this
   space**, **Other spaces** (each result naming its space and its path), then **People** (each with their picture or
   initial and `~username`) and **Settings** (each with its heading on the rail). The part of a name the text matched is shown in bold. An empty group is not shown. A chip is a
   filter over content: while one is set, People and Settings are not shown. A member's name offers them as a person
   chip and lists them as a person - the chip filters, the person opens their profile.
7. **Open a result.** The member chooses one. A node opens where it lives, switching the active space when it lies in
   another. A photo opens in the open Memory's lightbox. A person opens their profile page. A settings
   section opens on the settings page, the active space kept.

## Alternate / exception flows

- **A1 — a tree changes while the search is open:** the space's revision moved (the poll the client already runs). The
  tree is re-read, its documents are replaced, and the results update without losing the query.
- **A2 — no result:** the field says nothing was found and names what was searched. The first stage searches names,
  Memory dates, places and people across every space, but photos only in the open Memory. A photo's own place or date
  outside the open Memory is not searched yet, and the empty state must not imply it was.
- **E1 — a space cannot be opened** (its key is not on this device yet, a fetch fails, a rotation is in flight): its
  results are left out, and the result list names that space as not searched. The other spaces are unaffected.
- **E2 — the member is removed from a space, or the space is rotated:** its tree no longer fetches or opens, or it moved.
  Its documents are dropped, or rebuilt from the re-opened tree. Sign-out drops every document.
- **E3 — a share view:** there is no search field ([ADR 0125](../../../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)
  decision 7).

## Quality notes

- **Operator-blind, also about the query.** Nothing about the query leaves the device. No endpoint is added, and the
  tree reads of step 2 are those of entering each space.
- **In memory only.** No index, query or result is written to IndexedDB, localStorage, Cache Storage or the server.
  No key is held for search: each tree's keys are zeroed once its documents are built.
- **One source of truth.** A document is derived from an object and its revision. The index never holds a value the
  tree or the listing does not.
- **Accessible.** The field is a combobox a screen reader can drive: the number of results is announced politely as
  it changes, each group is named by its heading, and adding or removing a chip is announced.
- **Core never imports an application.** What an application contributes reaches core through the registry
  ([ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## User impact

You can find any folder, Memory, Chapter or file across all your spaces by name, date, place or the people in it,
without the server learning what you searched for.

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
