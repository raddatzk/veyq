---
id: cr-gallery-model-web
title: Memories' gallery model onto the web — GalleryModel drawn through @veyq/core-js/memories over a door the web writes, its TypeScript twin deleted
status: in-progress
satisfies: ["uc.memory.gallery", "uc.memory.live-updates", "uc.memory.delete-media"]
adrs: ["0155", "0136", "0156", "0033", "0105", "0079", "0154"]
invariants:
  - "no key crosses into the model: the web's door hands it the opened facts it orders and splits by, the web reads every byte itself (operator-blind E2EE unchanged)"
  - "the model reaches I/O only through its door: no session, no generated API client, no Ktor and no kotlinx.serialization in any entry's bundle (the package's slice check)"
  - "share view = member view: the guest's Memory page and the member's draw the one model; rights only remove what it offers"
  - "every hot path the page runs is at most 100 ms over a 10,000-medium Memory, its answers identical to the deleted TypeScript's (ADR 0155 decision 7.3)"
---

# Memories' gallery model onto the web

Inbox #1323, decided by the owner 2026-11-25 (ADR 0155 decision 8: the models cross; every size gate dropped that day).

## Refinement — the row's question, re-derived

The row asked whether `GalleryModel` crosses *as it is* or reads only through its `Door` first. Measured against the tree
(`b53b952f0`'s probe, ADR 0155 § Memories' move D7): the door already is the model's whole I/O surface — the 450 platform
classes did not come through the door but through **five static reaches beside it**, each a Kotlin/JS initializer or a
concrete type the whole-program elimination cannot see is never instantiated on the web:

1. `ItemMarks` — a concrete class over the tenant (the generated `ItemFavoritesApi`, `TenantSession`, a `ListSerializer`),
   reached through the model's `itemMarks`, `readMarks`, `toggleMark` and `GalleryFilter.predicate`;
2. `MemoriesSearch`'s object initializer (`val search = ApplicationSearch { scopeMediaDocuments(it) }`: the space's whole
   listing, Ktor), reached by `mediumDocument`;
3. `MemoryMedia`'s and `MediaDetails`' `Json { … }` fields, reached by `order` and `downloadName`;
4. `NodeExport`'s `pretty` `Json`, reached through `GalleryFilter.Calendar.UTC` → `NodeExport.isoMs`;
5. `UploadQueue.Status` — a `@Serializable` enum whose companion's serializer every use of an entry initializes, reached by
   `UploadTile.shown`.

So the coordinator's stance holds without a new port for the reads: the existing `Door` is the narrow port; what needed a
port was the marks (`GalleryModel.Marks`), and what needed moving were four initializers and one enum read. Then the web
implements the door over its existing source (`source.ts`: a member's or a link guest's), and nothing of the session, the
client, Ktor or the serializer is in any bundle.

