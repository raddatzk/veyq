# Memories: the live journal (concept)

> **Concept, not built and not refined** (inbox #18 and #6, *first after V1*, owner 2026-10-29). It is the decision
> basis for the refine with the owner: each question below has its options, a recommendation and the price of it,
> and the product choices only the owner makes are at the end. Nothing here is decided; the ADRs the refine produces
> take the decisions over (§ Slicing), and this page then keeps only the domain.

The journal is a **function of Memories**, not an application of its own: the text of a Memory (and of each Chapter),
written in markdown, with the Memory's photos, its map and later its tracks (#781) embedded, and written by several
people **at the same time** — not one writer after the other. The server stays operator-blind: it stores and relays
sealed updates it cannot read and cannot merge; every merge, every snapshot and every rendering happens in a client.
[features/memory](../features/memory/_index.md) already plans it as "markdown sealed under `K_node`", the capability
register names `memories.journal` ([ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)),
and [ADR 0125](../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md) stage 8b waits
for it. **Built today:** nothing but the safe markdown renderer `src/web/src/shared/markdown.ts` (hand-written, the
sanitizer of the Trusted Types policy, [ADR 0117](../decisions/0117-the-web-client-enforces-trusted-types.md);
no embeds) and [ADR 0033](../decisions/0033-live-media-updates-revision-polling.md)'s polling, the platform's only
live mechanism — the backend has no WebSocket, no SSE and no push of any kind (measured:
`grep -rlE 'WebSocket|websockets|SseBroadcaster|text/event-stream|RestStreamElementType' src/backend contracts` finds nothing).

**The earlier, pre-E2EE veyq had one** (read-only reference, `git -C ../veyq-no-e2ee show 640fe0f2`): a journal per
Memory and Chapter, two sealed projections — a markdown body every reader used and a Yjs log editors appended to — with
compaction the only writer of the markdown, and embeds as tokens in the text (`::media:id1,id2 label`, `::track:…`,
`::chapter:…`), a CodeMirror chip editor on the web and a block editor on the phones. This concept keeps its two
projections and its token syntax and rebuilds the rest on today's platform.

## The shape in one paragraph

A journal is **a CRDT document whose content is the markdown text itself**. Every edit becomes a small binary update
that a client seals and appends to the journal's log; the server numbers it (a **position**, as ADR 0033's) and
hands it to every client that has the journal open. A client reads the latest **snapshot** plus the updates past it,
merges them locally, and from time to time — a member's client only — writes a new snapshot that folds the log
in. Each snapshot starts a new **snapshot revision** with its own random key, so a report, a scope rotation and the
bounded keeping of earlier states each act on one snapshot revision, never on the whole Memory.

## 1. Which CRDT

All three serious candidates are MIT-licensed, treat an update as opaque bytes (so sealing changes nothing for them)
and merge in the client. Remembered unless marked; the library facts were read on 2026-11-12 from npm, crates.io,
bundlephobia and the projects' GitHub pages (measured, sources in the refine's ADR).

