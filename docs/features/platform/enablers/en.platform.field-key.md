# Enabler: Every field seals under a key of its own

- **ID:** en.platform.field-key
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: it states what is sealed under what, not a flow.
- **Status:** **built** — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 9, built by cr-text-fields-seal-under-their-own-key (2026-10-17). The one implementation is `@veyq/crypto`'s
  field seal ([`seal.ts`](../../../../src/packages/crypto/src/seal.ts) `FIELD_PURPOSES`, `sealField`, `openField`,
  `fieldKeyOf`, `openFieldWith`; the envelope in [`primitives.ts`](../../../../src/packages/crypto/src/primitives.ts)),
  pinned by `field_seal` in [`v1.json`](../../../../contracts/crypto/vectors/v1.json).
- **Provides:** for every text or icon field, a key that opens **one revision of that one field** and nothing else:
  the key a report of a field will hand over to the moderator, and the moderator's open with it.
- **Serves (use-cases):** the report of a node's name on its own ([uc.moderation.report](../../moderation/use-cases/uc.moderation.report.md)
  A8: the report hands over that name's `K_field` alone, `fieldKeyOf`, and the moderator opens it with `openFieldWith`).
  Every other field is still reported with its object; every reader derives the field's key from the key it already
  holds.
- **Depends on:** [en.memory.tree](../../memory/enablers/en.memory.tree.md) (the key chain a node's name and meta sit
  in); [en.spaces.member-identity](../../spaces/enablers/en.spaces.member-identity.md) (`K_tenant`, which a member's
  name and picture sit under).

## What is sealed under what

`K_field = HKDF-SHA256(ikm = K_parent, salt, info = "veyq/v1/field/<purpose>", 32)`. `K_parent` is the key the field
belongs under; the AAD binds purpose and object as every seal does
([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)).

| Purpose | Field | `K_parent` |
|---|---|---|
| `node-name` | a node's directory entry (`encryptedName`) | the entry key: the parent's `K_node`; `K_SC` at the node's epoch for a scope root; a guest's `K_up` for its identity folder |
| `node-meta` | a node's meta (`wrappedMeta`) | the node's own `K_node` |
| `media-meta`, `media-exif` | a Memories medium's two metadata tiers | the Memory's or chapter's `K_node`, or the guest's `K_up` |
| `bucket-name` | a Memories bucket's name | the Memory's or chapter's `K_node`, or `K_up` |
| `share-identity-name` | a guest's display name | the share root's `K_node`, or `K_up` |
| `share-memory-name`, `share-note`, `share-name` | a share's root name, note and private label | `K_share` |
| `member-name`, `member-avatar` | a member's real name and profile picture | `K_tenant` |

`group-name` and `space-icon` are **not** fields: they keep a key per (scope, epoch), `HKDF(E_e, "", label)`, so it
opens every revision of that epoch. A pending invitee is handed `K_name`, which a per-write key would turn into a new
wrap per invitee and rename; only the scope's members read either, and a group's name is not reportable (ADR 0093 § 1).
A blob's `blob-meta` is covered by its `K_blob`.

## The envelope

`0x03 ‖ salt(16) ‖ nonce(12) ‖ AES-256-GCM(K_field, plaintext, AAD = 0x03 ‖ "v1" ‖ context)`. Its own type byte, so a
field never opens as a plain seal (`0x02`) or a wrap (`0x01`), nor they as a field. The salt is not in the AAD: another
salt derives another key and the tag fails. The server stores the envelope as the opaque base64 it was: no column, no
contract field, and a copy it makes between columns (a guest's display name onto its primary bucket,
`MediaStore.primarySealedNameFor`) carries the salt with it. The salt adds 16 bytes; the constant-length profile
picture is exactly 21908 base64 characters.

## Rules

1. **A field purpose seals only through the field seal.** `seal` refuses it by type and at runtime; the typed tables
   are the guard (ADR 0072 Decision 6), no grep is.
2. **One key per write.** The salt is fresh on every write, so a rename, a move's re-seal and a share's re-issue each
   seal under a new key, even of the same text.
3. **A `K_field` opens that revision of that field and nothing else** — not a sibling's field, not the field's next
   revision, not `K_parent` (HKDF is one-way).
4. **No reader of the old format.** Pre-release, every instance was reset; a ciphertext sealed before the field seal
   no longer opens.

## What the server learns

A 16-byte random salt per write, and the 16 bytes of length it adds. Nothing about any key.

## Acceptance criteria

An enabler carries no coverage file (`validate_coverage.py` reads use-cases only), so each criterion names the tests
that verify it. The crypto ones run in `packages/crypto`'s check, the wire one in `web/`'s.

- **field-key-one-field** — the key handed over for node A's name opens A's name and neither sibling B's name, nor A's
  node key, nor A's meta: `fieldSeal.test.ts` "B1: the key handed over for node A's name opens A's name (the
  control)", "B1: … does not open sibling B's name", "B1: … reaches neither A's node key nor A's node-meta".
- **field-key-one-revision** — one revision's key does not open the next, even of the same text: `fieldSeal.test.ts`
  "B2: the key of one revision of A's name does not open the next revision, even of the same text".
- **field-key-only-through-the-field-seal** — no purpose sits in two tables, and `seal`/`open` refuse a field purpose
  by type and at runtime; a field envelope does not open through `open`, nor a seal through `openField`:
  `v1.conformance.test.ts` › "field seal (#field_seal, ADR 0093 § 9)" › "no purpose sits in two tables", "seal and
  open refuse a field purpose, by type and at runtime", "a field envelope does not open through open, nor a seal
  through openField".
- **field-key-on-the-wire** — a create sends its name as a field envelope that does not open as a plain seal under
  the parent's key: `tree.wire.test.ts` › "a create sends its name as a field envelope (ADR 0093 § 9)".

_No user-impact section: an enabler's value reaches the user through the use-cases it serves — here the report
ADR 0093 decides._

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 9 (the decision), § 1 (the report that will hand a `K_field` over).
- [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) (what the AAD binds; Decision 2, where a key unique
  to its object is the binding — the three share fields).
- [en.platform.field-editor](en.platform.field-editor.md) — the sibling model: who last wrote each field.
