# ADR 0125 — Search runs in the client over what it can open, across every space
- Status: Accepted
- Built: partly (decision 8a built; 8b and 8c not built)
- Date: 2026-11-08
- Model: [uc.platform.search](../features/platform/use-cases/uc.platform.search.md)

## Context

The server holds no plaintext to search. Every name is sealed under its parent's node key, and a Memory's date, place and
participants are sealed in its `wrappedMeta`. A photo's capture time and place are sealed in its listed tier, and its
camera and XMP title are sealed in its EXIF tier ([security model](../crosscutting/security-model.md) § inventory). So a
search runs either in the client, or on blinded tokens the server matches. The invariant permits both: "blinded tokens"
are among what the server may see.

What the web client already holds decides most of the cost:
- Entering a space fetches and opens **every node of that space**: the names, and each Memory's `wrappedMeta` on
  demand. `spaceMap` and `participantIndex` already read the meta scope-wide.
- The photos of a Memory are fetched only when it opens, then opened in a worker
  ([ADR 0105](0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md)).
- Freshness exists already. The tree revalidates on one per-space revision and returns whole when it moved
  ([ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)). A node's items revalidate on their own revision,
  with a `since` delta ([ADR 0033](0033-live-media-updates-revision-polling.md)).

The load model is a household. The volume pre-check's largest tree is 5,000 nodes, its largest Chapter 10,000 photos
(`changes/cr-volume-measurement-cax.md`).

## Decision

1. **Search runs in the client, over content the client can open. The server gets no index and no search tokens.**
   Blinded per-word tokens were rejected:
   - Deterministic tokens over natural language fall to frequency and co-occurrence analysis.
   - The server would see every query as a token.
   - Prefix and fuzzy matching are impossible.
   - A removed member keeps the test-for-existence unless every token is rewritten at each rotation. That is the trade
     [ADR 0024](0024-scope-key-rotation.md) decision 3 accepts for dedup, where it is cheap; here it buys nothing.

   At household scale the client can hold the plaintext, so a leak buys no capability the client lacks.
2. **The index is a derived cache, never a second source of truth.**
   - **Keying:** each entry is keyed by its space, its object's id and that object's revision. It is rebuilt from the
     decrypted object when the revision moves.
   - **Freshness:** comes from the revisions the server already serves. A renamed Memory reaches the index through the
     tree poll the client already runs; nothing is synced back.
   - **Lifetime:** the cache lives in memory only, and never holds a key. Each space's tree is opened, turned into
     documents, and its keys are zeroed at once. It revalidates by its ETag, and a `304` keeps the documents. A tree
     that moved is opened again. A space whose tree can no longer be fetched or opened loses its documents. Sign-out
     drops everything.
   - **No persistence**, neither on the device nor as a sealed per-user blob on the server, until a rebuild is
     *measured* too slow on a real household. Nothing decrypted is on disk today, and
     [ADR 0047](0047-inline-video-playback-range-streaming.md) holds that line. A server-side snapshot would show the
     operator its size and update times.
3. **Search spans every space the member belongs to in the tenant they are in.** That covers their personal
   space, the tenant's space and every group. Each space's tree is opened the first time the search field gains
   focus, not at sign-in and not by switching the active space. From then on, each tree revalidates on its own
   revision while the field stays in use.
   - **Why only this tenant:** a tenant is resolved from the host the client is served on, and entering another tenant
     is a navigation to its domain. A member of several tenants searches each one on its own domain. Searching across
     tenants would need a cross-origin read of another tenant's spaces and directory, which no endpoint offers. It is
     left until a member of several tenants asks for it.
4. **One search field. Metadata is expressed as chips.**
   - **The chips:** space, year (a Memory matches when its date range overlaps the year, a photo when it was
     captured in it), place, person, kind. Each chip is a typed predicate over a document's
     fields, not text.
   - **Suggestions:** typing suggests chips. A year offers the year, a member's name the person, a known place the
     place.
   - **Free text:** whatever is not turned into a chip stays text, matched against names, case- and
     diacritic-insensitive.
5. **Results are grouped by where the member stands: the open Memory, then this space, then other spaces.** Ranking
   happens only within a group, and an empty group is not shown.
   - **Why groups:** a hidden context weight would order the same query differently depending on where the member
     stands, with nothing on screen saying why. A group's heading says it. (Owner, 2026-11-08: the open Memory's
     content comes first.)
6. **Core owns the field, the search model, the chips' grammar, the grouping and the names of every node.** An
   application contributes the searchable documents and facets of its own kinds through the build-time registry
   ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md): core never imports an application).
   - **Memories contributes:**
     - for a Memory: its date range, its place and its participants;
     - for the open Memory: the photos and videos of its already-opened listing, with their capture time and place, at
       no extra fetch.
7. **A share guest has no search.** A right removes a function, so this is consistent with the share view being the
   member view. It also leaves the hosting reading of
   [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) untouched, which
   holds while a share has "no directory, no search".
