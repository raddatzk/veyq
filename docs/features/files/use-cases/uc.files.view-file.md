# UC: View a file — open it and see it (image, text, PDF, video, audio), or get the download

- **ID:** uc.files.view-file
- **Primary actor:** Whoever can open a file's page: a **member** of the scope, or a **share guest** of a link that
  reaches the file (`READ` or `READ_WRITE`, a single-file link or a file inside a folder link — the member's view with
  functions removed, [inbox #401](../../../inbox.md)).
- **Goal:** See what the file *is* without saving it first — a picture, a text, a PDF, a recording — and where it cannot be
  shown, be told so and get the download, with nothing about the file ever readable by the operator.
- **Preconditions:** A file that holds content (an empty file says so), the page open, and the file's key in the client
  (the member's tree, or the key a link carries).
- **Postconditions (success):** the file is shown on its page above its size, type and date, or full screen; nothing is
  stored beside it (**Files makes no thumbnail and no preview blob**), and everything the page created in the browser —
  its object URL, its playback handle, its read in flight — is gone when the page closes.

## Main flow

0. **Who uploaded it** (ADR 0083 Addendum): a member sees the uploader of the file's current content in the header -
   "Uploaded by" a member's name and picture, linking to their profile; one who left the space as a greyed "former
   member" without a link; a link's guest by the name it gave itself, without a link (inbox #431) - a drop-box guest by
   its identity folder's name, any other guest by its sealed name opened under the file's key or the nearest ancestor key
   the member holds; a guest of a deleted link stays named; "a guest" only where the name does not open. A replace makes
   the replacer the uploader. A share guest sees none: the link's listing carries no uploader and no name.
   Seen in a real browser on 2026-10-14: the owner's upload read "Uploaded by" with the owner's avatar and a profile
   link; a `READ_WRITE` folder link's guest named itself "Ben Gast" and uploaded a file - the guest's own file page showed
   no uploader line, the member's read "Uploaded by Ben Gast" as plain text without a link.
1. The page opens. Only now does the client read the file (not for a row in a list): it opens the first chunk under the
   file's key and **sniffs the bytes** (magic numbers) — the sealed type and the name only narrow, never decide.
2. **Image** (JPEG, PNG, GIF, WebP, AVIF, BMP, SVG; HEIC is tried and falls back where the browser cannot decode it):
   the chunks are opened a few at a time into one typed `Blob` and shown as an `<img>` from a blob URL (an SVG only so,
   never inline), up to 64 MiB.
3. **Text** (plain text, Markdown, code, JSON, CSV …: valid UTF-8, or UTF-16 with a byte-order mark, no control bytes,
   and a text-ish type or extension): the first 1 MiB is shown as text in a monospace block — as characters, never as
   markup — with a note when the file is longer. The text is ordinary selectable page text; a **Copy** button copies exactly
   what is shown (a longer file's shown part, and the note says so; where the browser's clipboard is unavailable the text
   is selected and the user is told to press Ctrl/Cmd+C), and **Wrap lines** (on by default) toggles wrapping against
   sideways scrolling.
4. **Video and audio** (MP4, MOV, WebM, Matroska, Ogg, MP3, AAC, WAV, FLAC, M4A): played by `<video>`/`<audio>` from the
   Service Worker's ranged media route ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)),
   which asks the page for decrypted chunks as the player seeks; no cap, because nothing but the player's window is in
   memory.
5. **PDF** (`%PDF-` within the first KiB): decrypted whole (up to 64 MiB) and drawn **inside the page**, page by page onto
   canvases by pdf.js — loaded as a chunk of its own on the first PDF — with "Page n of N" and zoom out / zoom in / fit
   width. Only the pages near the viewport are drawn; the others cost nothing. Over each drawn page sits a transparent **text
   layer**, so the text can be selected with the mouse or keyboard and copied (Ctrl/Cmd+C); a page without extractable
   text (a scan) has none. A link inside the PDF is not clickable: there is no annotation layer and the text layer holds
   spans only ([docs/apps/files.md](../../../apps/files.md) § The viewer).
6. **Full screen:** a button enlarges the viewer (the browser's Fullscreen API, Escape leaves; a fixed overlay where
   the API is missing, e.g. an iPhone's Safari).

## Alternate / exception flows

- **A1 — a PDF the page cannot draw:** one that cannot be read, one over 1000 pages or 64 MiB: a clear message and the
  download. A page that
  fails to draw says so in its place and leaves the others. A scanned page whose image uses the JBIG2 or JPEG 2000
  codec shows that image blank, because their decoders need WebAssembly, which the app's policy does not allow
  ([CSP](../../../crosscutting/cc.security.csp.md)).
- **A1b — a password-protected PDF:** the page asks for the password in a labelled field (focused, Enter submits, Escape
  cancels). A wrong one says "Wrong password, try again" in place and asks again; the right one shows the pages; Cancel
  keeps the PDF closed with the download offered. The password goes to pdf.js only: it is not stored, logged, kept in the
  component's state or sent anywhere, and it is cleared from the field as soon as it is submitted and when the page goes.
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
- **Never trust the bytes as code.** Text is `textContent`; an image is an `<img>`; a PDF is pixels on a canvas with a
  layer of plain text spans over it (no frame, no link, no script, no eval, no WebAssembly, no network but the app's origin); no `<iframe>`, `<object>` or inline
  SVG; the blob URL's type is the sniffed one; the media route sets `Content-Type` from the sniffed container.
- **Memory is bounded per kind** (image 64 MiB, PDF 64 MiB and 1000 pages, text 1 MiB, media streamed); a larger file is never pulled whole.
- **A share looks like the member view:** the same component and the same capability rules for every role.

## What this means for you / Was das für dich bedeutet

**EN —** Open a picture, a text file, a video or a sound file and it is simply shown on its page — the same for you and
for anyone you shared it with. Members of the space also see who uploaded the file's current version; people you share a
link with do not. It is decrypted on your device while you look; nothing is saved besides the file itself.
A PDF opens on its page too, page by page, and you can select and copy its text; a password-protected PDF asks for its
password on the page. A format your browser cannot show comes with a clear note and the download instead.

**DE —** Öffne ein Bild, eine Textdatei, ein Video oder eine Audiodatei, und sie wird einfach auf ihrer Seite angezeigt
— für dich genauso wie für alle, denen du sie freigegeben hast. Mitglieder des Bereichs sehen außerdem, wer die aktuelle
Fassung der Datei hochgeladen hat; wer nur einen Link hat, sieht das nicht. Sie wird auf deinem Gerät entschlüsselt, während du sie
ansiehst; außer der Datei selbst wird nichts gespeichert. Auch eine PDF-Datei öffnet sich auf ihrer Seite, Seite für Seite, und ihren Text kannst du markieren und kopieren;
eine passwortgeschützte PDF fragt direkt auf der Seite nach ihrem Passwort. Bei einem Format, das dein Browser nicht
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
