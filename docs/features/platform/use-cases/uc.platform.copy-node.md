# UC: Copy a node and everything under it to another place

- **ID:** uc.platform.copy-node
- **Status:** built 2026-10-14 (cr-copy-node), web only, for folders, files, drop boxes, Memories and chapters. A Memory's
  media copy with their existing tiers and sealed facts, nothing decoded (ADR 0082 decision 11). Seen in a real browser on
  2026-10-14: "Reisen (2)" (sub-folders, files, the Memory "Lissabon" and the drop box "Postfach" with two guests' folders
  both named "Dr. Anna") copied into "Archiv (4)" - the copy opened with its files readable (`a_b.txt` showed its text),
  "Postfach" a plain folder holding "Dr. Anna" and "Dr. Anna (2)"; a file copied into its own folder became `a_b.txt (2)`;
  a selection of a folder and a file copied as one ("2 copied to ..."). Then, with "Lissabon"'s photo given a corrected
  capture time and made its cover, and an empty chapter "Alfama" added, "Reisen (2)" copied into a fresh folder (30 nodes,
  the dialog leaving nothing out and listing the Memory's loss): the copied "Lissabon" showed the date, its cover and the
  chapter, its photo's thumbnail and preview decoded, and the lightbox named "tram.jpg", image/jpeg, 1.1 kB, captured
  14.09.2025 16:02, "Korrigiert"; every request answered 2xx (one media completion, three blobs - the original and two
  tiers - presigned and uploaded once each, the cover written as a photo of the copy). **Not yet seen:** a stop part-way
  and the quota's "no" (unit-tested), a multi-MB file or a video (the frame reads are unit- and wire-tested), a copied
  uploaded cover image and a place name (wire-tested), and the progress bar on a long copy.
- **Primary actor:** A member whose keys are unlocked.
- **Goal:** Put a copy of a folder, a file, a Memory with its chapters or a drop box at another place in the same space,
  without the server learning a name or that the copy is one.
- **Preconditions:** Signed in and unlocked; the node lies in the member's open space; the destination is a place in the
  same space where the member may create.
- **Postconditions (success):** a new node tree under the destination, every node with a fresh key and every file
  re-encrypted under a fresh `K_blob`, owned and uploaded by the member; the original is untouched.

## Main flow

1. On a node, or on a level's selection ([uc.memory.browse](../../memory/use-cases/uc.memory.browse.md) step 6), the
   member chooses **Copy to…**, beside **Move to…**.
2. Core's folder browser opens, as for a move ([uc.memory.move-node](../../memory/use-cases/uc.memory.move-node.md)
   step 2), with **Copy here**: it shows the places that take every copied kind, and unlike a move the node's current
   place is allowed (the copy is named `Name (2)` there) - a node itself and anything inside it are still not.
3. The dialog says what the copy will create and how many bytes, and **what does not come along** (below); it asks the
   server whether those bytes still fit the tenant's quota, as the import does ([uc.platform.export-import](uc.platform.export-import.md) E7).
4. **Copy** walks the subtree with the member's keys and re-creates it under the destination through each kind's own
   hooks - the same walk and hooks as a download followed by an import ([ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
   decision 11), without a ZIP: each file is read from the original by chunk, decrypted on the device and uploaded again
   under a new key. A name taken among the destination's children becomes `Name (2)`. Progress says "N of M".
5. The tree is re-read; a toast names the copy.

## Alternate / exception flows

- **E1 — not enough room:** the quota question says no; nothing is written.
- **E2 — a stop part-way** (a refused write, quota, a failed upload, the tab closed): what was created is moved to the
  trash, and the message says "N of M" - the import's rollback.
- **E3 — a kind this build cannot copy** (no hooks): left out with its subtree and named before anything is written.

## What a copy does not carry, by design

What an import does not carry: shares, members, favourites, the trash, server-observed times; participants and a
medium's original contributor stay names only (a medium's capture-time correction, coordinates and place name DO come
along: a copy carries the sealed facts and the derived tiers, ADR 0082 decision 11); a folder's or a drop box's cover; a drop box becomes a plain folder with
plain folders inside (no drop key, no links, no guest identities).

## Quality notes

- **Operator-blind, also about the copy.** The server sees ordinary creates and uploads of new ids and sizes; it is not
  told that they copy anything, and no stored object is shared (one reference per blob, [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md), stays true). The size
  and timing of the uploads still resemble the original's - as any upload of the same files would.
- **Costs what an upload costs.** Every byte is downloaded and uploaded again and counts against the quota twice. A
  server-side object copy or a deduplicated blob was rejected (owner decision 2026-10-14): either would need new
  endpoints per application and tell the operator which nodes are copies.
- **No memory wall.** A file is never held whole; it is read and re-encrypted by chunk.
- **Not atomic.** As the import: best-effort rollback to the trash.

## What this means for you / Was das für dich bedeutet

**EN —** "Copy to…" puts a copy of a folder, file, memory or drop box somewhere else in the same space. Everything in it
is copied on your device and stored again, so a big folder takes as long as uploading it and uses its space twice.
Shares, favourites and who took which photo do not come along, and a copied drop box is an ordinary folder. The
server only sees new things being stored - not that they are a copy.

**DE —** Mit „Kopieren nach …“ legst du eine Kopie eines Ordners, einer Datei, einer Erinnerung oder eines Briefkastens
an einem anderen Ort im selben Bereich ab. Alles darin wird auf deinem Gerät kopiert und neu gespeichert: Ein großer
Ordner braucht so lange wie sein Hochladen und belegt seinen Platz doppelt. Freigaben, Favoriten und wer welches Foto
gemacht hat, kommen nicht mit, und ein kopierter Briefkasten ist ein gewöhnlicher Ordner. Der Server sieht nur, dass
Neues gespeichert wird - nicht, dass es eine Kopie ist.

## Related

- Feature: [Platform](../_index.md)
- Use-cases: [uc.platform.export-import](uc.platform.export-import.md) (the walk and hooks a copy reuses),
  [uc.memory.move-node](../../memory/use-cases/uc.memory.move-node.md) (the browser),
  [uc.memory.browse](../../memory/use-cases/uc.memory.browse.md) (the selection)
- Decisions: [ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)
  decision 11, [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md),
  [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (one reference per blob)
- End-user view (docs site): [Depot — and finding your way around](../../../../site/src/content/docs/depot.md)
