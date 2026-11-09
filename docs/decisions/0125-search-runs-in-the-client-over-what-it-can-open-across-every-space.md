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
     captured in it), month, place, person, kind, and *In:* a node. Each chip is a typed predicate over a document's
     fields, not text, and a predicate over **every** document - a person and a settings section included (decision 9):
     a document that lacks the field a chip asks about does not satisfy it. A year chip leaves out every person, since a
     person has no year; *Person: Kevin* means "content Kevin takes part in", and his profile does not satisfy it.
   - **Every kind has a word** (owner, 2026-11-09: "the same for nearly everything"): the kinds the applications name, and
     core's two, *Person* and *Setting* (de *Person*, *Einstellung*). *Kind: Person* lists only people.
   - **In: a node.** A node that holds something - a folder, a Memory, a Chapter - is offered as *In: <name>* when the
     typed text starts a word of its name, beside the node itself as a result. Its predicate is "lies below that node":
     a node below it, or an object of a node at or below it (a photo by its Memory). It is matched by the node's id,
     never its name, and it scopes to the node's space. Two nodes of one name are two suggestions, each with where it
     lives as its second line.
   - **Suggestions:** typing suggests chips. A year offers the year, a member's name the person, a known place the
     place, a kind's word the kind, a node's name *In:* that node.
   - **Free text:** whatever is not turned into a chip stays text, matched against names, case- and
     diacritic-insensitive.
5. **The list has two areas: the chip suggestions (*Filter by*), then ONE list of results** (owner, 2026-11-09: "keep it
   simple - suggestions and results as two separate areas, suggestions first; where a result lies should set the order,
   not a group"). The results stand under one heading of their own, *Results* (de *Treffer*), drawn always, with a line
   between the two areas (owner, 2026-11-09: a person suggestion above the results read as a result); there is no other
   heading.
   - **The order:** match quality first - an exact name, then a prefix, then a word's start, then inside a word, then a
     place or a settings section's word -, then where the result lies - the open node's subtree, then the active space,
     then the other spaces, then people and settings -, then the name's collation. A strong match elsewhere comes before
     a weak one here; where the member stands breaks ties and never hides a result.
   - **Each row says where its result lies**: another space's result its space and path, this space's its path, a person
     their `~username`, a settings section its heading on the rail. So the order needs no heading per place to be read.
   - **Paging:** the first 10 results are drawn, and "show more" adds 10; the count is exact.
   - **Why one list, and why the groups went:** a member read five headed groups (the open node, this space, other
     spaces, people, settings) for what one ordered list with a place on every row says, and a strong match in another
     space was buried under a weak one here. The groups also cost: the first keystroke's longest task is mostly the
     first list's render, not the matching (measured at volume, § Consequences), and five groups of eight drew up to
     45 rows where one list draws 11. An invisible context weight was rejected earlier because nothing on screen said
     why a result came first; here the place is a tie-break after the match, and every row names it. (Owner, 2026-11-08:
     the open Memory's content comes first - it still does, at an equal match.)
6. **Core owns the field, the search model, the chips' grammar, the order of the results and the names of every node.** An
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
         the tree revision, one join over the live nodes' item revisions and the blocks, and no media query. On MariaDB
         the listing scans `ix_media_scope` in id order with no filesort (EXPLAIN at 10,000 photos); the join's plan on an
         instance with many tenants was not measured.
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
9. **People and settings are results too: two kinds in the one list of decision 5, after content at an equal match.**
   (Owner, 2026-11-09.)
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
   - **Kinds, not a rule of their own:** each has its kind word (*Person*, *Setting*), and a chip is a predicate over them
     as over content (decision 4). No rule hides them while a chip is set: a chip they cannot satisfy leaves them out by
     itself, and *Kind: Person* shows only them. They live in no space, so their place in the order is after every
     space's: a query's content results keep their order, and a person or a section still comes first where it matches
     better.

## Consequences

- **What the server learns:**
  - The server learns that a member opened each of their spaces' trees, as it does when they enter each space.
  - Stage 8(a) adds one read, `listScopeMedia`: that a client read every listing of a space - ids, sizes and times, the
    kind of fact opening each Memory reveals today - and, while nothing moved, one `304` per space per focus. Nothing
    about a query.
- **The cost of a first search across many spaces is one tree fetch and one media listing per space, opened in
  slices.** It is paid on focus, not at sign-in. Measured (2026-11-09, with the photos of decision 8a) on this Mac in
  desktop Chrome, on the volume harness's own instance with 7,000 nodes over three spaces (one of 5,000), 10,000 photos in
  one Memory and 445 members, at a 1-minute load of 5.9–8.4:
  - **The build:** 1.8–2.6 s until every space and its photos are indexed, 3.3–5.0 s at a 4× CPU throttle. Its longest
    main-thread task is 53–79 ms, and 84–119 ms at 4× (bound: 200 ms). It runs in slices (`searchIndex.ts`, at most
    40 ms at a time, the space on screen and an application's objects included); before it did, one task per space held
    the main thread 0.8–2.6 s at 4× (measured without photos).
  - **Typing** "K", "Ke", "Kev", a chip and Backspace: no long task unthrottled; the longest at 4× is 57–99 ms (bound:
    100 ms), the first keystroke's, most of it Vue's render of the list. Before a document that does not hold the text
    was answered by one search and the text's words were split once per query (`query.ts`), the photos took it to
    70–113 ms - over the bound in two of twelve readings. With the month chip, the
    settings' words and the bold part of each row in (the field now narrows the matches it remembers while the text grows
    and ranks only as far as it is shown), at loads of 5.3–37: 76–100 ms in two invocations, every reading holding;
    one reading of another went to 207 ms at a load of 8, and one at 115 ms at a load of 30. The margin is the first
    list's render, not the matching. With one list of 10 (decision 5), the first keystroke
    draws 11 rows instead of up to 29, and its longest task at 4× fell to none of 50 ms or more in four readings of eight and
    50-66 ms in the rest, at loads of 5.6-13: a margin of 34 ms or more.
  - **A second focus with nothing changed** reads each other space's tree and every space's media listing as one `304`
    each, and the index opens nothing; nothing else decodes either (the settings sections are read
    once, on the first focus).
  - **The index's memory:** 9.1–9.6 MB of JS heap since each document carries the ids above it (decision 4's *In:*),
    7.8–8.4 MB before (3.7–4.2 MB without the photos).
  - The command: `scripts/measure_volume.py --instance I search --runs 2 --throttles 1,4 --active tenant|group`, after
    `seed tree --target 5000`, `seed spread --target 1000`, `seed members --target 445` and `seed photos --target 10000`.
    Judged on a busy machine, with the load recorded at both ends of each run: a bound that held under load passed, a
    failure got one re-run and would have failed on a second ([`changes/README.md`](../../changes/README.md), *machine load
    is part of a timing's substrate*). The individual readings were not kept. The harness is temporary,
    `cr-volume-measurement-cax` deletes it.
- **A removed member's search loses that space** when its keys go, and keeps nothing they could not read before.
- **Rejected alternatives:**
  - A persistent on-device index (decision 2).
  - Server-side blinded tokens (decision 1).
  - A single ranked list with an invisible context weight that can lift a weaker match above a stronger one
    (decision 5: the place breaks ties only, and every row names it).
  - Result groups by place, with headings (decision 5's first form, built and replaced on 2026-11-09).
  - Searching only the active space: the owner wants every space.