| | **Yjs** (JS) + **Yrs** (Rust port, same wire format) | **Loro** | **Automerge** |
|---|---|---|---|
| State (measured) | yjs 13.6.33 (2026-09-23); v14 in release candidates under the new name `@y/y` (rc.29, 2026-10-15); yrs 0.28.1 (2026-11-04) | 1.17.1 (2026-10-22), format stable since 1.0 by its own docs | 3.5.0 (2026-09-16) |
| Web cost (measured, gzip) | 28 KiB + lib0 | 1.2 MiB of wasm | 1.15 MiB of wasm |
| Native (KMP) | no maintained Kotlin or Swift binding (`yswift` last released 2024, `ykt` dead); Yrs is bound through UniFFI by us | official `loro-swift`; Kotlin generated from its UniFFI `loro-ffi` by us | `automerge-swift`; `automerge-java` (JNI) states no licence |
| Editor binding (web) | `y-codemirror.next`, `y-prosemirror`, Tiptap's (MIT), Lexical's | `loro-codemirror` 0.3, `loro-prosemirror` 0.4 | `@automerge/prosemirror` 0.2, "beta" |
| Implementations of the format | three (yjs, yrs, AFFiNE's y-octo) | one | one (Rust, wrapped) |
| Used E2EE in production | Proton Docs (sealed, signed updates; the client squashes past 500 updates) | — | — (Ink & Switch's Keyhive is pre-alpha) |

**Spike** (measured 2026-11-13, a throwaway script outside the repository; yjs 13.6.33, loro-crdt 1.17.1,
@automerge/automerge 3.5.0, Node 23.11, `node spike.mjs`): one deterministic typing session of 20,000 characters —
appends at the cursor, 5 % backspaces, 1 % jumps to an earlier place — flushed every 5 keystrokes (about a second of
typing), each flush sealed with AES-256-GCM:

| | sealed update, median (p99) | updates / log | snapshot | compaction: log → snapshot | open a snapshot |
|---|---|---|---|---|---|
| Yjs, update v1 | 112 B (123 B) | 4,473 / 487 KiB | 57 KiB (v2 encoding 31 KiB) | 18 ms (replay into a doc); `mergeUpdates` without a doc 153 ms and 319 KiB — no compaction | 2 ms |
| Loro | 119 B (141 B) | 4,473 / 537 KiB | 22 KiB; shallow (earlier states cut) 8 KiB | 70 ms | 2 ms |
| Automerge (first 4,000 keystrokes) | 568 B | 800 / 445 KiB | 4.4 KiB | — | — |

At 100,000 characters (`CHARS=100000`): Yjs snapshot 325 KiB (v2 157 KiB), compaction 83 ms; Loro 79 KiB, 277 ms.
Sealing per keystroke instead (`BATCH=1`): Yjs 55 B, Loro 114 B per update. The markdown itself was 19.7 KiB. So
sizes and compaction cost decide nothing — every candidate is cheap enough; a client compacts a long journal in well
under a second, and two Yjs writers converged (measured in the same run).

**OT or last-writer-wins is out.** Operational transformation needs a server that transforms plaintext operations
against each other, which a blind server cannot. A sealed document with compare-and-set (`409 stale_revision`, as a
Memory's meta) is one writer at a time — what the owner ruled out.

**Recommendation: Yjs on the web, Yrs in the native apps — with the markdown as one `Y.Text`.** Tradeoff: the
smallest web cost by far, the most editor bindings, and the one format three independent implementations speak — the
vendor-neutral choice our dependency rule prefers over a single project's format; against it, we write and keep the
UniFFI binding of Yrs for Kotlin and Swift ourselves, and the v13 → v14 move is under way (start on v13's update
format; whether v14 keeps it is a fact the refine must measure before the ADR). **Loro is the strong second**: one Rust
core on every client, an official Swift package, smaller snapshots and built-in shallow snapshots — for 1.2 MiB of
wasm on the web (loaded only when a journal opens; the CSP already allows `'wasm-unsafe-eval'` for the WebP encoder,
[ADR 0127](../decisions/0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)) and one implementation of
its format. Automerge loses on update size, web weight and its beta editor binding.

**Why one `Y.Text` of markdown, not a rich-text tree:** a native app binds a plain-text CRDT to a text field by
diffing the field — no native rich editor with a CRDT binding exists for Android at all (measured: the only native
one of the web editors' families is Lexical's iOS editor; that it has no CRDT binding is remembered), and a rich-text tree would leave the apps unable to edit. It also keeps the
stored format one we can read forever without any CRDT library (§ 2's projection). The editor style the owner sees
is still open (owner question 2) — a markdown-source editor with live preview binds to it directly.

## 2. Storage: a sealed log and client-made snapshots

In Memories' own tables, keyed by the Memory's (or Chapter's) node id ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 6):

- **A snapshot revision** (one row each): its number, the scope epoch its key was minted at, its key `K_jrev`
  (random, wrapped under the node's `K_node` — the shape of an Expenses revision's `K_rev`,
  [ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 4), the
  position of the last update it folds in, its writer, and two seals under `K_jrev`: the **CRDT state** and the
  **markdown projection** (the text as it stands, with its embed tokens). Padded to size classes.
- **Updates** (one row each): the snapshot revision it belongs to, its server-given position, its writer (a member or
  a link identity, as every revision's today), its time, and the update sealed under `K_jrev` with its object bound
  in the AAD (journal, snapshot revision, a client-minted update id; [ADR 0072](../decisions/0072-a-seal-binds-its-object.md)).
  Padded to size classes; a contract cap per update (a long paste is several).

**Who compacts, and when.** A **member's** client, never a guest's: a snapshot is the one write that drops
earlier states. It compacts when it opens a journal whose updates past the snapshot exceed a threshold (Proton
squashes past 500 updates; the refine picks ours), when it closes the editor after writing, and always as the first
write after a scope rotation (§ 6). Jittered, so two members opening at once rarely both try.

**The stale-snapshot race.** A snapshot names the snapshot revision it continues; the server takes revision `n + 1`
only over `n` (`409 stale_revision`, the platform's compare-and-set). Two compactors at once: one wins, the other
drops its snapshot and reads the winner's. Updates keep landing while a compactor works: the server takes updates for
`n` until `n + 1` exists and answers later ones for `n` with `409` — the client re-seals its unsent updates under the
new key (CRDT updates are idempotent, so re-sending is harmless). Updates of `n` past the snapshot's last folded
position stay and are served with it as its **tail**, so no edit is lost to a race and no compactor ever has to win
against live typing.

**A forged snapshot.** A member's client could write a snapshot that drops text no update deleted. Every client that
still holds the previous snapshot revision checks the new one against it (the old state plus the updates up to the
folded position must equal the new state; Proton verifies before it squashes) and says so where it differs; and the
earlier snapshot revisions are **kept for a window** (the platform's trash retention,
[ADR 0061](../decisions/0061-node-deletion-reversible-node-trash.md)), from which a member restores an earlier state —
written as a new snapshot revision, so the restore itself is undoable. Past the window the server deletes earlier
snapshot revisions and their updates, a structural job on rows and times it already sees
([ADR 0021](../decisions/0021-operator-blind-background-tasks.md)). That bounds what is kept, and with it what a
report or a leaked key can reach.

**Dropped and reordered updates** — the gap the [security model](../crosscutting/security-model.md#applications-are-compiled-in--never-loaded)
asks every append-only application to state. The CRDT's own causality covers most of it: an update that builds on
one the server withheld waits in the client as pending (each Yjs writer's clock runs without gaps, and an insert names
the item it follows), and the client shows that. Newest updates held back look like a stale read: the platform's
freshness limit, stated, not closed (ADR 0108 decision 5's wording). Whether a withheld *delete* is detected the
same way is a property of the library's encoding to bite-test before the ADR, not assumed here.

**Reading without the CRDT.** Search, export, a `READ` guest, the moderator and a native app before its binding ships
read the newest projection plus nothing else, and need no CRDT library; whoever edits reads the CRDT state plus the
tail. The price: a projection lags by its tail until the next snapshot, and a client that edits checks the projection
against the state it merges.

## 3. Transport

| Option | Latency | Cost and fit |
|---|---|---|
| **ADR 0033's poll**, at a short cadence while a journal is open | the cadence (1–3 s) | nothing new; a request per open editor per tick |
| **Long-poll** with the position as cursor | sub-second | nothing new in the proxies; a request held per open editor |
| **SSE** down (`Last-Event-ID` = the position), ordinary POSTs up | sub-second | one stream per open editor; plain HTTP through Traefik and an ingress (no buffering, a heartbeat inside the read timeout) |
| **WebSocket** both ways | lowest | an authentication, rate-limit, Origin and guest-gate path beside core's REST gates; `wss:` in the CSP; an upgrade through every proxy |

**Recommendation: SSE down, POST up — the stream in core, the journal its first user.** Every write stays an
ordinary operation behind core's existing gates (session or share guest gate, Origin, rate limit, the application
switch's `requireWritable`, a moderator's `451`, the guest-write record), described in the contract like every other
(API-first); only the read side is new, and a reconnect resumes from its cursor, which is also the offline catch-up:
a client that comes back sends its last position and gets the updates past it — paged, or the newest snapshot when
its cursor is older than the snapshot's tail. The stream is the push seam ADR 0021 left open ("the same backend, no
broker"), so it is **core's**, as ADR 0033's counter is: an application publishes "an item at this position of this
node's log", the stream carries it, and the journal's tables stay Memories'. Tradeoff: the first long-held
connection in veyq — ADR 0033 and [ADR 0081](../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)
rejected push partly because a held connection is a sharper presence signal than a poll (§ 4). **Slice it after the
log** (§ 7): the log on ADR 0033's poll first proves storage, keys and merging without a new transport.

**Scale and self-hosting.** Both deployment targets run **one** backend (the chart's `app-deployment.yaml` has `replicas: 1`, with `Recreate`
for its migrator, `infra/kubernetes/charts/veyq/values.yaml`; measured by reading both), so fan-out is in-process: a POST
hands its update to the streams of the same process. Should more replicas ever be decided, each replica tails the
update table by position for the journals it has streams for — the database it already has, no broker; a broker
(Redis, NATS) would be the first extra service a self-hoster must run, and is rejected. Compose's Traefik passes SSE
as it is; the chart's ingress needs buffering off and a timeout above the heartbeat — a line in each, checked on both
targets ([ADR 0106](../decisions/0106-deployment-under-infra-tests-under-tests.md)'s "every infra decision has an
answer on both").

**Offline.** The web client keeps unsent updates in the open tab and sends them on reconnect; surviving a reload
would need plaintext or sealed local storage, which the web client has none of today — the native apps' job (#438).
A long offline edit merges like any other; the CRDT guarantees convergence, not that two people's paragraphs read
well together.

## 4. What the operator learns

Per journal: that it exists (rows under a Memory or Chapter node), the number of snapshot revisions with their times,
size classes and writers, and every update's time (to the millisecond), size class and writer — so **who writes when,
and roughly how much**: with a flush per second, the number of updates is about the seconds of typing. With the
stream: **who has which journal open, and when** (and a guest's address, § 5). Not the text, not which passage, not
the embeds, not a cursor.

Minimised by: a fixed flush tick (an update per second at most, not per keystroke), size classes for updates (the
spike's sealed batch of ~120 B fits a first class of 256 B; a paste lands in a larger one) and for snapshots (powers
of two), no stored connection log beyond what § 5 requires, and no time stored without a reader (the
`EveryStoredTimeHasAReaderTest` rule). **Not** minimised: cover traffic (empty updates on a fixed tick while a
journal is open) would hide *whether* someone types but not *that* the journal is open, at the cost of a row per
second per open editor — rejected, the residual named. A journal update bumps **no** scope tree revision (ADR 0081):
otherwise every member's level view would re-read once a second while someone types.

**Presence and cursors**, if the owner wants them (question 3), are ephemeral messages sealed under `K_jrev`, relayed
by the stream and **never stored** — a fixed size, a few per second at most. The operator sees that they flow (it
sees the stream anyway), not where anyone's cursor is. Yjs' own awareness protocol sends names and cursors in the
clear and is not used as it is.

## 5. Authenticity and abuse

**Who wrote it.** The server knows each update's writer, as it knows every revision's today (layer 1,
[ADR 0026](../decisions/0026-client-trust-tiers.md)); a seal under `K_jrev` proves a key holder wrote it. **No
signature per update in the first cut** — ADR 0108 decision 5's reasoning, and a signature could not survive
compaction anyway (a snapshot is the compactor's, not each writer's). Content writes are unsigned platform-wide today
([security model § What stays open](../crosscutting/security-model.md)); signing them with the identity key that signs
grants ([ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md) decision 6) is the platform's later
step, and the journal would take it with everyone else. So "who wrote this passage" is shown, if at all, as the
server's attribution, never claimed as proven.

**A CRDT-specific trap:** a Yjs writer's id is chosen by its client. A writer who reuses another's id with different
content makes clients that see the two in different orders diverge for good. Two guards: every client applies updates
in the server's order (so all diverge identically, i.e. not at all), and a client pins each writer id to the
server-attested writer of the first update that used it, dropping a later update that reuses it from someone else.

**Garbage and size.** An update that does not decode is skipped and marked. Bounds the server enforces on what it
sees: a cap per update, a rate per writer, a cap on the tail (past it, updates are refused until a member's client
compacts — so a guest cannot fill storage), a cap on the snapshot, and the journal's bytes against the storage quota
([ADR 0073](../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)). Vandalism — a writer deleting
everything with valid updates — is answered by restoring an earlier state (§ 2), not prevented.

**Share guests.** A share view is the member view with functions removed (owner principle, #401): a `READ_WRITE`
guest edits the journal as a member does (owner question 4), but neither compacts nor restores; a `READ` guest reads
the projection and polls on its own tag ([ADR 0033](../decisions/0033-live-media-updates-revision-polling.md)
decision 7), not the stream, which would tell an anonymous visitor when the members write; `WRITE_ONLY` has no
journal (a CRDT writer must read the state it edits). **One legal point:** [ADR 0093](../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 10 records a guest's address, port, time and `Origin` **per write**, kept up to 30 days — a typing guest would
leave one row a second. The refine amends § 10 to one row per editing session (its first and last time, its count of
updates, a new row whenever the address changes); whether that still serves the evidence purpose is the legal reading
the amendment must state.

**Reports and holds.** A report of the journal is an `ITEM` whose report share carries the current snapshot
revision's `K_jrev` (and its predecessor's, while a tail of it is served): the moderator reads the text as it stands
plus the edits since the last snapshot — not the Memory's photos, not states the window already dropped; a report of
the Memory carries `K_node` and reaches every kept snapshot revision. The hold copies the snapshot revision rows and
their updates (ADR 0093 § 4); a content block refuses every later update with `451`. The reporter may mark a passage
as context, which stays context, not evidence (§ 1).

## 6. Against the platform's machinery

- **Scope rotation** ([ADR 0109](../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)): the re-key
  re-wraps every kept `K_jrev` under the new `K_node` and changes no sealed body (its decision 3, as Expenses' `K_rev`).
  But a `K_jrev` minted before the rotation is one the excluded party may hold, so **no update lands under it**: an
  update to a snapshot revision minted below the scope's current epoch is refused (`409`, decision 6's shape), and the
  writer's client starts a new snapshot revision under a fresh key — a rotation is a forced compaction, not a
  re-sealing of the log.
- **Export** ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)):
  the Memory's content carries its journal as markdown — the projection, with each embed token rewritten to the
  exported file it names — in `contracts/export/memories/content.schema.json`; no CRDT state and no earlier states.
  An import starts a fresh journal from that markdown, the importer its first writer.
- **Trash and erasure:** the journal lives and goes with its node (trash, restore, purge). An erased account's
  updates stay in the shared text, their writer shown as a former member — as an Expenses entry stays (ADR 0108
  decision 2).
- **The switch** (#539, [ADR 0088](../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)):
  `memories.journal` off — no new update, snapshot or restore, reading kept; Memories off — the same, then erased with
  Memories past the window.
- **Search** (ADR 0125 stage 8b): the index reads each journal's projection — one sealed text per journal, no replay —
  keyed by its snapshot revision; a journal being edited is found by the text of its last snapshot. Guests have no
  search.
- **Embeds** (#35, folded into #18): the old token syntax, inside the sealed text, so the server never learns which
  photo a passage shows. `::media:` and `::chapter:` name ids of the same Memory; `::map:` the Memory's places; `::track:`
  a track once #781 lands (this concept only names the token; the track's own model is #781's). Rendering goes through
  the safe renderer, extended with the tokens. An embed a viewer cannot open — a parent's photo in a Chapter's journal
  seen through the Chapter's link — renders as a placeholder. An application never embeds another's object
  (ADR 0079); a link card to a generic core reference (scope and node id) is the most it gets.

## 7. Slicing

**Straight to the CRDT, not a single-writer document first.** A single-writer markdown document with
compare-and-set is cheaper to build and proves the renderer, the embeds, export and search — but it is what the owner
ruled out, and its storage would be replaced rather than extended. The irreversible part is the log, its keys and
its snapshot rule; the transport is the reversible part. So:

1. **The log on the poll** (large) — Memories' tables and contract, snapshot revisions and their keys, compaction and
   its race, the projection, the editor on the web (members only), embeds of photos and Chapters, export and import,
   trash, the switch, rotation's forced snapshot, report and hold, restoring an earlier state. Transport: ADR 0033's
   poll at a short cadence while open. Proves: everything but latency.
2. **Core's stream** (medium) — SSE with the position as cursor, in-process fan-out, the heartbeat, the chart's and
   Compose's lines; the journal moves onto it. Proves: sub-second co-editing, catch-up after a reconnect.
3. **Guests write** (small–medium) — the share API's update route behind core's guest gate, ADR 0093 § 10's amendment.
4. **Presence and cursors** (small, if wanted) — ephemeral sealed messages on the stream.
5. **Search stage 8b** (small) — the projection into ADR 0125's index.
6. **Native** (large, with #438) — the Yrs binding through UniFFI, reading first (the projection needs no CRDT),
   editing second.

**The ADRs the refine produces:** (a) *the journal: a Memory's markdown as a sealed CRDT log with client-made
snapshots* — the CRDT and its format, snapshot revisions and their keys, the race, compaction and the kept window,
reports, rotation, export, search; (b) *core's live stream* — SSE, cursor, fan-out, what it reveals, revisiting the
push rejected in ADR 0033 and ADR 0081; (c) an amendment of **ADR 0093 § 10** for a guest who writes by the second.
Embeds belong to (a); the native binding to the native client's ADR (#438).

## On the platform

| Need | Today |
|---|---|
| A journal per Memory and Chapter, its key, trash, report, the switch | The node is there; the journal is Memories' tables keyed by its id, `memories.journal` in the register. |
| A sealed, append-only log the server numbers | **New**: ADR 0033's position (a per-node counter's bump) gives the order; ADR 0108's revision table is the nearest shape, but appends without a per-record chain. |
| A client's snapshot replacing the log | **New**: snapshot revisions with `n + 1` over `n`, a key each, a kept window. |
| Sub-second delivery | **New in core**: the stream (ADR 0021's push seam). Until then, ADR 0033's poll. |
| Guest edits | Core's guest gate and share API, as Expenses' entries; ADR 0093 § 10 amended. |
| Rendering with embeds | The safe renderer, extended with the tokens. |
| A CRDT on every client | **New dependency**: Yjs on the web, Yrs bound for the apps (recommended). |

## Open owner questions

1. **One journal per Memory and per Chapter, or one per Memory only, or dated entries (a diary of days)?**
   Recommended: one per Memory and per Chapter — a Chapter is a day or a stage, its text is its own, documents stay
   small and a report reaches one of them.
2. **Editor: WYSIWYG, markdown source with live preview, or blocks?** Recommended: markdown source with live
   preview (headings styled, embeds drawn as cards in place) — the one the native apps can edit too (§ 1).
3. **Presence: none, who is here, or live cursors with names?** Recommended: who is here first, cursors as their own
   later slice; neither tells the operator more than the stream already does.
4. **May a `READ_WRITE` link guest write the journal?** Recommended: yes, as the member view with compaction and
   restore removed — guarded by restoring an earlier state, and costing the evidence rows of § 5.
5. **Who wrote what: nothing, a contributors' list, or passages coloured by writer?** Recommended: a contributors'
   list first; colouring shows the server's attribution, which no signature proves.
6. **Restoring an earlier state, and for how long?** Recommended: members may, within the platform's trash
   retention; longer means more kept text a report or a leaked key can reach.