What the web had beside the model and does not hand over in this CR (each its own row, measured against what moves):
the thumbnail pipeline (decoding and its cache are the platform's, ADR 0136 decision 1 — never moves), the in-flight
upload tiles with their previews (#1342), the lightbox's snapshot, slideshow and details (#1343), the marks (#1340), the
filter and the rights matrix (#1321), the Trash tab (no Kotlin twin).

## Plan (one phase: core → package → web)

- core: `GalleryModel.Marks` (the port `ItemMarks` implements); the four initializers in holder objects; `UploadTile` reads
  its status once; `MemoryMedia.order(items, oldestFirst)` stable on the listing's order; `GalleryModel.sort`,
  `State.oldestFirst`; the page's one `LivePoll`; `GalleryModel.restored`.
- package: `GalleryWeb.kt` (the façade: an opaque `GalleryHandle`, `GalleryWatch`, a microtask dispatcher), its shapes;
  `slice.test.ts` widened (coroutines in every row, in this commit); `gallery.test.ts` through the shipped module.
- web: `galleryDoor.ts`, `useGalleryModel.ts` (decision 8's one composable), `useMediaGalleryLive.ts` drawn over them;
  `mediaRevisionPolling.ts` and its test deleted; `MemoryDetail.vue`'s guest purge through the model.

Done when: the mobile checks (`compileKotlinJs compileTestKotlinJs jvmTest macosArm64Test`), the package's `pnpm run build
&& pnpm run check`, the web's `pnpm run check` and every `scripts/validate_*.py` exit 0; the sizes decision 10 names
measured; the hot paths timed; one bite per load-bearing new test.

## Decision log (kept by the implementer; harvested into ADR 0155 § The gallery model's move)

- **D1 — The set: the gallery's listing state, not the page.** What crosses is what the model decides and the web twinned:
  what is listed and split (photos, music, GPX tracks), in which order, when it reads anew (the live poll, an upload in, a
  restore), a member's trash with its Undo and a refused restore named, a guest's purge asked first. What stays is listed
  above; each has its row. *Why:* every other part of the page has its own web state the model's would duplicate until
  that part moves (the marks' store, the lightbox's snapshot); moving them in one CR would change six screens at once.
- **D2 — The door is the port; five reaches cut.** (Refinement 1–5.) `GalleryModel.Marks` is public (the door's `marks()`
  returns it, so `ItemMarks.setLocal` became public) — a public Kotlin signature change, the apps' header changes.
  *Measured* (the package's bundle of every platform entry, gzip -9): as the probe left it 380,894 B gz; with 1–3 cut
  150,385; with 4 148,170; with 5 145,049, and no kotlinx.serialization or Ktor source in any entry's map.
- **D3 — The grid's order is the model's, in the web's direction and with the web's ties.** The web toggled newest/oldest
  (cr-0066) and broke a tie by the listing's order; the core had one direction and broke ties by id. ADR 0154's rule (the
  web's answer won): `MemoryMedia.order(items, oldestFirst)` is a stable sort, an undated medium last either way, and the
  model orders every listing itself (`GalleryModel.sort`, `State.oldestFirst`). The apps' order changes only on a tie.
- **D4 — One poll per page, so a page shown again catches up.** `live()` made a `LivePoll` per run, whose last start was
  always now: a page shown again waited the whole interval, against `LivePoll`'s own contract and the web's poller. The
  model keeps one poll across its runs; the web starts it while the tab is visible and stops it when hidden.
- **D5 — A restore from the Trash tab lifts the model's tombstone** (`GalleryModel.restored`): a medium trashed here and
  restored before a read showed it gone stayed hidden (the web had `liftTombstone`; the model had no way in).
- **D6 — The web's model runs on microtasks.** Kotlin's default dispatcher on the web queues each step through
  `postMessage`; a page then drew a read a task late, and the settle the model starts right after a read ran before the
  web's state held the rows. Each step is a microtask (`Promise.then`), as the TypeScript's awaits were; delays stay
  `setTimeout`. The web sets its state in the model's own listener (no Vue watcher between).
- **D7 — A refused restore names the medium by its place in the gallery's rows**, not in the web's lightbox snapshot (a
  bucket's order there): the lightbox is still the web's (#1343). The one answer that changed; a test was re-pointed.
- **D8 — Settle points are the model's** (the page's opening, a medium's trash) plus an upload batch's end on the web, as
  the apps' `MediaUploads` settle it; a live poll no longer settles (the web settled after every poll: a peer's change is
  that peer's to settle). The settle itself stays the web's (`MemoryDetail.settle`).
- **D9 — The model's rights are fed the web's delete reach only:** a contributor's link (`READ_WRITE`: a purge), a member
  who trashes, else one who deletes nothing — the page's other rights stay the web's matrix (#1321).
- **D10 — The music and the tracks reach their tabs in the listing's order**, as before (each tab orders its own): the
  model's split decides membership, the door's held listing the order handed over.
