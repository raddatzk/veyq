# UC: View a file — open it and see it (image, text, video, audio), or get the download

- **ID:** uc.files.view-file
- **Primary actor:** Whoever can open a file's page: a **member** of the scope, or a **share guest** of a link that
  reaches the file (`READ` or `READ_WRITE`, a single-file link or a file inside a folder link — the member's view with
  functions removed, [inbox #401](../../../inbox.md)).
- **Goal:** See what the file *is* without saving it first — a picture, a text, a recording — and where it cannot be
  shown, be told so and get the download, with nothing about the file ever readable by the operator.
- **Preconditions:** A file that holds content (an empty file says so), the page open, and the file's key in the client
  (the member's tree, or the key a link carries).
- **Postconditions (success):** the file is shown on its page above its size, type and date, or full screen; nothing is
  stored beside it (**Files makes no thumbnail and no preview blob**), and everything the page created in the browser —
  its object URL, its playback handle, its read in flight — is gone when the page closes.

## Main flow

1. The page opens. Only now does the client read the file (not for a row in a list): it opens the first chunk under the
   file's key and **sniffs the bytes** (magic numbers) — the sealed type and the name only narrow, never decide.
2. **Image** (JPEG, PNG, GIF, WebP, AVIF, BMP, SVG; HEIC is tried and falls back where the browser cannot decode it):
   the chunks are opened a few at a time into one typed `Blob` and shown as an `<img>` from a blob URL (an SVG only so,
   never inline), up to 64 MiB.
3. **Text** (plain text, Markdown, code, JSON, CSV …: valid UTF-8, or UTF-16 with a byte-order mark, no control bytes,
   and a text-ish type or extension): the first 1 MiB is shown as text in a monospace block — as characters, never as
   markup — with a note when the file is longer.
4. **Video and audio** (MP4, MOV, WebM, Matroska, Ogg, MP3, AAC, WAV, FLAC, M4A): played by `<video>`/`<audio>` from the
   Service Worker's ranged media route ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)),
   which asks the page for decrypted chunks as the player seeks; no cap, because nothing but the player's window is in
   memory.
5. **Full screen:** a button enlarges the viewer (the browser's Fullscreen API, Escape leaves; a fixed overlay where
   the API is missing, e.g. an iPhone's Safari).

## Alternate / exception flows

- **A1 — a PDF** is not rendered on the page (first cut): the page says so and offers the download. The browser's own
  viewer needs a `blob:` frame the app's policy forbids ([docs/apps/files.md](../../../apps/files.md) § The viewer).
- **A2 — a format the sniffers do not know, a text in another encoding, or an image the browser cannot decode:** a
  clear message and the download. A claimed type never makes the bytes render as something they are not.
- **A3 — over the cap** (an image over 64 MiB): not decrypted; the message names the limit and offers the download.
- **A4 — no usable Service Worker, or a codec the browser lacks:** a video or audio is not played; message and download.
- **A5 — a read fails or is stopped:** an error in the page's own style with "try again", or "stopped" after the
  member pressed Stop. Leaving the page stops the read.
- **A6 — the file is replaced** (by this page or a peer): the viewer reads the new content; the old object URL is
  revoked.

## Quality notes

- **Operator-blind.** The server serves the same ciphertext chunks as for a download; decryption, sniffing and drawing
  happen on the device. Nothing is logged and no new network origin is used.
- **Never trust the bytes as code.** Text is `textContent`; an image is an `<img>`; no `<iframe>`, `<object>` or inline
  SVG; the blob URL's type is the sniffed one; the media route sets `Content-Type` from the sniffed container.
- **Memory is bounded per kind** (image 64 MiB, text 1 MiB, media streamed); a larger file is never pulled whole.
- **A share looks like the member view:** the same component and the same capability rules for every role.

## What this means for you / Was das für dich bedeutet

**EN —** Open a picture, a text file, a video or a sound file and it is simply shown on its page — the same for you and
for anyone you shared it with. It is decrypted on your device while you look; nothing is saved besides the file itself.
A PDF, or a format your browser cannot show, comes with a clear note and the download instead.

**DE —** Öffne ein Bild, eine Textdatei, ein Video oder eine Audiodatei, und sie wird einfach auf ihrer Seite angezeigt
— für dich genauso wie für alle, denen du sie freigegeben hast. Sie wird auf deinem Gerät entschlüsselt, während du sie
ansiehst; außer der Datei selbst wird nichts gespeichert. Bei einer PDF-Datei oder einem Format, das dein Browser nicht
anzeigen kann, bekommst du stattdessen einen klaren Hinweis und den Download.

## Related

- End-user view (wiki): [Files — and finding your way around](../../../../wiki/en/files.md)
- **Feature:** [files](../_index.md); the application's description [docs/apps/files.md](../../../apps/files.md).
- **Use-cases:** [uc.files.member-files](uc.files.member-files.md) (the page's download and replace),
  [uc.files.share-file](uc.files.share-file.md), [uc.files.share-folder-files](uc.files.share-folder-files.md).
- **Decisions:** [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md) (the media route),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) (the blob key bound to node and role),
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md), [CSP](../../../crosscutting/cc.security.csp.md).