8. **Later stages, decided here so the first stage is shaped for them:**
   (decision 9 follows them; it was decided after the first stage was built)
   - **(a) The photos of every space** (owner, 2026-11-09: build it).
     - **The listing:** a member's paged listing of every live medium of a scope, ordered by id. It covers the media of
       live nodes only and carries what the client needs to open a medium's capture time, place and name: the listed
       tier, the original's sealed blob key and blob meta, and the uploader origin.
     - **Freshness:** the listing has an ETag of its own, derived from the items revisions of the scope's live nodes
       and the tree revision, as the share listing derives its own. A `304` keeps the space's photo documents.
     - **Why not the tree:** carrying the items revisions in the tree, as first sketched, was rejected. An items bump
       does not move the tree revision, and making it move would put the scope lock on every item write, which item
       writers deliberately do not take. So a tree `304` would hide a photo change.
     - **Opening:** the client opens the listing while the walk of decision 3 still holds the space's node keys,
       through an application's per-space search hook. It zeroes those keys with the rest. The open Memory's own
       documents (decision 6) win over the same medium's document from the listing.
     - **Built** (`listScopeMedia`; `ApplicationRegistrar.search`, `searchIndex.ts`, Memories' `search.ts`):
       - The listing restricts to live nodes in its statement (an `exists` on the node's row), not by a list of the
         scope's node ids, and narrows a row to what the search opens (no derivative, no bucket, no time).
       - The tag is derived, not a digest of the rows: a member is told the item revisions already, so an idle read costs
         the tree revision, one join over the live nodes' item revisions and the blocks, and no media query.
       - The hook reads the listing by its tag on every walk, the active space's included, and asks for the node keys only
         when a medium must be opened: on a tree `304` the scope key is opened only then. A medium at an unchanged
         revision is kept unopened, and a blocked one is no document.
       - A photo outside the open Memory opens its Memory by the node's fragment and leaves an in-memory request the
         Memory's page takes once its gallery holds the medium. It is no address: a reload or a copied link carries no
         medium, since a medium's own address does not fit ADR 0015's grammar (an open question).
   - **(b) Full text over the journal.** This is designed inside the journal's refinement (inbox #18): whether a client
     reads one compacted snapshot per journal or replays updates decides its cost. It then plugs in as one more
     document kind.
   - **(c) Semantic search.** Deferred. Embeddings in the browser need WebAssembly or WebGPU, the CSP grants no
     `'wasm-unsafe-eval'` ([CSP](../crosscutting/cc.security.csp.md)), and a model is tens of MB. Its natural home is
     the native apps.
9. **People and settings are results too, each in a group of its own after the spaces' groups: People, then
   Settings.** (Owner, 2026-11-09.)
   - **People:**
     - A person result is a member of this tenant, named as the directory names them: the real name under the
       tenant key, else the username.
     - Choosing one opens their profile page ([ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)).
     - A person stays a chip as well. Typing "Oma" offers her as a chip (filtering Memories she takes part in) and
       lists her as a person result. The two are different answers to the same word.
     - The roster the client already reads is the source. Nothing new is fetched or sent.
   - **Settings:**
     - A settings result is a section of the settings page, matched by its label in the UI's language.
     - The search offers exactly the sections the settings rail offers this member at this moment. Owner-only,
       moderator-only and capability-gated sections stay hidden where the rail hides them, so the rail and the search
       read one list with one set of gates.
     - Choosing one opens that section.
     - The entries are the client's own words, so they tell the server nothing.
   - **Why groups of their own:** a person or a settings section lives in no space, so the spaces' grouping
     (decision 5) has no place for them. Their fixed position after content keeps a query's content results where
     they were.

## Consequences

- **What the server learns:**
  - The server learns that a member opened each of their spaces' trees, as it does when they enter each space.
  - Stage 8(a) adds one read, `listScopeMedia`: that a client read every listing of a space - ids, sizes and times, the
    kind of fact opening each Memory reveals today - and, while nothing moved, one `304` per space per focus. Nothing
    about a query.
- **The cost of a first search across many spaces is one tree fetch and one media listing per space, opened in
  slices.** It is paid on focus, not at sign-in. Measured (cr-search-photos-of-every-space, 2026-11-09) on this Mac in
  desktop Chrome, on the volume harness's own instance with 7,000 nodes over three spaces (one of 5,000), 10,000 photos in
  one Memory and 445 members, at a 1-minute load of 5.9–8.4:
  - **The build:** 1.8–2.6 s until every space and its photos are indexed, 3.3–5.0 s at a 4× CPU throttle. Its longest
    main-thread task is 53–79 ms, and 84–119 ms at 4× (bound: 200 ms). It runs in slices (`searchIndex.ts`, at most
    40 ms at a time, the space on screen and an application's objects included); before it did, one task per space held
    the main thread 0.8–2.6 s at 4× (cr-search-at-volume, without photos).
  - **Typing** "K", "Ke", "Kev", a chip and Backspace: no long task unthrottled; the longest at 4× is 57–99 ms (bound:
    100 ms), the first keystroke's, most of it Vue's render of the list. Before a document that does not hold the text
    was answered by one search and the text's words were split once per query (`query.ts`), the photos took it to
    70–113 ms - over the bound in two of twelve readings.
  - **A second focus with nothing changed** reads each other space's tree and every space's media listing as one `304`
    each, and the index opens nothing.
  - **The index's memory:** 7.8–8.4 MB of JS heap (3.7–4.2 MB without the photos).
  - The command: `scripts/measure_volume.py --instance I search --runs 2 --throttles 1,4 --active tenant|group`, after
    `seed tree --target 5000`, `seed spread --target 1000`, `seed members --target 445` and `seed photos --target 10000`.
    The readings and the judging rule are in the CR's retrospective. The harness is temporary, `cr-volume-measurement-cax`
    deletes it.
- **A removed member's search loses that space** when its keys go, and keeps nothing they could not read before.
- **Rejected alternatives:**
  - A persistent on-device index (decision 2).
  - Server-side blinded tokens (decision 1).
  - A single ranked list with an invisible context weight (decision 5).
  - Searching only the active space: the owner wants every space.
