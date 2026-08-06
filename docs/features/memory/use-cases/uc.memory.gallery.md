# UC: Open a Memory & browse its gallery

- **ID:** uc.memory.gallery
- **Primary actor:** A member with an **unlocked vault**, inside one of their spaces, who **opens a Memory**
  from the shell.
- **Goal:** Open a Memory and look through its media — a thumbnail **grid**, an item **lightbox** (full view,
  prev/next), and **download** an original — with every byte decrypted **on device**.
- **Preconditions:** The user has unlocked the vault and is browsing the tree
  ([uc.memory.browse](uc.memory.browse.md)); the Memory exists and its **`K_mem`** can be unwrapped; its media
  carry **derivatives** ([en.memory.derivatives](../enablers/en.memory.derivatives.md); a non-derivable item
  shows an icon tile).
- **Postconditions (success):** The user sees the Memory's media as a grid, opens any item in a lightbox and
  pages through, **plays a video** in place, can favorite the Memory, and can download an item's original. The
  server saw only **ciphertext fetches** — no pixel, no filename, no content key.

## Main flow

1. From the shell ([uc.memory.browse](uc.memory.browse.md)) the user clicks a Memory → it **opens**: the
   client unwraps the Memory's **`K_mem`** (already held from the tree).
2. The client **lists the Memory's media** ([en.memory.media](../enablers/en.memory.media.md)) and fetches each
   item's **thumbnail** ([en.memory.derivatives](../enablers/en.memory.derivatives.md)) via presigned GET,
   decrypts on device, and renders a **grid** under a header (title, item count, favorite toggle).
3. **Lightbox:** clicking an item opens it full-view; the client fetches that item's **preview**, decrypts, and
   displays it; **prev/next** page through the grid order. (The preview — not the multi-MB original — keeps the
   lightbox fast.) A **video** (known from its sealed `mediaType`, [ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md))
   shows its poster with a **play** control; playing it **streams** the **original** on device — a key-free Service
   Worker answers the `<video>` demuxer's byte-range requests, pulling + decrypting only the covering chunks from
   the page as the demuxer needs them (no whole-blob wait). Where no Service Worker is available it falls back to
   fetching + decrypting the whole original into a typed blob URL. A **play badge** marks a video's grid tile
   (#104/#105).
4. **Download:** an explicit download fetches the **original** (the only pristine, full-resolution copy) and
   saves it.
5. **Favorite:** the header toggle pins / unpins the Memory ([en.memory.favorites](../enablers/en.memory.favorites.md)).

## Alternate / exception flows

- **E1 — an item has no derivable frame** (a type that is neither image nor video): the grid shows an **icon +
  name**. (There is **no backfill** — on a fresh instance every image/video media carries its derivatives from
  upload, [en.memory.derivatives](../enablers/en.memory.derivatives.md).)
- **E2 — vault locked / Memory not open:** without `K_mem` the media can't be read → the gallery is unavailable
  until unlock + the Memory is opened.
- **E3 — empty Memory:** the grid shows an empty state.

## Quality notes

- **Operator-blind.** Every tier is fetched as **ciphertext** and decrypted on device; the server never sees a
  pixel, a filename, or a content key. The lightbox shows the **preview**, not the original — the original
  leaves the store only on explicit download.
- **Reuse the existing UI/UX — never invent** ([ui-web-styleguide](../../../crosscutting/ui-web-styleguide.md)
  § Reuse first). The grid + lightbox are existing components; only the **data layer** is client-side crypto
  over the contracts client.

## What this means for you / Was das für dich bedeutet

**EN —** Open a Memory and you see its photos as a grid; tap one to view it large and swipe through the rest.
A **video** shows a small ▶ badge on its tile — open it and press play to watch it right there. Everything is
decrypted on **your own device** — the server only ever stores scrambled files and never sees your pictures or
videos. To keep it quick, the grid and the large view use **compact copies your device made**; the
**full-quality original is only fetched when you actually download it** — and a video is **streamed piece by
piece as you watch**, so only the parts you actually play are fetched, not the whole file. Journal, chapters,
maps and playlists come in later steps.

**DE —** Öffne eine Erinnerung und du siehst ihre Fotos als Raster; tippe eines an, um es groß zu sehen und
durch die anderen zu blättern. Ein **Video** zeigt ein kleines ▶-Abzeichen auf seiner Kachel — öffne es und
drücke Play, um es direkt dort anzusehen. Alles wird auf **deinem eigenen Gerät** entschlüsselt — der Server
speichert nur verschlüsselte Dateien und sieht deine Bilder und Videos nie. Damit es flott bleibt, nutzen das
Raster und die Großansicht **kompakte Kopien, die dein Gerät erstellt hat**; das **Original in voller Qualität
wird nur geladen, wenn du es wirklich herunterlädst** — und ein Video wird beim Ansehen **Stück für Stück
gestreamt**, sodass nur die tatsächlich abgespielten Teile geladen werden, nicht die ganze Datei. Journal,
Kapitel, Karten und Playlists kommen in späteren Schritten.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.derivatives](../enablers/en.memory.derivatives.md) (the three tiers),
  [en.memory.media](../enablers/en.memory.media.md) (the blob transport),
  [en.memory.favorites](../enablers/en.memory.favorites.md) (the favorite toggle),
  [en.memory.tree](../enablers/en.memory.tree.md) (`K_mem`)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (direct-to-store
  media), [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_mem` → `K_blob`)
- Related use-cases: [uc.memory.browse](uc.memory.browse.md) (done — the shell this opens from);
  the journal / chapters / map — planned
