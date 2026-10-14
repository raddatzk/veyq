# Application: Files

> **Built.** The platform's second application: a file tree on the platform's node tree
> ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)) — the Gradle module
> `:apps:files` (`me.raddatz.veyq.files`, depending on `:core` only), its contract `contracts/api/files/`, and
> `web/src/features/files`. What a person does with it, and the tests that hold it, are its use-cases:
> [uc.files.member-files](../features/files/use-cases/uc.files.member-files.md),
> [uc.files.share-folder-files](../features/files/use-cases/uc.files.share-folder-files.md),
> [uc.files.share-file](../features/files/use-cases/uc.files.share-file.md),
> [uc.files.drop-box](../features/files/use-cases/uc.files.drop-box.md). This page is how it works.

## What it is

- A **file** is a node kind that holds **one blob**, with its name and metadata sealed. Directories are the platform's
  `FOLDER`: every node carries a key and a `FOLDER` can be a share root with `READ` or `READ_WRITE`
  ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md), built), so Files needs
  no directory kind of its own, and a directory can be shared whole. A file node carries its own key like every node,
  and Files declares it a share root with `READ` or `READ_WRITE` (never `WRITE_ONLY`): a file is shared through its
  folder above it, or **on its own** (§ A single file as a share root). Files live in the scope's one folder tree, beside
  Memories.
- **Web only.** No sync client, no versioning: replacing a file replaces its blob.
- A **drop box** (write-only) is a node of its own with one guest folder per identity (ADR 0080 decision 11,
  § The drop box); a `WRITE_ONLY` link on a plain `FOLDER` stays refused. Viewer and
  contributor shares carry over from the platform; what a guest may do with a `FILE` is the role matrix in § Guests
  (ADR 0080 decision 6; no declaration allows nothing).
- Rides on the platform's blobs, quota and trash; names sit under the parent's key, `K_SC` only at the scope root.

## How a file is stored

- **The node** is core's: a `FILE` node with its sealed name and slug (`fileNameSlug`, case-folded) and its random key
  wrapped under its parent's. Its NAME is the file's name — a rename changes it, a replace keeps it. Files writes
  nothing into the node's generic `wrappedMeta` (inbox #397; it used to hold the file's type, size and date). Files
  adds no column and no table.
- **The blob** is a core attachment: the slot `FILE_CONTENT` of `veyq_node_attachment` (one blob per slot), its
  role the same token. Its `K_blob` is wrapped under the file node's key and sealed bound to the blob id, the node id
  (`ownerId`) and the role ([ADR 0072](../decisions/0072-a-seal-binds-its-object.md), ADR 0080 Decision 13), so no
  new seal purpose exists for Files.
- **The metadata is the blob's** (inbox #397): the picked file's type, its name and its modified time
  (`{type, name, modifiedAt}`, `uploadFile.ts` `fileBlobMeta`) are sealed under the blob's own `K_blob` with core's
  `blob-meta` seal at the upload's declare (`planBlob`'s `meta`, `wrappedBlobMeta` on the start — member and guest
  alike), stored write-once beside the blob and served back on every `FileBlobRef`. So the metadata changes exactly
  when the bytes do, in the one attach transaction: a replace is a new blob with its own metadata, and no state shows
  new bytes beside old metadata. The **size** is not in it: the plaintext size is the blob's sealed size less 28 bytes
  per chunk (`plaintextTotal`), exact. The sealed name is the name the file was *uploaded* under; what a member sees and
  downloads under is the node name (`core/blob/meta.ts` `downloadName`: the node name, else the sealed name, plus the
  sealed type's extension when the name has none). A blob uploaded before the field carries no metadata and lists
  with its size alone.
- **Trash and purge** are core's: deleting the node trashes it with its attachment (the quota still counts it, ADR
  0073 §2); the purge frees the attachment row and the blob through core's eraser.
