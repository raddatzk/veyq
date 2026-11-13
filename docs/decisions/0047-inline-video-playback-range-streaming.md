# ADR 0047 — Inline video playback via a Service-Worker range shim

- Status: Accepted
- Built: partly (not built: § The small playable copy)
- Date: 2026-07-22
- Model: [en.memory.media](../features/memory/enablers/en.memory.media.md)

## Context

A video in a Memory was **a still after reload** (inbox #104). The gallery derives one JPEG **first-frame poster**
for both the thumbnail and the preview tier ([en.memory.derivatives](../features/memory/enablers/en.memory.derivatives.md)),
so the lightbox had only an image. The wish is to **play the video there**, accepting the **pristine original** as
the source: there is no compressed/streamable video tier, and there is none until a transcode on a key-holding device
exists ([en.memory.playback-derivative](../features/memory/enablers/en.memory.playback-derivative.md), planned with
the client worker queue, inbox #493) — building one in the browser tab is exactly the throwaway stopgap `CLAUDE.md`
forbids (*structured over fast*).

So the question this ADR settles is narrow: **how does the client feed decrypted video bytes to a `<video>`
element while the server stays operator-blind?** Media is stored as **chunked AEAD** — a fresh random `K_blob`
per blob (wrapped under `K_node`), each chunk `nonce ‖ AEAD(K_blob, nonce, plaintext, aad = index ‖ total)`,
transferred **direct-to-store** via presigned URLs ([en.memory.media](../features/memory/enablers/en.memory.media.md),
[ADR 0013](0013-blob-storage-presigned-direct-to-store.md), [ADR 0012](0012-content-key-hierarchy.md)). The read
paths that existed — grid, lightbox, download — fetched a blob **whole**: presign all chunk GETs, fetch every
frame, open each, concatenate. That is fine for a poster or an image preview; it is the wrong shape for a
video the user scrubs through.

## Decision

**Play video through a Service Worker that intercepts a synthetic in-origin media URL and answers `206 Partial
Content`, while the page — not the SW — presigns, fetches and decrypts.** The browser's own `<video>` demuxer
drives the byte ranges; there is no MSE and no remux. The SW holds **no key material and no auth**: it maps the
demuxer's plaintext byte-range to the covering ciphertext chunk indices and requests the **decrypted plaintext**
of those chunks from the owning page over a `MessagePort`; the page runs the existing presign→`openFrame` path
in its own context and transfers the plaintext back. The SW slices the requested window and responds with
`Content-Range` + `Accept-Ranges: bytes`.

Concretely: before playback the page **registers** it with the SW (`postMessage`: an opaque per-playback handle
+ `{chunkCount, byteSize}` + a `MessagePort`), then sets `<video src="/__media/<handle>">`. For a `Range:
bytes=A-B` request the SW computes the covering chunk indices `[⌊A/CHUNK⌋ … ⌊B/CHUNK⌋]` from the registered
`{chunkCount, byteSize}`, asks the page for those chunks' plaintext over the port (with a small in-memory
read-ahead cache), assembles the window and answers `206`. **No key, no auth token and no plaintext-at-rest ever
enters the SW; the URL carries only an opaque handle, never a credential.** `K_blob`/`K_node` stay exactly where
they live today — in the page.

**The handle is opaque because a `blobId` could not carry the auth context.** A route keyed on `blobId` would
require the SW to presign a chunk GET itself, and it cannot: presigning is bearer-authed for a member (the token in
page memory, refreshed there) and share-token-authed for the anonymous **share viewer** (the share id and a guest's
password token), and both are whole-blob only. A SW holds neither
credential and cannot tell the two routes apart — it would break first, and most visibly, at the share viewer.
Keeping the handle opaque puts the auth context behind the registered `MessagePort`, in the page, where both
credentials already live.

**The SW's plaintext cache is in-memory and size-bounded — never Cache Storage, never IndexedDB.** At most a
small read-ahead window of decrypted chunks, evicted by size and dropped when playback ends. Persisting
decrypted media to disk would be a real regression against today, where no plaintext is stored at all, and it
is named here because it is the shortcut a later change is most tempted to take.

### Why not the rejected options

- **(A) Full blob, then play** — fetch + decrypt the entire original into memory, `<video src="blob:…">`. Correct
  and trivial, and it stays the fallback where no Service Worker runs. Rejected as the **strategy** because it
  scales the wrong way: a long video makes the user wait for the *whole* multi-hundred-MB decrypt before the
  first frame, and holds it all in memory. Acceptable for a phone clip, not as the general answer.
- **(B) MSE + fragmented MP4** — feed the demuxer via Media Source Extensions. Rejected: MSE requires a
  **fragmented, streamable** container, and a pristine user upload is whatever the camera wrote — a progressive
  MP4 with its `moov` atom at the tail, a MOV, an MKV. Making arbitrary uploads MSE-feedable means **remuxing or
  transcoding on device**, i.e. the transcode enabler we deliberately do not have. Without a guaranteed fMP4, MSE
  buys nothing here.
- **(C, chosen)** needs no transcode and no MSE: it plays whatever the browser can already demux, streamed, by
  putting the decrypt in the fetch path the demuxer already speaks (HTTP ranges).
- **(C′) the same shim, but with the key in the SW** — hand the SW a per-blob `K_blob` so it can decrypt what it
  fetches, instead of asking the page for plaintext. Rejected. Presign and auth have to stay in the page
  regardless (see the handle, above), so moving the key buys no round trip; what it costs is a **second
  key-bearing context** whose lifetime must be managed and whose key must be purged on vault-lock. The safest
  way to clean up a key in a Service Worker is to never put one there. `K_node` in the SW is strictly worse — it
  opens every blob of the Memory — and was never an option. This is what makes the trust-tier note below
  *stronger* rather than weaker: the SW is operator-served code that holds no confidential key at all.

```mermaid
sequenceDiagram
  participant V as native video demuxer
  participant SW as Service Worker (in-origin, key-free)
  participant P as page (holds K_blob + auth)
  participant B as backend (blind, presign)
  participant O as object store (blind)
  Note over P,SW: before play — page registers {handle, chunkCount, byteSize, MessagePort}
  V->>SW: GET /__media/&lt;handle&gt;  Range bytes=A-B
  SW->>SW: chunks = overlap(A..B, chunk_frame_size) — the vector's frame size
  SW->>P: (port) need plaintext for chunk indices
  P->>B: presign GET (bearer / share-token) — whole blob, cached
  B-->>P: presigned URLs
  P->>O: GET the ciphertext chunks (direct)
  O-->>P: AEAD frames
  P->>P: openFrame(K_blob, index, total = chunkCount)
  P-->>SW: (port) plaintext chunks
  SW->>SW: slice A..B, assemble 206
  SW-->>V: 206 Partial Content, Content-Range, Accept-Ranges bytes
```

### The accepted seek-leak — stated plainly

Range streaming means the client fetches **only the ciphertext chunks the demuxer currently needs**, as it needs
them. The object store — and therefore the operator — **observes which chunks a playing client fetches, and when.
That is an intra-media access pattern**: which parts of a video were watched, re-watched, skipped, or seeked to.

This is **new, not pre-existing**: every current read path fetches a blob *whole*, so today the operator learns
only that a blob was accessed, never *which parts in which order*. Range streaming is what introduces the
per-chunk-subset pattern. It is also **finer than the metadata the invariant already permits** — `CLAUDE.md`
allows *timestamps* and *sizes* — when a blob was written or fetched, and how big it is; a
position-within-one-medium-over-time trace is more granular than either.

**The owner has decided this is acceptable:** the intra-media access pattern is **not** an exclusion criterion for
video playback. The alternatives were weighed and rejected — hiding the pattern would require either fetching the
whole original first (option A's cost, the thing we rejected) or routing bytes through a decrypting proxy (which
would hand the operator the plaintext and break both operator-blindness and self-hostability). Recording the
decision here keeps the **technical** guarantee and the **communicated** guarantee from drifting: operator-
blindness stays the product's central claim, and where we knowingly narrow it, the narrowing is written down.

Because the leak is accepted, **prefetch is a UX dial, not a privacy control.** The SW may read ahead of the
demuxer, and a seek may jump **straight** to its target range — there is no obligation to fetch sequentially to
mask the pattern. Read-ahead depth trades playback smoothness against bandwidth and is free to tune; it defaults
to modest read-ahead. It is explicitly **not** a security parameter.

### The media type stays sealed

The play badge and the `<video>` branch need to know a medium is a video **without** fetching its original. The type
stays sealed — [ADR 0044](0044-field-visibility-declared-at-the-field.md) applied, since a server-visible type would
tell the operator photo-vs-video-vs-PDF for every medium — in the original's blob meta
([ADR 0072](0072-a-seal-binds-its-object.md) Decision 2; [en.memory.media](../features/memory/enablers/en.memory.media.md)
§ Key & blob model), read from the listing without fetching the original. The shim's route keys on an opaque
per-playback handle plus range offsets, which reveals even less about *what* a blob is than its id.

### The shim serves inert responses — ADR 0013 §6 applied

Sealed is not trusted: `mediaType` is written by the **uploader's** client, and the shim hands it back as the
response's `Content-Type` on the app origin. The only gate in front of it was `startsWith('video/')`, which
`video/mp4, text/html` passes — and a browser that opens `/__media/<handle>` as a page (*open video in new
tab*) resolves a listed `Content-Type` to its **last** valid entry (the Fetch spec's MIME extraction; read, not
yet browser-measured), so the decrypted bytes become a document on the app origin without a CSP. The shim
therefore applies [ADR 0013 §6](0013-blob-storage-presigned-direct-to-store.md) to what it serves:

- a medium is playable for the shim only if its type is **one** well-formed `video/<subtype>` or `audio/<subtype>`
  essence — no list, no parameters; anything else never gets a playback handle;
- the shim **refuses navigations** (`request.mode === 'navigate'` → 404): a `<video>` fetch is the only caller
  it has, so a page load of a handle is by definition not one;
- every shim response carries `X-Content-Type-Options: nosniff` and
  `Content-Security-Policy: sandbox; default-src 'none'`.

### The download route

A download must not hold the whole decrypted file in one `Blob` in tab memory (inbox #371). So the page registers a
download with the SW (an opaque one-shot handle, the plaintext size, the file name and a `MessagePort`), then navigates
the tab to `/__download/<handle>`; the SW answers that navigation with an attachment whose body pulls the page's
decrypted chunks over the port, one credit per chunk the browser consumed. The browser writes the file itself, so the
tab holds a few chunks, not the file. The SW still holds no key and no token; the page presigns and opens every frame,
as for playback. It is the second of three save targets: a file picked through the File System Access API comes first
where the browser has it, an in-memory `Blob` with a cap last ([en.memory.media](../features/memory/enablers/en.memory.media.md)
§ Reading back: playback and download). Above the cap, with neither streaming target, the download is refused with a
message. Rejected: a "stream saver" library, because its
man-in-the-middle iframe is served from another origin by default (a self-hostability regression) and the app's CSP
allows no frame at all (`frame-src 'none'`).

This route is the first one on the app origin that a **navigation** reaches, the exact case the playback shim refuses.
So it is held to the inert rules above and to more: it answers **only** a `GET` navigation (a `fetch()` of a handle
is 404); the handle serves **one** navigation and expires soon if unfetched; the response is
`Content-Disposition: attachment` with a sanitised `filename` and the exact name as `filename*=UTF-8''…` (controls,
CR/LF, bidi overrides and path separators removed), `application/octet-stream` whatever the file is, `nosniff`, the
sandbox CSP, `Cache-Control: no-store` and the exact `Content-Length`. A handle the SW does not hold answers **204**,
which leaves the tab where it is (a 404 would replace the page holding the keys with an empty document). A page
failure, a stream that ends short of or runs past the registered size, and an ended session **error** the body, so
the browser marks the download failed and never keeps a truncated file as if it were whole; a cancelled download
reaches the page as an abort. Every end reaches the page: the SW confirms a clean end, so the page's close resolves only
then, and reports a stream it refused (short, overlong, or out of protocol), so the page's pending write or close fails
instead of waiting for a credit that never comes — a refusal the page never hears of leaves a download hanging at
100 %. The registered size is the exact length of what the page streams, so a caller that builds the bytes, as a ZIP
download does, announces the built file's length, never its payloads' sum (ADR 0082).

Every application saves through it, and names a file by core's one rule: the name sealed in the original's blob meta
at upload, else one derived on device, its extension from the blob meta's type. The name is known synchronously, so
nothing is read before the save target opens: the picker is always the click's first await, which a name that waited
on a lazily fetched tier (or on a sniff of chunk 0) would break.

**The page and the worker speak a versioned protocol**, so a still-controlling worker that predates a change fails the
handshake and the page falls back as it does without a worker. Every caller — the lightbox, a banner, Depot's file
page, which registers a file's sniffed container and never its claimed type — registers through the same page-side
chunk provider; the worker holds no key and no plaintext at rest for any of them.

### The small playable copy

The shim plays whatever was uploaded; a long or large original streams at its own bitrate. A small playable copy of a
video is made later, by a device that holds the key
([en.memory.playback-derivative](../features/memory/enablers/en.memory.playback-derivative.md); a banner's later slot is
[ADR 0102](0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md) decision 2):

- **On a key-holding device, in the client worker queue** ([ADR 0021](0021-operator-blind-background-tasks.md)).
  Rejected: on the server — it holds no plaintext (operator-blind), and a transcoder would be a heavy dependency on a
  small self-hosted instance; in the editor's browser tab — a long video is a long job there, and the worker queue
  serves the gallery as well.
- **One fixed target, not a ladder:** H.264 (the codec every engine plays), long edge 1920, about 5 Mbit/s, the source's
  frame rate capped at 30, the sound kept where the source has it. The size is the lightbox's, as an image's preview is (2048): the gallery plays the copy and fetches the original
  only to download it ([ADR 0132](0132-a-content-job-runs-on-a-members-own-device-and-adds-its-result-to-the-object-later.md) decision 9; owner 2026-11-13: 1920, not 1280, which looks soft full-screen). Rejected: adaptive streaming — the copy streams
  through this shim as any blob does, so a ladder buys nothing but storage.
- **Additive, never a replacement in place.** The copy goes into a slot of its own beside the original; a reader that
  does not know it plays the original. Rejected: writing the copy into the original's slot — every reader that
  predates the copy would then find other bytes than the slot promised.

Which videos get one, and when, is [ADR 0132](0132-a-content-job-runs-on-a-members-own-device-and-adds-its-result-to-the-object-later.md) decision 9: every video, made after its upload by a worker of the
queue, stored videos too; the gallery then plays the copy and fetches the original only to download it, as for an image.
Still open: whether a banner's original is dropped once its copy exists.

## Consequences

- **Trust tier is unchanged.** The SW is operator-served code on a non-self-hosted instance, so it sits at the
  same client-integrity tier as the rest of the web client ([ADR 0026](0026-client-trust-tiers.md)) — it does not
  move the active-operator boundary. Confidentiality of already-stored bytes still holds; the operator still
  cannot read content, only observe the (now finer) access pattern above.
- **The security model says it**, so its *timestamps and sizes* wording is not read as stronger than it is: the
  per-chunk fetch pattern of a *playing* medium is observable — an accepted intra-media access-pattern leak, scoped to
  range-streamed playback ([security-model](../crosscutting/security-model.md)).
- **No new crypto and no contract change for the type.** Playback reuses `K_blob` and the frame opening verbatim; the
  type rides an already-opaque seal (the original's blob meta). The shim adds presign plumbing but no new key.
- **Native surfaces.** A Service Worker is a web-only mechanism; a future native client feeds its own player the
  same decrypted ranges through the platform equivalent. This ADR governs the **web** playback path (the current
  V1 priority); it does not bind native to a SW.
- **A download is whole-blob and sequential**, so it adds no access pattern beyond what a whole-blob read already
  shows. The page must stay open while it runs, since it holds the key.

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md).
