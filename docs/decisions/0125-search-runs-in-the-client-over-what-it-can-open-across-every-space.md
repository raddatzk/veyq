# ADR 0125 — Search runs in the client over what it can open, across every space
- Status: Accepted
- Built: partly (not built: decision 8)
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
   - **(a) The photos of every space.** A paged, scope-wide listing of the listed tier for members, with each node's
     items revision carried in the tree so the client knows which listings moved. It is not built.
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
  - The first stage adds no endpoint. The server learns that a member opened each of their spaces' trees, as it does
    when they enter each space.
  - Stage 8(a) adds the fact that a client read every listing of a space: ids and times, the kind of fact opening each
    Memory reveals today.
- **The first stage does not find a photo by its own place or date unless it lies in the open Memory.** "Rome" finds the
  Memory whose place is Rome, not a photo taken in Rome inside a Memory placed elsewhere. Stage 8(a) closes this. A
  result list must not imply it searched what it did not.
- **The cost of a first search across many spaces is one tree fetch per space.** That is the size the volume pre-check
  measured for one space (0.84 s for the root view at 5,000 nodes on a desktop). It is paid on focus, not at sign-in.
- **A removed member's search loses that space** when its keys go, and keeps nothing they could not read before.
- **Rejected alternatives:**
  - A persistent on-device index (decision 2).
  - Server-side blinded tokens (decision 1).
  - A single ranked list with an invisible context weight (decision 5).
  - Searching only the active space: the owner wants every space.