- **The quota:** a member's upload counts against the tenant quota through core's start, unchanged. That start takes
  only a role some application declares to core's `MemberBlobRoles` (Files: `FILE_CONTENT`, `FilesMemberBlobRoles`) and
  refuses any other whole, `400 invalid_request`, before it reserves anything (inbox #366); a `FILE_CONTENT` start
  names no node, so one for an object that is no `FILE` still opens and ages out unattached. A link's usage
  for a `FILE` is core's attachment term; Files' `UploadAdmission` reports 0 for it (core never admits a link by its
  own terms alone).

## The contract (member, `contracts/api/files/files.openapi.yaml`, tag `files`)

- `completeFile` — `POST /api/scopes/{scopeId}/files/{nodeId}/content`, body `{uploadId, expectedBlobId?}`. The upload
  was started with core's `startBlobUploads` (`role: FILE_CONTENT`, `ownerId` = the node). Without `expectedBlobId`
  it fills an empty slot; with it, it replaces exactly that blob (a compare-and-set; the replaced blob is reclaimed in
  the same transaction). `200` the attached `FileBlobRef`; `409 stale_file` with the slot's `currentBlobId` when it
  holds anything else (the upload is spent and reclaimed); `404 node_not_found` (no live node — trashed, another
  scope's), `404 upload_not_found`, `422 illegal_target` (not a `FILE`), `422 upload_mismatch` (started for another
  node or role), `400 budget_exceeded`.
- `listFiles` — `GET /api/scopes/{scopeId}/files?parentId=` (absent: the scope root): `{items: [{nodeId, fileBlob|null}]}`
  for every live `FILE` directly under the parent, ordered by node id, each `fileBlob` with its `wrappedBlobMeta`. Names
  and keys come from core's node listing; the client joins by `nodeId`. `404 node_not_found` for a parent that is no live node of the scope.
- **A download** is core's `presignBlobDownloads` with the blob id (scope READ). **Delete** is core's node delete
  (trash). **Replace** is another `completeFile` with the blob the client read.
- **Live.** An open Files level learns of a peer's file without a reload from core's conditional node listing
  ([ADR 0081](../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)). Files writes no revision of
  its own: a `FILE` created or deleted (trashed) is a node change, and `completeFile` — member or guest, first upload or
  replace — is `AttachmentStore.attach`, which bumps the scope's tree revision in the transaction that fills the slot.
  An upload completing changes no node row, so the level's row factory answers a *moved* poll (`liveTick`) by re-reading
  `listFiles`, and answers nothing to a `304`. A guest's `resolveShare` tag covers the attachments of the served nodes
  for the same reason.

Backend: `me.raddatz.veyq.files.content` — `FilesResource`/`FilesHandler` (the scope-access gate first), core's
dispatcher to `FilesUploadCompletion` (`UploadCompletion` for `FILE`, maps the item's `expectedBlobId` payload onto
`AttachmentStore.attach` — the whole write, nothing beside it), `FilesUploadAdmission`, `FilesStore` (the listing's
reads).

## The web (`web/src/features/files`)

- **Files' rows** in core's ONE level list (`levelRows.ts`, the row factory both kinds name as `list.rows`; inbox #407 —
  there is no Files section any more, core draws the list): a FILE is a row or a tile with the leading file icon, the
  node name and the meta line "File" plus the details (`details`, [apps/README](README.md#how-an-application-shows-facts-in-a-levels-rows-and-tiles)) the plaintext size the blob's sealed size gives and the modified time from the blob's
  sealed meta (`download.ts` `fileFacts`; the only modified time Files has is that sealed one - `FileBlobRef` carries no server-observed `updatedAt`; a blob without meta, or one whose meta does not open under this node's key,
  shows its size alone; a file listed without a blob says "not uploaded yet" — none of it reads the node's own payload).
  Its **primary click opens** the file's own page (`FileDetail`); **download is its one first-class action** (a button on
  the row, beside core's own favorite heart, which every kind's row has - `core/favorites`, ADR 0068 §10), and **rename** (core's `FolderNameDialog`, through the host's `rename`), **move** and **delete**
  (into core's trash, restored from core's trash dialog — or at once from the "moved to the trash" toast's Undo, core's
  `trashUndo.ts`, inbox #385 (c)) sit in the row's one "more" menu, each `shown` by `filesCapabilities` narrowed to the
  doors the session's source carries (fail closed: a guest gets the same row with fewer actions). **A row has no
  replace action** (owner decision 2026-10-14): in a folder, a file's content is replaced by uploading a file of the
  same name, which asks first (below), for a member and a contributor guest alike; an upload of that name also fills a
  file that holds no content. **The opened file keeps its replace** — the one way a `READ_WRITE` link on a single file
  writes, which has no folder to upload into — as an action of its header beside the download (core's `NodeHeader`;
  owner decision 2026-10-14: both used to be buttons below the header), with no standing sentence about what a replace
  does: the replace asks when it happens. Files, Memories and
  drop boxes are listed together, folders first and then everything by name (core's order); the tab "Files" filters the
  list to FILE nodes, the folders stay. **Upload** is a level action (`files.upload`, the "New" menu's "Upload files"),
  registered with the level by the factory for the level's whole life — so it is available under every tab — and core
  hands it the files dropped onto the level, only while the action is allowed and enabled. Hidden in map mode like every
  row (the map is Memories' presentation, drawn where a shown tab offers it; a guest's identity folder without Memories
  lists its files in the grid, inbox #404). An empty Files tab says so in Files' words (`files.empty` where an upload is
  possible, `files.emptyReadOnly`, `files.guest.empty`). Read-only during a pending deletion: download only. The same
  rows are a share guest's (§ Guests).
- **The upload** is one job of core's upload queue per file (`uploadFile.ts`): create the `FILE` node (or, for a name a
  FILE sibling already holds by `fileNameSlug`, replace that file after the uploader confirmed (`files.replaceConfirm`,
  for a member and a contributor guest alike — the one way a row's file is replaced, owner decision 2026-10-14); the
  opened file's replace asks too, naming the file and the picked one, and both questions say the old content cannot be
  restored, there being no version history (owner decision 2026-10-08, inbox #383); a file that holds no content is
  filled without the question, and "replace anyway" after a peer's replace is itself the answer and asks nothing), plan
  and pump the blob
  under the node's own key with the file's metadata sealed under its `K_blob`, then `completeFile` with
  `expectedBlobId` — nothing is written after it. The status menu shows progress; a final refusal (`slug_taken`, `illegal_structure`, `stale_file` — the job's `isFinal`, core's queue) fails the item at once and the menu offers no retry for it; the level shows, above the list, why a file of its
  folder was not uploaded (a taken name, `stale_file` with "replace anyway", no space). **A name another kind holds is
  not sent** (inbox #400): before anything is asked, every picked name is folded by the file's function and each
  sibling's by its own kind's (core's `collidingSibling`, the dialogs' pre-check), and one that meets a folder, Memory
  or drop box is skipped with a notice naming that sibling by its kind (core's `nameTakenMessage`) — for a guest too, who
  is then not even asked to name itself; a server `409 slug_taken` for such a sibling (the race backstop) is named the
  same way. **A given-up job cleans up**
  (inbox #384): failed after its
  retry or cancelled (core's queue calls the job's `abandon`), a job moves a node it created to the trash once
  `listFiles` shows it still holds no content — best effort; a node it cannot trash stays and its retry fills it. A file
  that holds no content anyway (an upload cut off, e.g. a closed tab; a guest's, which a guest cannot delete) is filled
  by a picked file of its name without the replace question (its opened page offers an upload into it); in core's
  trash such a file (core's `attached` false on the trash row, Files' `emptyInTrash`) says it holds no content — its
  restore stays offered (inbox #391). A failure the same file has since been uploaded over is no longer shown. A
  replace keeps the file's name (the opened file's action says so in its tooltip); a rename selects the name without its extension (`register.ts`
  `renameSelectionEnd`).
- **The download** (`download.ts`) saves under core's `downloadName` (the node name, else the blob's sealed name, plus
  the sealed type's extension when the name has none — the save target itself stays `application/octet-stream`, so a
  peer's sealed type never decides how a browser renders the bytes), presigns the blob's chunks, opens them under the
  node key at most four at a time and streams them in order (`core/blob/stream.ts`) into the save target `shared/saveStream.ts` opens: a file the member
  picks (File System Access, where the browser has it), else the Service Worker's download route
  ([ADR 0047 § Addendum](../decisions/0047-inline-video-playback-range-streaming.md#addendum-the-download-route)),
  else an in-memory `Blob` up to 512 MiB — above that, with no streaming target, the download is refused with a
  message rather than built as a multi-GB Blob. Proven in unit tests only; no real browser has streamed a large file yet
  (inbox #371).

## The viewer (`FileDetail` → `FileViewer`, inbox #412)

**Files makes no thumbnail and no preview blob** (a Memory keeps its derived tiers; a cover for a file is a different
idea, inbox #411). The file's own page reads the file when it is opened — never for a list row — and shows it above its
facts, in the page or full screen. It is the same component for a member and for a share guest of either role (a
share looks like the member view, inbox #401); it reads through the source's `chunks()` (the member's scope READ gate,
a guest's link presign) with the chunk provider the download uses (`fileChunkProvider`).

| What the bytes are | Shown as | Cap |
|---|---|---|
| JPEG, PNG, GIF, WebP, AVIF, BMP, SVG (HEIC is tried) | `<img>` on an object URL typed with the **sniffed** type (`useObjectUrl`, revoked on replace/unmount) | 64 MiB, then the download |
| text: valid UTF-8 (BOM dropped) or UTF-16 with a BOM, no NUL or other control byte, and a text-ish type or extension | `<pre>` through `textContent`, monospace, wrapping (a toggle), selectable, with a Copy button | first 1 MiB, a note says so; Copy copies what is shown |
| MP4, MOV, WebM, Matroska, Ogg, MP3, AAC, WAV, FLAC, M4A | `<video controls playsinline>` / `<audio controls>` on the service worker's `/__media/<handle>` ([ADR 0047](../decisions/0047-inline-video-playback-range-streaming.md); the page holds the key, the worker none) | none: streamed |
| PDF (`%PDF-` in the first KiB) | drawn page by page onto `<canvas>` elements by pdf.js, in the page (below) | 64 MiB and 1000 pages, then the download |
| anything else, a text in another encoding, an image the browser cannot decode (`<img>` `error`), a codec it lacks, no usable worker, an unreadable PDF | message + download | — |

- **The bytes decide** (`core/blob/sniff.ts`, `view.ts`): the first chunk is opened and sniffed; the blob's sealed type
  and the name only *narrow*. A PNG claimed as `text/plain` is an image; a binary claimed as `image/png` shows nothing;
  an SVG is an image only when its markup (first element `<svg`) **and** the type/extension say so — any other markup is
  text. Legacy 8-bit encodings are not guessed (they are not valid UTF-8, so the file is not shown as text).
- **Security rules.** Never render untrusted bytes as HTML or script: text is `textContent`, an image an `<img>` (an SVG
  too — never inline, never in `<object>`/`<iframe>`), blob URL types are the sniffed ones, and the media route sets
  `Content-Type` from the sniffed container (protocol 4 accepts `audio/*` besides `video/*`, same one-essence rule).
  Nothing is logged, no new network origin is used, and the object URL, the playback handle and the read in flight
  (an `AbortSignal` into the stream pump) are released on a new file, on navigation and on unmount. A blob URL document
  opened by the user in a tab inherits the app's CSP (`script-src 'self'`), so an SVG's script cannot run there either
  (remembered from the specification, not measured in a browser).
- **Memory.** Per-kind caps above; a file of another kind is read no further than its first chunk. Images and text are
  collected chunk by chunk (four frames in flight) into one `Blob`/string.
- **A PDF is drawn inline by pdf.js, not by the browser's viewer** (owner decision 2026-10-12). The browser's own viewer
  needs an `<iframe>`/`<object>` on a `blob:` URL, and the app's policy is `object-src 'none'; frame-src 'none'`
  (`web/src/shared/security/csp.ts`, [cc.security.csp](../crosscutting/cc.security.csp.md)); a viewer frame would also
  be a same-origin `blob:` document next to the keys. So the file is decrypted whole (cap 64 MiB, one buffer allocated
  at its size) and handed to **`pdfjs-dist`** (Mozilla, Apache-2.0, the *legacy* build) as `{data: Uint8Array}` — never
  a URL, so pdf.js opens no connection for the document. **The policy is unchanged** (the built header is pinned by
  `verify:csp`).
  - **Lazy.** `pdf/pdfEngine.ts` is the seam (`openPdf` -> `PdfDocument`: page count, page size, `render` onto a canvas,
    `destroy`); it reaches pdf.js through a dynamic `import('./pdfjsRuntime')`, so the library (~0.5 MB) and its worker
    (~1.3 MB) are a chunk of their own that loads on the first PDF opened, never before. The worker is a hashed
    same-origin asset (`pdfjsRuntime.ts` imports it with `?url`; `vite.config.ts` emits it as `assets/pdf.worker.min-<hash>.js`,
    a `.js` name so the backend's static handler serves a script MIME type to a module worker under `nosniff`), which is
    what `worker-src 'self'` admits. `npm run verify:pdf-worker` (in `npm run check`) fails the build unless the worker and
    the runtime chunk exist, the runtime names the worker, the runtime is reached by a dynamic `import()` and by no static
    one, the entry chunks hold none of pdf.js, neither file contains `new Function(`/`eval(`, no `.wasm` shipped, and the
    runtime data below is emitted.
  - **What is switched off, and why** (`pdf/pdfOptions.ts`, one pure module read by the runtime and the tests):
    `useWasm: false` — pdf.js would use WebAssembly for the JBIG2 and JPEG 2000 image decoders, ICC colour transforms and
    PostScript functions, and compiling WebAssembly needs `'wasm-unsafe-eval'`, which the policy does not grant; the `.wasm`
    files are not shipped and no `wasmUrl` is set. **Consequence, stated:** a scanned page whose image is JBIG2 or JPEG 2000
    (common in scanner output) shows that image blank or with gaps, and ICC-profiled colours fall back to their alternate
    colour space. `enableXfa: false` (XFA forms are XML + script). **No scripting:** pdf.js 6 runs a PDF's JavaScript only
    for a viewer that mounts its scripting manager and loads `pdf.sandbox.mjs`; this one does neither. There is no
    `isEvalSupported` option any more in pdf.js 6 and no `new Function`/`eval` in the emitted files (checked by
    `verify:pdf-worker`). `disableRange/Stream/AutoFetch` (they concern a URL; stated), `verbosity: 0` (no document
    structure in the console), `maxImageSize` 32 M pixels per decoded image, and the page canvas is capped at 16 M pixels
    (a page is drawn at a lower resolution rather than allocate more).
  - **No network but our own origin.** pdf.js fetches predefined CMaps (CJK and other non-embedded encodings), the
    standard fonts' substitutes and the CMYK ICC profile by URL from a directory; `vite.config.ts`'s `pdfjsAssetsPlugin`
    copies `cmaps/`, `standard_fonts/` and `iccs/` to `assets/pdfjs-<version>/` (versioned path, so the backend's
    `/assets/` immutable caching is safe) and `connect-src 'self'` carries the requests. No origin is added.
  - **The page.** `FileViewer` mounts `PdfPages`: a vertical scroll of every page as a placeholder, drawn only while near
    the viewport (an `IntersectionObserver` with a one-viewport margin; each page is drawn at the container's width times
    `devicePixelRatio`, at most 3), and its canvas is given back (resized to 0 x 0) when far away, so a long PDF holds a
    handful of bitmaps. "Page n of N", and zoom out / zoom in / fit width (50 % to 400 % of the fitted width) are labelled
    buttons (`files.viewer.pdf.*`, DE + EN); the scroll area takes focus, so the arrow and page keys scroll. The same
    component serves the member and both guest roles. Full screen is the viewer's, unchanged.
  - **Text layer (select and copy).** Over each drawn canvas `PdfPages` has a host `<div class="pdf-text-layer">`; the
    engine's `render(page, canvas, cssWidth, pixelRatio, textHost)` fills it with pdf.js 6's `TextLayer`
    (`new TextLayer({ textContentSource: page.streamTextContent({ includeMarkedContent: true }), container, viewport })`,
    `viewport = page.getViewport({ scale: cssWidth / pageWidth })` - the CSS scale, not the canvas' device scale, and
    `await layer.render()`): transparent, absolutely positioned `<span>`s the browser selects like any text (mouse, touch,
    Shift+arrows, Ctrl/Cmd+C; browser find sees the pages that are drawn). It is built **with the page's drawing**, so
    there is exactly one per drawn page; it is emptied (`replaceChildren`) when the page is freed together with the
    bitmap, rebuilt when the zoom or width changes (the same host, emptied first), cancelled with the drawing
    (`job.cancel()` cancels both) and `TextLayer.cleanup()` runs when the document is destroyed. A failure to read a
    page's text leaves an empty layer and the page drawn; a scan without a text layer is simply empty. The host is sized
    in px as the raw (unrotated) page at the current zoom (`viewport.rawDims`, the plain-px stand-in for the CSS `round()`
    pdf.js writes) and a page with `/Rotate` is turned into place by `[data-main-rotation]` rules - found wrong by a real
    browser on the first try. The few rules it needs are in `styles/pdf.css` (tokens, two-class selectors: the spans get
    `color: transparent`, `user-select: text`, `font-size`/`transform` from the custom properties pdf.js sets, the selection
    colour from `--app-accent`; the stylesheet pdf.js ships is not imported, so no `new` CSS file and no origin). The
    `.pdf-text-end` element pdf.js' own viewer appends keeps a drag from selecting the whole page (`.pdf-text-selecting`,
    toggled on `pointerdown`/`pointerup`). `style-src 'self' 'unsafe-inline'` carries what pdf.js sets through the CSSOM.
  - **What a PDF can never do here.** No `<iframe>`/`<object>`/`<embed>`, no link, button or form element is ever in the
    page: there is **no annotation layer** (a link in a PDF is ink on a canvas, not an element — it cannot navigate the
    app or open a URL; annotations that carry an appearance are drawn into the page), and the text layer is `<span>`s
    only - no `<a>`, no handler, nothing focusable. A link's text can be selected; clicking it does nothing.
  - **A protected PDF asks for its password** (`useFileViewer` state `password`). `openPdf(bytes, signal, onPassword)`
    keeps ONE pdf.js loading task and answers pdf.js' `onPassword(update, reason)` through the prompt (`reason` 2 =
    incorrect: `wrong`); the document bytes stay in pdf.js, no second copy is made. The viewer shows a `<form>` with a
    labelled `<input type="password" autocomplete="off">` (focused when the prompt opens and after a wrong answer, Enter
    submits, Escape cancels), "Open" and "Cancel"; a wrong answer is an inline `role="alert"` error ("Wrong password, try
    again") and the prompt stays; the right one shows the pages; Cancel (or Escape) resolves the prompt with `null`, the
    engine destroys the task and throws `PdfOpenError('password')`, which is the earlier statement and the download. **The
    password is a function argument and a resolved promise value and nothing else**: it is not in the viewer's state (only
    `{ wrong, busy }` is), not logged, not stored, not sent; the field is cleared the moment it is submitted, when the
    prompt closes and on unmount; a prompt raised by a superseded or unmounted open is declined at once. `files.viewer.pdf.password*`
    (DE + EN).
  - **Limits and follow-ups** (inbox #412): there is no search across pages that are not drawn (browser find sees the
    drawn ones); no annotation layer (live links, form filling - both need an owner decision: a link is a navigation and
    phishing vector, a PDF must not navigate the app); the WASM decoders stay off until the owner decides on `'wasm-unsafe-eval'` (the asm.js fallbacks pdf.js ships next to
    them could be served same-origin instead — not done). More than **1000 pages** is not shown (a message and the
    download): the page tree is a parse cost and a placeholder per page a DOM cost. A page that fails to draw says so
    in its place and leaves the others. Everything pdf.js holds (the document, its pages, its worker) is destroyed on a new
    file, on navigation and on unmount, and a document that finishes opening after the page moved on is destroyed at once.
  - **Tested:** the viewer's wiring with the engine mocked (`FileViewer.pdf.test.ts`: states, lazy drawing with an
    `IntersectionObserver` stub, zoom, destroy on unmount/replace, guest parity); the real library through our engine in node
    (`pdfEngine.real.test.ts`: page count, page size, a broken and an over-long PDF, the password flow - need / wrong / right /
    declined - on an RC4-encrypted fixture, and `getTextContent()` of a plain and of an ENCRYPTED page; `pdfEngine.text.test.ts`
    and `pdfEngine.password.test.ts` pin our side of the text layer and the prompt against a fake pdf.js; `FileViewer.pdf.test.ts`
    also: one text layer per drawn page, torn down with it, rebuilt on zoom, spans only, the password never in the DOM, the
    state, storage, the console or the network; `FileViewer.text.test.ts`: Copy, truncated copy, no-clipboard fallback, wrap
    toggle, and a real-DOM check against the stylesheets that nothing sets `user-select: none` on the text or the layer); and
    `verify:pdf-worker` on the build. **Proven in a real browser** (2026-10-12, a local server serving the built `dist` with
    the built `content-security-policy.txt` as the header): the built pdf.js chunk and its worker ran, three pages of a PDF
    drew with text, a non-embedded standard font was fetched from `assets/pdfjs-<version>/standard_fonts/`, a broken PDF was
    refused with `InvalidPDFException`, and no `securitypolicyviolation` fired. **Proven in a real browser too** (2026-10-12,
    the real `FileViewer` on the Vite dev server, which sends the real policy header, over a fake chunk provider): a
    3-page PDF's text selected by a real mouse drag and a select-all, the highlight aligned with the drawn glyphs at 100 % and
    200 % and on a `/Rotate 90` page, a click on a link annotation's text navigated nowhere (the hit element was a `<span>`),
    an RC4-encrypted PDF asked for its password, refused a wrong one inline, opened with the right one and its text layer read
    the encrypted page, Escape offered the download, the password was in no DOM node afterwards, no `securitypolicyviolation`;
    the plain text's Copy fell back correctly where the pane refuses the clipboard API. **Not yet proven:** the Vue viewer on
    a real file page behind the app (scroll in a long document, full screen, the observer's margin), a phone, the clipboard
    API's success path in a browser that allows it (unit-tested with a stub only), and the dev server's serving of the runtime data.
- **Full screen.** A button toggles the Fullscreen API on the viewer's container (Escape leaves, which the browser
  handles); where the API is missing or refused (an iPhone's Safari has no element fullscreen) a fixed-position overlay
  stands in, left by the button or Escape.
- **Not proven in a real browser yet:** video/audio through the worker for a FILE, fullscreen, mobile Safari, HEIC, and the
  Vue PDF viewer on a real file page (the pdf.js chunk itself is: § above).

## Guests (a folder share)

A file is shared through a `FOLDER` link above it, `READ` or `READ_WRITE` (or on its own, § A single file as a share
root). The role matrix (ADR 0080 decision 6):

| A guest of a … link | may |
|---|---|
| `READ` | list the files of the shared subtree, download them |
| `READ_WRITE` | the above; create a `FILE` under a folder of the subtree (core's `createShareNode`; core's `FolderGuestNodeRules` lets any kind `NodeKindRules` admits into a folder); upload into a file below the root, first or replacing its blob (any file below the root, a member's too); rename or move it (core's `updateShareNode`) |
| any | **not** delete a file (nodes store no authorship), **not** upload into a `FOLDER` |

**What the web offers.** One view per kind serves a member and a guest (ADR 0079 Consequences, inbox #401): what it
shows is `filesCapabilities(access, target)` (`web/src/features/files/capabilities.ts`, pinned row by row in
`capabilities.test.ts`) — pure, every right false unless a row grants it, an unknown role or kind grants nothing:

| Right | member | member, pending deletion | member, guest's identity folder | `READ` | `READ_WRITE` | `WRITE_ONLY` |
|---|---|---|---|---|---|---|
| download (a folder's files; a drop box's guests' folders; an opened file) | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| upload, and replace by uploading a file of the same name | ✓ | ✗ | ✗ | ✗ | ✓ (not in an identity folder, not in a drop box below the link) | ✓ on its own drop box only |
| rename a file | ✓ | ✗ | ✗ | ✗ | ✗ (the server allows it; offered nowhere yet) | ✗ |
| move a file (core's move dialog, inbox #358) | ✓ | ✗ | ✓ out of it (inbox #377 (a)) | ✗ | ✓ between the subtree's folders (not in an identity folder) | ✗ |
| move a guest's folder out of a drop box, move the drop box | ✓ | ✗ | — | ✗ | ✗ (a drop box below the link is read-only, #380) | ✗ |
| delete a file, a guest's folder | ✓ | ✗ | ✓ (inbox #391) | ✗ | ✗ | ✗ |
| the **opened file** (`FileDetail`): replace, a header action | ✓ | ✗ | ✗ | ✗ | ✓ (not in an identity folder) | ✗ |
| the opened file: rename, share, delete, move | all ✓ (share with the instance's public links; not in an identity folder) | share only (links are a read) | delete, move only | ✗ | move only, between the folder link's folders (never the link's root, not in an identity folder) | ✗ |
| create a drop box | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| a drop box's links, delete it | links with the instance's public links; delete ✓ | links only | — | ✗ | ✗ | ✗ |

These are the matrix half; a right shows only where the session's data source also implements it
(`web/src/features/files/source.ts`, `sourceCapabilities`: the guest's source has no delete and no rename, and no upload
or move unless the link is `READ_WRITE`), so the guest's columns are the most a guest page can ever offer. A move is
core's (`core/node/MoveNodeDialog.vue`, the host's): the row's "Move to…" asks the host through the row factory's
(or the detail's) `move` event, and the dialog's folder browser (`MoveBrowser.vue`, one place at a time with "Move
here") lists only places that take what `FilesNodeKindRules` takes — a file or a drop box at the root or in a folder
(the kinds' `placement`), never into a drop box or a guest's folder.

- **The contract** (tag `share-files` in `files.openapi.yaml`, no auth, behind core's `ShareGuestGate`):
  `listShareFiles` — `GET /api/shares/{shareId}/files`: `{items: [{nodeId, fileBlob|null}]}` for every live `FILE` of
  the live shared subtree (a `WRITE_ONLY` link: empty), each blob with its `wrappedBlobMeta`, names and keys coming
  from core's `resolveShare`; `completeShareFile` — `POST /api/shares/{shareId}/files/{nodeId}/content`,
  `{contributorToken, uploadId, expectedBlobId?}`: `completeFile`'s answers, plus `403 share_not_contributable` for a node that is no
  `FILE` (a folder) and `404 node_not_found` for one outside the live subtree. The upload itself is core's guest
  transport (`startShareBlobUploads` with `nodeId` = the file, `role: FILE_CONTENT`; any other role `400
  invalid_request`, refused before the link's upload budget is spent, inbox #367); the download core's
  `resolveShareBlob`.
- **The meta goes with the bytes.** A guest's file metadata is its blob's, as a member's (§ How a file is stored):
  sealed under `K_blob` and declared at `startShareBlobUploads`, so it lands only when the swap happens, and the guest
  writes no node payload at all (it has no door to one: core's guest create takes none, `updateNode` is a member's).
  `completeShareFile` used to carry a node meta that a Files-side write put in the attach's transaction; both are gone.
- **The write is decided again under the lock** (`ShareGuestGate.admittedInto`): a file trashed or moved out of the
  subtree, or a link revoked, between the guest's start and its completion refuses it (`upload_not_found`).
- **Trash:** a file in the trash — or under a trashed folder — is not listed, its blob is `404` at `resolveShareBlob`
  (core's attachment contributor authorizes live nodes only), an upload into it `404 node_not_found`.
- **Quota:** a guest's attachment names its identity, so the link's usage counts it (core's attachment term; Files'
  `UploadAdmission` reports 0) against the tenant's per-link limit; core's `AttachmentGuestIdentityUsage` keeps the
  guest's identity while a file names it (Files stores no row of its own naming a guest, so it ships no usage).
- **The web:** one view per kind for a member and a guest (inbox #401 S3). Core's page of a public share
  (`core/sharing/ShareTreePage.vue`) is core's member `LevelView` handed the guest's access and tree, so the files and the drop boxes are
  the member's rows (`levelRows.ts`); a drop box below the link opens
  the member's `DropBoxDetail.vue` (§ The drop box). Every read and write of those views goes through Files' data source
  (`source.ts`): the guest's lists through `listShareFiles`, downloads through the link's presign (`shareChunkUrls`)
  and, for a contributor, uploads one core-queue job per file (`shareUpload.ts`: guest create → guest transport with
  the blob meta → `completeShareFile`) and re-resolves the link afterwards (`access.reload`). So a guest sees the
  member's rows — name, size and date opened under the chain's keys, the download — and a contributor the upload. No
  row offers a replace, for a guest as for a member. No standing sentence above the list (removed by the
  owner 2026-10-14): the link's role badge says a contributor can upload over any file, and the replace itself asks. An upload of a name a file there already holds
  **replaces that file after the same question a member gets** (owner decision 2026-10-14, reversing inbox #392's
  "no replace by upload for a guest"): the guest's source turns it into a replace as the member's does, through the
  guest's replace door (an entry with `replace`, `shareUpload.ts`). **Every replace asks first** (owner decision
  2026-10-08, inbox #383) — after the file is picked and before the guest is named, as the member's does (§ The web);
  on an opened file the contributor's question says it changes for everyone who has the link, the owner too
  (`files.guest.replaceFileConfirm`); a file that holds no content asks nothing; after a
  peer's replace (`stale_file`) the page offers "replace anyway" on the current blob, as a member's does. The guest names
  itself once per link through the page's one naming flow (`requestGuest`, the shell's dialog, core's `GuestNaming`).
  No rename and no delete on the guest page (nodes store no authorship); a contributor's move of a file is core's move
  dialog (inbox #358). Inside a guest's identity folder
  (§ The drop box) no guest is offered a write (the capabilities). A guest-mode mount of each view proves it calls no
  authenticated client and reads nothing of the member's world (`levelRows.guest.test.ts`,
  `DropBoxDetail.guest.test.ts`).

## A single file as a share root

A `FILE` is a share root with **`READ` and `READ_WRITE`** (`FilesShareRoots`; inbox #378, owner principle #401: a share is
the member view with functions removed). Decided with it, small questions answered here:

- **`WRITE_ONLY` on a file is refused** — `422 illegal_scope` on create, `invalid_role_change` on a change, nothing
  stored, and the web never offers it. A write-only guest holds no key and reads nothing back, and a file has no
  identity folder to send into; sending a file *to* someone is the drop box. (Fail closed: core's `ShareRoots` answers a
  role Files does not list with `illegal_scope`.)
- **The link's guest reaches that file and nothing else.** The resolve serves the file alone (`parentId` null, no folder
  above, no sibling); `listShareFiles` lists it alone; `resolveShareBlob` authorizes its blob only. The file's key
  is the share's `wrap` under `K_share` (ADR 0080 decision 1), its name the link's own `sealedName`; every seal is bound
  to the file node (ADR 0072), so a blob the server lists for another node opens nothing under it. A `READ_WRITE` guest
  replaces the content through the same guest transport and `completeShareFile` a folder contributor uses — the root is
  a valid target (the guest's `ShareGuestGate.target` names a live node *inside* the subtree, the root included) — and
  creates nothing under the file (no `GuestNodeRules` answers for a `FILE` container: `illegal_scope`) and places nothing
  (the root is never placed: `illegal_structure`). No code changed there; `FileShareSliceTest` pins it.
- **A node in the trash is shared by nobody:** core's `createShare` answers `404 node_not_found` for one (for every kind;
  it served no guest anyway, and a restore is not a new share). A file trashed after its link exists serves nothing:
  not listed, its blob `404`, an upload refused.
- **The page is the member's file view, `FileDetail`** (`components/FileDetail.vue`, FILE's registered `detail`): core's
  header (name; download, replace, share, rename, move, delete — each a header action where the matrix says, owner
  decision 2026-10-14: the download and the replace used to be buttons below it), then the file and its size, type and
  date from its blob's sealed meta. The replace asks first (the node keeps its name; a compare-and-set on the blob it
  read, with "replace anyway" after a peer's replace; a file that holds no content offers an upload into it, which asks
  nothing); no standing sentence says beforehand what a replace does. It is the one way a `READ_WRITE` link on a single
  file writes: there is no folder above it to upload a file of the same name into. A member opens it from
  a click on its row in the folder's list (the row's primary opens it through the host); core's page of a public link
  (`ShareTreePage`) mounts it for the link's root — and for a file *inside* a folder link, which therefore also gets a
  page of its own. Its data source is the same `FilesSource` (`beginReplace` is its one addition: a replace names the
  file, so no folder is asked for). The download name is `downloadName` (the node name, else the blob's sealed name,
  plus the sealed type's extension).
- **The link dialog is core's** (`FolderShareDialog`) with the sentences about what is shared swapped for the file's
  (`ShareDialogCopy`, `files.share.*`; `FileShareDialog.vue` is a three-line wrapper). It offers `READ` and
  `READ_WRITE` only, as it does for a folder.
- **Delete** from the page is core's delete dialog (`deleteTitle`/`deleteCopy` of the kind) into the trash, with the
  undo toast; **rename** is core's name dialog (selecting the stem); **move** is core's move dialog.

## The drop box

A **`FILES_DROPBOX`** is a Files node at the root or in a `FOLDER` (no placement rule admits anything under it), shared
**`WRITE_ONLY` only** (`FilesShareRoots`). Its guests hold no key of it; what they send lands in **identity folders**,
one per guest identity, which are core `FOLDER`s carrying `veyq_node.share_identity_id` (ADR 0080 decision 11).

**A drop box carries a cover** (inbox #411, core's feature - [uc.platform.node-cover](../features/platform/use-cases/uc.platform.node-cover.md)):
Files opts it in (`FilesNodeKindRules.takesCover`, `register.ts` `cover: {}`), nothing else - the action, the dialog (choose, crop, remove), the upload, the
write and the display are core's, the image is the box's own blob in the `COVER` slot (not a file in it), and a **`FILE` takes none**
(its one blob is its content; a file's own thumbnail is a different idea, deferred). The header's leading slot shows it
(`DropBoxDetail.vue`), the row/tile and the sidebar's Favorites too; a guest of a link above sees it and sets none.

- **The keys.** The drop box's drop key pair is core's (`veyq_node.sealed_drop_private_key` under the drop box's key,
  the public half per link under `K_share`, set with the first link exactly as for a Memory's uploader link,
  `core/sharing/dropKey.ts`). A guest names itself with its own identity key `K_up`, wrapped to the drop public key
  (`veyq_share_identity.wrapped_identity_key`, seal `upload-identity-key`) and its name sealed under `K_up`. Its folder's
  entry — name (the guest's display name), slug and the folder's fresh key `K_F` — is sealed under **`K_up`** (seals
  `node-name`, `content-key`, epoch 0); everything below the folder is an ordinary subtree under `K_F`. A member (or a
  guest of a link above the drop box) opens drop box key → drop private key → `K_up` → `K_F` (core's tree,
  `childEntryKey`); no new seal purpose, no vector change. A guest-sealed entry that does not open leaves that folder
  out of the member's tree instead of failing it.
- **The guest** (`components/DropBoxGuestForm.vue`, what Files' share body `DropBoxShareBody.vue` renders on the link
  rooted at the drop box; `dropBox.ts`): names itself once — the name stays in the page until the **first file**, which
  creates the identity (`nameDropGuest`, once even for a second pick in flight) and then the folder, so a visitor who
  names itself and leaves leaves nothing on the server (inbox #385 (a)); a reload before that asks for the name again
  (the session then keeps identity, token, `K_up` and the name on the device; through core's one guest-naming component, `core/sharing/GuestNaming.vue`,
  the page says "You are here as …" with a "Not you?" that asks — only once a session exists — then forgets the
  session there and asks for a name again — a new identity, the old one's folder untouched; after a gone link the same
  action reads the link again first — a revoked or deleted one turns the page to "not available", inbox #393), then, with its **first file**, **`ensureShareDropFolder`** — `POST /api/shares/{shareId}/files/drop-folder`,
  Files' operation over core's `IdentityFolders`: the first call creates the folder from the guest's ciphertext, every
  later call (a reload, a second tab, a concurrent first upload) answers the same folder with its stored wrap. Then per
  file core's guest create **inside its own folder** (`createShareNode`: a `WRITE_ONLY` link creates only there, and
  **only `FILE`s** — a sub-folder or any other kind is `422 illegal_scope`; Files declares it through core's
  `WriteOnlyGuestKinds`, keyed on the link's root kind, owner decision 2026-10-08), core's
  guest transport and `completeShareFile` (core's `ShareGuestGate.target`: a `WRITE_ONLY` link names nothing else below
  its root). It sees nothing of the drop box or of others (no node served, `listShareFiles` empty) — only its files of
  this session: being sent, sent (with the size), or not sent with the reason and, where a retry can help, a retry (the
  page has no upload menu, so this list is the only place a guest learns what failed, inbox #381). A gone link — any
  `404`, or `share_not_contributable`/`share_password_incorrect` at any door — turns the page into "this link no longer
  takes files from you" without a picker, and `dropGuestJob` stops the rest of the batch without a request; a store's own
  refusal (`chunk_put_failed:403`) is a failed file, not a gone link.
- **The member** (a drop box's row in the level list — icon, name, "Drop box · N people", open on a click, move and delete in its menu —, `DropBoxDetail.vue`, `DropBoxCreateDialog.vue`,
  `DropBoxShareDialog.vue`): creates a drop box from a folder level, mints, copies, switches off (after a question) and
  on again (core's `reactivateShare`; a link a departed member may hold says to make a new one) and deletes its links,
  opens it to its guests' folders — under core's header (`NodeHeader`, the one a folder opens with: the
  drop box's name, its share and delete; inbox #385 (b)) — (a repeated guest name numbered at display time, never stored: `Anna`, `Anna (2)` in
  creation order — the server-observed `createdAt` of core's listing, then the node id for one millisecond, core's
  `compareByCreation`, by core's `displayNamesOf`, which the folder's heading and breadcrumb use too; the same two folders' URL segments are `anna`, `anna-2` by the same order,
  core's `segmentsOf` in `core/node/location.ts`; a download's ZIP names its directories by core's `archiveName` instead), opens a folder
  (core's level view, read-only there: download and a delete per file — a content-less one too — through the level's
  `deletable`, no share, and no tab that has nothing to show — core's `hideEmptyTabs`) and deletes a folder. **Members place nothing** in a drop box or
  a guest's folder (core: no rule places into the drop box; a create, move or rename into an identity folder is
  `illegal_structure`, `NodesHandler`), and only delete or move out — a move out re-wraps and re-slugs under the
  target and ends the folder's identity link. **The web offers it** (inbox #358): "Move … out…" on each guest's folder
  of the drop box's detail, and "Move to…" in the folder's own header (its one action there) — core's
  `MoveNodeDialog`, which asks first (the folder becomes an ordinary one, its guest can add nothing more, and nothing
  moves back into a drop box, so the toast offers no undo) and lists no place inside a drop box. The member's move
  re-wraps the folder's own key, opened through the drop key and the guest's `K_up`, under the destination's
  (`core/node/move.wire.test.ts`: the folder and its file open afterwards; `views/MemoryHomeView.move.test.ts`: the
  question asked and answered in the real dialog). A file inside a guest's folder moves out the same way, without the
  question (the folder keeps its identity).
  **Only a member moves out** (owner decision 2026-10-08, inbox #377 (a)): a guest's `updateShareNode` of an identity
  folder or of anything inside one is `422 illegal_structure` (`ShareNodesHandler`, known by core's
  `share_identity_id`, before any application's `GuestNodeRules`), held by
  `DropBoxSliceTest.aGuestOfALinkAboveMovesNothingOutOfADropBox`.
- **In a folder share above** (`READ`/`READ_WRITE`) the drop box **is shown, read-only** (owner decision 2026-10-08,
  inbox #380: the guest was given that tree): the backend serves the drop box's subtree with its drop key to a guest of
  a link above (ADR 0080 decision 11), so core's walk opens the identity folders' names (`childEntryKey`) and their
  files. Core's folder page renders the drop box's `detail` — the member's `DropBoxDetail.vue`, handed the guest's
  access and tree (inbox #401 S3) — never the `WRITE_ONLY` guest's form (Files' claimed share body, which the shell
  mounts for the drop box's own `WRITE_ONLY` link only): the guests' folders with their file counts, a repeated name numbered in node-id order (the
  guest's tree serves no `createdAt`, so `compareByCreation` falls to its id tie-break and the numbers can differ from the
  member's creation order), in the guest's words (`files.dropBox.sharedHint`: only the owner deletes or moves); a folder
  opens on core's folder page, which inside an identity folder offers no guest a write (no "New" menu, Files' rows
  only download), so it lists the files with their download only. No send form, no upload, no replace, no share, no
  delete, no move — the guest's source carries no delete and the matrix grants a guest neither share nor delete; the
  server refuses each anyway (a create inside an identity folder `illegal_structure` for anyone but its identity, a
  guest move out above). Pinned by `DropBoxDetail.guest.test.ts` (the real registry, router and PrimeVue, DOM clicks,
  guarded clients; red with the write controls let through or the guest's form embedded).
- **Revoking or deleting a link keeps the drop box and everything in it**: an identity a folder names is kept
  (`NodeGuestIdentityUsage`, `fk_node_share_identity`), so the member still opens it; the purge of a trashed drop box
  erases its folders, then the identities nothing names any more.
- **A trashed identity folder** takes nothing: the guest's next `ensureShareDropFolder` and creates there answer
  `403 share_not_contributable` for good — the unique index spans trashed rows, so that identity never gets a second
  folder; the guest starts over under a new identity ("Not you?" / "Start over") — and a member restores the folder from
  the trash. Owner-accepted as built (2026-10-08, inbox #377 (b)).
- **The folder call spends upload budget:** every `ensureShareDropFolder` spends a token of the link's upload rate
  limit (300/min by default), also when it only answers the existing folder — deliberately, so the limit has no gap a
  caller could loop through. Owner-accepted as built (2026-10-08, inbox #377 (d)).

## Operator-visible, beyond the platform's `kind`

File sizes (exact sealed sizes, unless padded), upload and change timestamps, the tree's shape, and when a file's blob
was replaced; of a blob's sealed metadata only its length, padded to one of four 256-byte buckets (a name's rough length
class, not the name). For a drop box: how many guests sent something (one folder per identity), which identity holds which
folder and its files, and when.

## Open questions

- A thumbnail or cover for a file: out of scope for the viewer (inbox #412 decided Files makes no preview blobs); a cover
  for a file is inbox #411.
- A PDF's live links and form fields (an annotation layer: a link is a navigation and phishing vector, so it needs the
  owner's decision on a policy for where a click may go) and the WASM image decoders (a policy decision,
  `'wasm-unsafe-eval'`): § The viewer, inbox #412. (Text selection/copy, the password prompt and a plain text's Copy and
  wrap toggle are built.)
- An operator-configurable per-file size cap (the quota only, at first).
- The other reversible choices made while building Files wait for the owner's review: inbox #378. (Where the drop box
  differed from ADR 0080 decision 11, inbox #377, the owner ruled 2026-10-08 — § The drop box.)
