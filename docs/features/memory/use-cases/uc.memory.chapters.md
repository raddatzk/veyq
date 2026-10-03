# UC: Chapters — nested Memories inside a Memory

- **ID:** uc.memory.chapters
- **Primary actor:** A member with an **unlocked vault**, inside one of their spaces, who has a Memory open
  ([uc.memory.gallery](uc.memory.gallery.md)) and wants to **subdivide it into chapters**.
- **Goal:** Create and browse **chapters** — sections *inside* a Memory, each itself a full Memory (own
  content, own media) that may nest further — and attach media to a chapter, all **operator-blind**.
- **Preconditions:** The vault is unlocked and the Memory is open (its **`K_mem`** is held). Chapters are a
  child node kind already modeled in the contract + backend (`CHAPTER`, allowed only inside a `MEMORY`).
- **Postconditions (success):** The Memory shows its chapters; the user can create / open / rename / delete a
  chapter and upload media into it. Each chapter holds its **own random `K_mem`**, wrapped under the parent
  Memory's `K_mem`; the server stored only ciphertext + the opaque wrap + the node shape.

A chapter is itself a content unit, so it carries its **own** `K_mem` (wrapped under the parent Memory's
`K_mem`) — **recursive `K_mem`**, which is what makes per-chapter sharing possible later without a re-encryption
migration. The key layout lives in [en.memory.tree](../enablers/en.memory.tree.md); the decision + rationale
in [ADR 0012](../../../decisions/0012-content-key-hierarchy.md).

## Main flow

1. With a Memory open, the user **adds a chapter** (a titled section). The client **births a fresh random
   `K_mem`** for the chapter, wraps it under the **parent Memory's `K_mem`**, and creates a `CHAPTER` node
   under the Memory (ciphertext name + slug token under `K_SC`, like any node).
2. The Memory view **lists its chapters** (decrypted titles) alongside its own media.
3. **Open a chapter** → it behaves like a Memory: its own gallery of media, and it may nest further chapters.
4. **Upload into a chapter:** media is sealed under the **chapter's `K_mem`** ([en.memory.media](../enablers/en.memory.media.md)) and attached to the chapter node.
5. **Rename / delete** a chapter like any node (delete takes its subtree into the space's recoverable trash —
  [uc.memory.delete-node](uc.memory.delete-node.md)).

## Alternate / exception flows

- **E1 — illegal nesting:** a folder inside a Memory, or a chapter outside a Memory, is rejected by the
  existing structural rule (`422 illegal_structure`) — chapters live **only** under a MEMORY (or another
  CHAPTER), never under a FOLDER or the scope root.
- **E2 — vault locked / Memory not open:** without the parent `K_mem` a chapter's key can't be unwrapped → its
  content is unavailable until unlock + open.
- **E3 — empty chapter:** opens to the empty gallery state.

## Quality notes

- **Operator-blind.** The server sees the chapter node (id / parent / kind / order / timestamps, to the second), the
  ciphertext title + blinded slug, and the **opaque `K_mem` wrap** — never a key, name, or pixel. The recursive
  key model keeps least-privilege **crypto-enforced**, not server-gated.
- **No new key type.** A chapter reuses the Memory key model (`K_mem`, the `content-key` wrap purpose bound to
  the chapter's own node — [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)) one level deeper —
  the contract change is only *allowing* `wrappedContentKey` on a `CHAPTER`, not a new field.
- **Reuse the existing UI/UX — never invent** ([ui-web-styleguide](../../../crosscutting/ui-web-styleguide.md)
  § Reuse first). Chapter creation + the nested gallery are the existing shell and grid; only the data layer
  is new.

## What this means for you / Was das für dich bedeutet

**EN —** A big Memory can be split into **chapters** — like „Day 1 / Day 2" inside one trip. Each chapter is a
little Memory of its own with its own photos, and you can even nest chapters further. Every chapter is locked
with its **own key** (kept inside the Memory's key), so later we can let you **share a single chapter** with
someone without exposing the rest — your device does all the locking, the server only ever sees scrambled data.

**DE —** Eine große Erinnerung lässt sich in **Kapitel** unterteilen — etwa „Tag 1 / Tag 2" innerhalb einer
Reise. Jedes Kapitel ist eine kleine eigene Erinnerung mit eigenen Fotos und kann selbst wieder Kapitel
enthalten. Jedes Kapitel hat einen **eigenen Schlüssel** (aufbewahrt im Schlüssel der Erinnerung), damit wir
dir später erlauben können, **ein einzelnes Kapitel** mit jemandem zu teilen, ohne den Rest preiszugeben — dein
Gerät verschlüsselt alles, der Server sieht nur verschlüsselte Daten.

## Related

- Feature: [Memory](../_index.md)
- Use-cases: [uc.memory.browse](uc.memory.browse.md) (the tree + node CRUD this builds on),
  [uc.memory.gallery](uc.memory.gallery.md) (opening a Memory/chapter gallery; the **aggregated** timeline is
  a later CR), [uc.memory.upload](uc.memory.upload.md) (media into a node)
- Enablers: [en.memory.tree](../enablers/en.memory.tree.md) (`K_mem`, node CRUD),
  [en.memory.media](../enablers/en.memory.media.md) (`K_blob` under the parent's `K_mem`)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (content-key hierarchy — this UC
  applies its **recursive `K_mem`** decision for chapters)
- Future: chapter **sharing** (#19) — the key layout here is the groundwork
