---
title: Key hierarchy
description: Every key veyq uses, layer by layer — how it is made, what wraps it, who holds it, when it rotates and what losing it means.
sources:
  - docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md
  - docs/crosscutting/security-model.md
  - docs/crosscutting/keys-grants-and-trust.md
  - docs/features/account/enablers/en.account.key-model.md
  - docs/decisions/0012-content-key-hierarchy.md
  - docs/decisions/0024-scope-key-rotation.md
  - docs/decisions/0025-forward-secrecy-policy-per-scope.md
  - docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md
  - docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md
---

veyq encrypts content under layered envelopes: an account's keys open a scope's key, the scope's key opens
each node's key, and a node's key opens its blobs. Every layer is a separate random key wrapped by the one
above it, so the server stores only wraps it cannot open. This page walks the four layers (account, scope,
content, sharing) and explains why the layering is cut where it is.

:::note[What this page does not restate]
Byte formats are normative in the conformance-pinned suite
[`packages/crypto/src/v1.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/v1.ts) and its
vectors [`contracts/crypto/vectors/v1.json`](https://github.com/raddatzk/veyq/blob/main/contracts/crypto/vectors/v1.json).
Every wrap and seal named below also binds its purpose **and the object it is stored as** (the scope, node,
blob, share or member, plus the epoch or revision stored with it), so a ciphertext served for another object fails to
open. That rule is [ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md).
The primitives are on [Cryptography](/veyq/security/cryptography/).
:::

:::caution[Which operator this holds against]
The client enforces everything on this page. On a web client the operator serves, that means it holds against
a **passive** operator only: an active one can serve code that reads the keys after they are opened
([ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)). How much
holds against an active operator on a client it does not serve is
[security-model § What holds today](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve);
see also [Threat model](/veyq/security/threat-model/).
:::

## The shape

```
unlock method ──KEK──▶ Master Key ──▶ identity private key
                                          │ opens a signed grant
                                          ▼
                              scope key K_SC (epoch n) ──chain──▶ epochs n-1 … 0
                                          │ wraps
                                          ▼
                       node key (folder / Memory / chapter …) ──▶ child node keys
                                          │ wraps
                                          ▼
                                    K_blob ──▶ chunk frames

share link:  K_share (URL fragment) ──wraps──▶ one node key
             K_SC ──seals──▶ recovery copy of K_share
             K_share ──seals──▶ drop public key   (uploader links: no node key at all)
```

Two keys sit beside the chain and decrypt nothing: the **auth keypair** (login) and the **Secret-Key proof
keypair** (authorizes credential changes).

## Why it is layered this way

**Cheap grants.** A member is admitted by sealing one key, the scope's current epoch key, to their identity.
From it they open the whole tree down the chain: every node's key is wrapped under its parent's key and its name
sealed under a field key derived from it, `K_SC` at the root ([ADR 0012](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0012-content-key-hierarchy.md),
[ADR 0080](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 2). Adding a member never touches content.

**The share grain is cryptographic.** Node keys are **random, not derived** from `K_SC`. A share is one
additional, independent wrap of one node's key, which reveals neither `K_SC` nor any sibling's key, because no
parent key is derivable from a child's. ADR 0012 rejected the alternative, one content key per scope, because
sharing one Memory would then hand out the whole scope's key and leave the other Memories' confidentiality to
server gating instead of cryptography. A chapter carries its own key for the same reason: adding per-chapter
sharing later would have forced a client-side re-encryption of every chapter's media.

**A rotation re-keys the tree and re-encrypts nothing.** `K_SC` rotates forward into a new epoch: one random key,
one chain link and one re-wrap per remaining member
([ADR 0024](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0024-scope-key-rotation.md)). The device that
commits the epoch then re-keys every live and trashed node of the scope, top-down, one atomic commit per node: a
fresh node key minted at the new epoch, every field sealed under the old key re-sealed under it, each `K_blob`
re-wrapped and kept, no store object read or written ([ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)). Per node that is one key and its children's
entries, per medium its blob wraps and two field seals, all symmetric and all in the database: a Chapter of 1,000
photos is about 5,000 seals, about 20 requests. Until a node is re-keyed, a write into it is refused
`409 node_rekey_pending`: a member's client re-keys that node's path itself and retries, a guest's waits; a write
stating a node key from before is refused (`409 stale_epoch`). Any member holding the new epoch may re-key, a
Memory's drop key included, except a drop box, a guest's identity folder and what lies below one, which only an owner
re-keys
([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decision 11). ADR 0024 rejected
full re-encryption on every removal (gigabytes re-keyed per leave, for content that is existing but unopened) and a
flat set of independent epoch keys (each new member would need one wrap per epoch; the backward chain makes it one).
What the re-key leaves in the excluded party's reach: what existed at the rotation, and every link they could
rebuild, until it is re-issued (Layer 4).

**But removal is still `O(N)`, so whether it rotates is a per-scope policy.** A new epoch must be wrapped to
every remaining member's identity, because nobody holding only the old epoch can derive the new one. That is a
hybrid post-quantum wrap per member, concentrated on the removing device and linear in the scope's size.
[ADR 0025](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0025-forward-secrecy-policy-per-scope.md)
therefore decides per scope type:

| Scope | On a member removal | Why |
|---|---|---|
| Group | Always rotates, in the same request as the removal | Small `N`, deliberate and often sensitive sharing |
| Tenant | Membership deleted (instant `403`), **no** rotation; an explicit re-key exists for a compromise | The primary, potentially large scope; rotation buys protection only against an operator colluding with the ex-member |
| Personal | Never (nobody to rotate away from) | One member |

Deleting the membership already stops an honest operator from serving anything new. Rotation adds protection
against a **malicious operator who feeds a removed member post-removal ciphertext**. ADR 0025 weighs that
narrower threat against an `O(N)` burst on every routine tenant removal, and cites 1Password's shared vaults, which
also revoke by access control and leave re-keying as an explicit, rare action. The one escalation the ADR names
for cheap rotation at large `N` is a key tree (MLS/TreeKEM); it was not taken.

**Unlock is decoupled from content.** One Master Key is wrapped separately per unlock method, so adding or
removing a method writes or deletes one wrap and never re-encrypts anything. Login is a separate keypair, so
breaking it yields a session and operational metadata, never content
([ADR 0003](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0003-operator-blind-password-auth.md)).

## Layer 1: account keys

Normative detail: [en.account.key-model](https://github.com/raddatzk/veyq/blob/main/docs/features/account/enablers/en.account.key-model.md).

What each account key is for:

- **Secret Key** — the high-entropy half of every cold unlock. Mixed into the password unlock, it makes a password
  cracked from a database dump useless on its own; it is also the recovery factor, and it proves to the server that
  a credential change comes from the account's holder (the proof keypair below).
- **Unlock KEK** — opens exactly one wrap of the Master Key. There is one KEK per unlock method; the Master Key itself is one per account.
- **Master Key** — the account's root. It opens the identity private key, and it seals the account's own private
  state (the identity-pin store, the device register, private node pins).
- **Identity keypair** — the account as other members see it. Scope keys are sealed to its encryption key; its
  signing key signs every grant the account issues. The safety number is computed over it; a contact pins the head of
  the account's key chain, which names it.
- **Auth keypair** — signing in. It proves knowledge of the password to the server without sending it, and opens
  nothing.
- **Secret-Key proof keypair** — authorising credential changes. It proves possession of the Secret Key to the
  server without sending it, and opens nothing.

| Key | How it is made | What wraps it | Who holds it | When it rotates | What losing it means |
|---|---|---|---|---|---|
| **Secret Key** | 256 random bits, generated on the device at account birth | Nothing. It is printed in the Emergency Kit and **never stored server-side** | Only the user (Emergency Kit). A device uses it once, at the first sign-in there, and stores it nowhere; it keeps its own automatically written wrap instead (`DEVICE`, or `DEVICE_KEY` with **Stay signed in on this device** on). A device linked by QR never sees it ([ADR 0059](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0059-device-linking.md)) | Only when the account is secured: the rotation mints a new one and issues a new Emergency Kit, saved and confirmed before anything is published; the old kit stops working. A password change, a reset and a change of the sign-in address keep it | Recovery narrows to another enrolled device, and the account can no longer be secured ([ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md) Consequences) |
| **Unlock KEK** (one per method) | Derived fresh on the device each unlock (see the table below), then discarded | Not wrapped; never stored or sent | Nobody at rest | Changes with its inputs (a new password, a new device key) | Its one wrap no longer opens; other methods still do |
| **Master Key** | A 32-byte symmetric key, generated on the device at registration | One copy per unlock method (`MasterKeyWrap`), each under that method's KEK: still one key, many locks | The unlocked client; the server stores only the wraps | Only in a compromise rotation, together with the identity. A password change or reset keeps it | Losing **every** unlock path loses the account's content; there is no escrow |
| **Identity keypair**: a hybrid post-quantum encryption key and an Ed25519 signing key | Generated on the device at registration | Private half: only under the Master Key. Public half: published in the key directory | The unlocked client; the public half is visible to fellow members | Only together with the Master Key | Same as the Master Key: it exists only wrapped under it |
| **Auth keypair** (Ed25519) | Derived from the password with Argon2id over a salt derived from the account address | Not wrapped; the private key is derived on demand, never stored or sent. The server stores the public key | Derived on the client at sign-in | Whenever the password changes (re-derived, public key re-registered) | A forgotten password: one of the recovery paths below. A cracked one: a session, never content |
| **Secret-Key proof keypair** (Ed25519) | Derived from the Secret Key **alone** | Not wrapped. The public half is registered at account birth | The client, whenever it holds the Secret Key | With the Secret Key: securing the account registers the new public half under a signature of the current one | It decrypts nothing; without it the server refuses rotation, password change, recovery completion and a change of the sign-in address |

The identity key's two halves do different work. Others seal scope grants to its encryption key; its signing key
signs every grant this user issues. Its public bundle is what the safety number is computed over, and the key chain's head that a contact pins names it
([ADR 0028](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md);
[Protocols](/veyq/security/protocols/)). The Master Key also seals the account's **identity-pin store** — one entry
per pin, at an id derived under a key of its own (`veyq/v1/identity-pin-index`), and a head over all of them — and the
**device register**, which is why a rotation re-seals both in the same transaction, every pin entry moving to the new
key's ids.

### Unlock methods

**Why one KEK per method.** The Master Key is random, and everything the account can read hangs on it. It is
never stored as itself: the server keeps only wraps of it, one per unlock method, each under that method's own KEK.
So changing the password, or adding or removing a device, rewraps 32 bytes and re-encrypts nothing else; losing one
method leaves the others working; and the server, holding only wraps, can open none of them.

| Method | KEK input | Notes |
|---|---|---|
| `PASSWORD` | Argon2id over the Secret Key and the password | The mandatory floor; cannot be removed. The Secret Key is why a password cracked from a database dump opens nothing |
| `RECOVERY` | Argon2id over the Secret Key alone | The server **withholds** this wrap until a mailed one-time token and a Secret-Key proof signature are both presented ([ADR 0054](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0054-password-recovery-two-factor-release.md)). There is no separate recovery code |
| `DEVICE` | Argon2id over a device-bound key's HMAC output and the password | Written when you sign in with **Stay signed in on this device** off. The device key is a non-extractable WebCrypto HMAC key, so no script can export it (the bind it computes can still be copied once); device and password are two factors, so a stolen device alone opens nothing |
| `DEVICE_KEY` | A random key held on the device | Written when you sign in with **Stay signed in on this device** on. Passwordless: a stolen *unlocked* device opens content |

**The two device methods, in plain terms.** The first time you sign in on a device you type your password **and**
your Secret Key. After that the device keeps one wrap of its own, and which one depends on the sign-in toggle
**Stay signed in on this device**:

- **Off — `DEVICE`.** This device unlocks with your password alone; you never type the Secret Key here again. Its
  KEK mixes the password with an HMAC from a key the browser holds and cannot export, so the device and the password
  are two factors: a stolen device without the password opens nothing.
- **On — `DEVICE_KEY`.** A random key held by the device wraps the Master Key, with no password at all, so the
  device opens straight in across a restart. A stolen device that is unlocked, or that holds this key, opens content.
  That is why it is an explicit choice.

A device holds one of the two, never both: changing the toggle replaces one wrap with the other. **Untrust this
device** deletes it, and the next unlock there needs the Secret Key again.

Each KEK is derived with a stored salt, used to open its one wrap, then discarded. **A password change, a reset and a rotation keep only the two cold wraps** (`PASSWORD`,
`RECOVERY`) and delete every other one, so a device-held KEK does not outlive the credential it was enrolled under.
A passkey method is designed but not built; see [Roadmap](/veyq/security/roadmap/).

A linked device receives the Master Key and the identity private key on one human confirmation, but never the
password or the Secret Key ([ADR 0059](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0059-device-linking.md)).
That gap is what the next section relies on.

### The Secret-Key proof keypair

An Ed25519 keypair derived from the Secret Key **alone**; its public half is registered at account birth. Every
request that mints a cold credential — a key rotation, a password change, completing a recovery, changing the
sign-in address — must carry a signature with it, and the server checks that signature against the registered
public key ([ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md)
Decision 2). It changes only when the account is secured: the rotation registers the new public half under a
signature of the current one and a signature of the new one, which proves the new key is held. So the server can check that the requester holds the Secret Key without ever seeing it, and someone who
has only the password, a session, or a linked device (which never receives the Secret Key) cannot replace the
account's keys or credentials. It is a check the server runs, so it binds every caller except the operator.

### Rotation after a compromise

The Master Key, the identity keypair and the Secret Key rotate **together, always**, as one step of the account's
**key chain**: the identity private key exists only under the Master Key, so a leaked identity implies a leaked
Master Key, and someone holding the password and the Secret Key is otherwise indistinguishable from the holder
([ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md) Decisions 1 and 2). The
step is signed by the **old identity and the old Secret-Key proof key**; a phished linked device holds the identity
but never the Secret Key, so it cannot rotate first and lock the holder out, and it cannot sign a step anyone accepts.
The new Emergency Kit is saved and confirmed before anything is published.

**Access moves first, security after.** One transaction appends the step and re-grants every scope the account
holds a grant on, in every tenant, at its current epoch, to the new identity; the grants were staged just before and
are verified under that identity ([Protocols](/veyq/security/protocols/#7-the-key-chain-and-securing-an-account)). Then
the account mints each scope's next epoch itself, from any of its devices, and any member re-keys the nodes
([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) decisions 6–11). No retired private
key is kept anywhere; the chain keeps the retired public keys. Other members accept the new identity because the
chain reaches it from the head they pinned, with no review. Every `DEVICE` and `DEVICE_KEY` wrap dies. Rotation is
**forward secrecy, not repair**: what was already read stays read.

### Recovery

Every path back runs through the Secret Key or a device that already holds the keys: another enrolled device;
the Emergency Kit plus the password on a fresh device; or the Emergency Kit alone, which opens the withheld
`RECOVERY` wrap after a mailed link. Because recovery restores the Master Key itself, every scope comes back at
once. Losing every path is the documented, accepted residual loss
([security-model § Recovery](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#recovery--no-escrow)).

## Layer 2: scope keys

A scope (personal, group or tenant) has one content key, `K_SC`, realized as a forward chain of **epochs**
`E0, E1, …`.

| Key / artifact | How it is made | What wraps it | Who holds it | When it rotates | What losing or leaking it means |
|---|---|---|---|---|---|
| **Epoch key `E_n`** | 32 random bytes, minted on a client: `E0` when the scope is born, each later epoch by the client that rotates | Its predecessor sits under it: the chain link `symWrap(E_{n+1}, E_n)`, stored server-side. Each member receives it in a **grant** | Every member whose grant is at that epoch or later; holding `E_n` derives every earlier epoch, never a later one | Per scope policy (table above), and in every scope of an account that rotates after a compromise | Leaked: the holder opens every node key and name under that epoch and earlier (see the residual below). A member whose grant is behind (a rotation left them out) waits until any online key-holder delivers one |
| **Grant** | A seal of the epoch key to the member's identity encryption key with the hybrid post-quantum KEM, purpose `scope-grant`, bound to the scope and epoch | It is the wrap. It carries the **granter's Ed25519 signature** over the scope id, the scope's kind, the epoch, the recipient, a digest of the recipient's identity bundle and a digest of the grant ciphertext | Stored as ciphertext on the membership; opened by the recipient's identity private key | Born with the membership — a newcomer's own self-grant at an invite's accept, the adding owner's at a group add — and re-issued at each new epoch: by the rotator for the members it keeps, otherwise by any online key-holder (grant delivery) | — |
| **Invite key** | An identity-shaped bundle (X-Wing + Ed25519) the inviting owner's client derives from a 32-byte random seed, one per member invite; the owner signs its public half (`invite-key` statement: tenant, invite, SHA-256 of the bundle, expiry) | The seed travels only in the invite link, sealed (purpose `invite-link`) under a key Argon2id derives from a generated 10-character code or an owner-chosen password | The server stores the public half, the owner's signature and a grant of the tenant's current epoch wrapped to it; the invitee's client derives the private half from the seed | Never; every tenant rotation re-grants each open invite or declares it pending, and the invite's key and grant are deleted when it is accepted, revoked, re-sent or expires | Leaked with its code before the accept: the holder opens the tenant epochs the invite was granted and can redeem it. After a revoke the link and code still open the epochs up to the revoke (the tenant does not rotate on removal) |
| **Join record** | Written by a newcomer's client at an invite's accept: its identity public key, its Secret-Key proof public key and the owner-signed invite, signed by the invite key and by the new identity (`join-record` statement) | Sealed as the field `member-join-record`, bound to the tenant and the member, under the tenant epoch key the invite's grant carried | Every holder of that tenant epoch; the tenant's first owner has none | Never rewritten | Not a key; it records who joined through which owner's invite, and every member's client reads it as the anchor of that member's key in the tenant |
| **Verification** | Written by a member's client after a matching compare: the verifier's identity signs the `verification` statement (tenant, verifier, subject, the subject's key-chain head, the verifier's own head) | Sealed as the field `member-verification`, bound to tenant, verifier, subject and epoch, under a tenant epoch key | Every holder of that tenant epoch; the server keeps one row per verifier and subject, addressed in the clear | Re-signed by the verifier's new identity when they secure their account; a newer compare replaces it | Not a key; it counts only while it names the subject's current head under the verifier's current key |

**The grant signature closes a gap on the receiving side.** A seal to a public key needs only that key, so
without a signature the server could plant a scope key of its own choosing. A grant whose signed kind is
`PERSONAL` opens only if it is self-signed under the vault's own signing key. A shared scope's grant opens under
the granter's pinned key, with a first-seen granter pinned on first use — in a group of the active tenant only when the
granter's join record anchors them there
([ADR 0028](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md)
Decision 6). The server stores the signature and does not verify it.

**The invite link anchors a newcomer to its owner.** The invite key is minted and signed on the owner's client, and
its seed travels sealed together with the owner's fingerprint, so an invitee whose client derives the key from the
seed and checks the owner's signature against that fingerprint trusts nothing the server served unchecked. The seal
is mandatory because the server mails the link: without the code, the operator holds the link but cannot open it
except by guessing offline. The newcomer then grants the epoch to itself, so no other member has to be online
([ADR 0111](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decisions 1–5). Every other wrap to a member in a tenant — a rotation's re-grant, a grant delivery, a group add —
goes only to a key the recipient's key chain reaches from the step their join record names, rooted at an owner this
account anchors; a non-owner without a join record is never wrapped to, and an owner without one (the tenant's first)
is trusted on first use, from then on only where the chain reaches from the head this account pinned
([Known limitations](/veyq/security/known-limitations/#identity-keys-tofus-first-use-gap)).

**Freshness has one mark here.** Each device remembers the highest epoch it has held for each scope. It never
seals under, and never opens a grant below, that mark, whatever current epoch the server reports. A member who
never learnt of a newer epoch at all (a withheld rotation, a fork) is outside that mark
([ADR 0024](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0024-scope-key-rotation.md) Decision 6 and
§ Guarantee).

## Layer 3: content keys

Every node, whether a folder, a Memory, a chapter or an application's own kind, carries its own random content key.
In Memories the key of a Memory or a chapter is called `K_node`.

| Key | How it is made | What wraps it | Who holds it | When it rotates | What losing or leaking it means |
|---|---|---|---|---|---|
| **Node key** (`K_node`, a folder's key) | 32 random bytes from the client that creates the node; never derived from `K_SC` or the parent | Its **parent's** content key, or `K_SC` at the node's epoch for a scope-root node. A share adds independent wraps of the same key (Layer 4) | Every scope member, down the chain; every viewer or contributor link at or above the node | At **every rotation** of `K_SC`: the re-key replaces it with a fresh key minted at the new epoch, which the node's `epoch` records ([ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)). A move or rename re-wraps it under the (new) parent's key, an `O(1)` write that leaves the subtree untouched | Leaked: the node's whole subtree, including names and everything created under it until the scope next rotates |
| **`K_blob`** | Random, one per blob | The content key of the blob's **direct** owning node (a chapter's media under the chapter's key). Media a public uploader added sit under that uploader's own key instead (Layer 4) | Whoever holds that node's key | Never. A rotation re-wraps it under the node's new key; moving media between a chapter and its Memory re-wraps only `K_blob` | That one blob |
| **Chunk frames** | The blob's bytes, encrypted in frames under `K_blob` with AES-256-GCM | — | — | — | — |

A node's **directory entry** (its sealed name and its blinded slug token) lives under its **parent's** key, so
whoever holds a folder's key reads every name and opens every key below it, and nothing beside or above it. The
name, like every field, seals under a key of its own derived from that key (below).
`K_blob` also seals the blob's type, upload name and modification time (`blob-meta`). Because each `K_blob` is
random, identical bytes become different ciphertexts. Dedup across a space is declined
([ADR 0013](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md#5-one-stored-object-for-an-equal-file-inside-one-node-nothing-wider)),
so the server learns no content equality there. Inside one node, a cover or banner equal to a gallery item is a second
blob row on that item's stored object, its `K_blob` wrapped again for the new row; the server sees which object it
shares. Frame layout:
`sealFrame` / `openFrame` in [`v1.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/v1.ts),
vector `chunk_frame`.

**Field keys.** Every text or icon field (a node's name and meta, a medium's metadata, a bucket's, a guest's or a
share's name, a share's note, a member's name and picture) seals under `K_field = HKDF-SHA256(K_parent, salt,
"veyq/v1/field/" + purpose)`. `K_parent` is the key the field belongs under: the parent's key for a name, the node's
own for its meta, `K_share` for a share's fields, a guest's identity key for its own, the tenant's scope key for a
member's.
The 16-byte salt is fresh on every write and travels inside the ciphertext, so one revision's `K_field` opens that
revision of that one field and nothing else, and reveals nothing of `K_parent`. It is the key a report of one field
will hand over
([ADR 0093](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9). A group's name and a space's icon are not fields: each keeps a key per scope epoch (a pending invitee is handed
the name's).

:::caution[Between rotations, a node key stays]
A rotation replaces every node key of the scope. Until the next one, a removed member or former guest who kept a
node's key, **and** obtains its later ciphertext (from a database dump or a colluding operator), can read what is
added under that node: a tenant member's routine removal does not rotate
([ADR 0025](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0025-forward-secrecy-policy-per-scope.md)), nor
does revoking a link. After a rotation, a link they could rebuild still opens the new key until it is re-issued.
Against an honest server, deleting the membership or the share is the cut
([ADR 0080](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
Decision 5; [ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) § Consequences). See [Known limitations](/veyq/security/known-limitations/).
:::

## Layer 4: sharing keys

A public link is a capability. The secret half lives in the URL fragment, which the browser does not send to the
server.

| Key | How it is made | What wraps it | Who holds it | When it rotates | What losing or leaking it means |
|---|---|---|---|---|---|
| **`K_share`** | 32 random bytes, minted by the client per share | Not wrapped for the link: it travels in the URL fragment. Members recover it from the recovery copy below. Under it sit the share root's node key (`symWrap(K_share, K_node)`, viewer and contributor links only), the field keys of the root's name, the note and the private label, and the password's reveal copy | Anyone with the link; every member of the scope | **Never** for the life of a link; a rotation re-wraps the root's new node key under it, so the link keeps showing what is added. Cutting someone off a link means **re-issuing** it: a new id and a fresh `K_share` ([ADR 0075](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)) | Leaked: the shared subtree. Revoking the share stops new access on an honest server; what was already downloaded stays read |
| **Recovery copy** (`sealed_share_key`) | `K_share` sealed under `K_SC` at the scope's **current** epoch, bound to scope, node, share and epoch; mandatory on every share | `K_SC` | Every scope member; **no link** | Not re-sealed by a rotation: a copy older than the scope's current epoch makes its link re-issue-only ([ADR 0075](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0075-a-link-a-departed-member-could-copy-is-reissued.md)) | — |
| **Drop keypair** (hybrid post-quantum KEM) | Minted by the client of the member who creates a Memory's first uploader link; one per Memory (a chapter has its own), not per share | Private half: under the node's key. Public half: under `K_share` on each uploader share | Private: everyone who reads the node. Public: uploader guests | At **every rotation** of `K_SC`: a new keypair, the private half under the node's new key, the public half re-sealed under each uploader link's `K_share`, every guest identity key re-sealed to it, the old private half discarded ([ADR 0109](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) point 5) | Private half leaked: what uploaders added to that node until the next rotation, and after it what a guest identity from before adds (below) |
| **Uploader identity key** | Random, minted by an uploader guest's client | Wrapped to the drop public key | That guest, and every reader of the node | Never. A rotation re-seals it to the new drop public key; only a re-issue starts every guest over | That identity's uploads: their blob keys, metadata and display name |

**Why the recovery copy is sealed under `K_SC` and not `K_node`.** A viewer or contributor link holds `K_node`;
that is what the link exists to hand over. A copy under `K_node` would let that link's holder, with a database dump,
open every sibling share of the same Memory: its `K_share`, and through it the password, the private label, the note
and the whole link, since the dump holds every share id in clear. `K_SC` is the one key no share carries
([ADR 0036](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0036-editable-share-note-recoverable-share-key.md)
point 4). The accepted price is that every scope member can derive `K_share`, which gives them nothing they could
not already read. A member removed later may still hold the old links; re-issuing a link is what cuts them off.

**Why an uploader link carries no content key.** An uploader link is the one link handed to people who are meant not
to see. A write-only role enforced only by the server would not survive a dump. So the link carries the node's drop
public key instead of a wrap of `K_node`. Each guest seals under an identity key of its own, wrapped to that drop key:
one asymmetric wrap per identity, not per blob. A link plus a full dump reads only what that link's guests uploaded
([ADR 0031](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0031-uploader-public-share.md) point 7). The
drop key belongs to the node rather than to the share so that every reader finds the private half where they
already look.

**A rotation does not revoke a link.** A shared node key is wrapped to `K_share`, not to `K_SC`; the re-key re-wraps
the root's new key under the link's unchanged `K_share`, so the link keeps showing what is added, and whoever held an
older epoch can rebuild that `K_share` from the recovery copy. The re-key leaves recovery copies at their epoch, so
every link from before reads re-issue-required; links are revoked or re-issued separately. A guest write must also
present a token derived from `K_share` ([ADR 0076](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0076-a-guest-write-proves-it-holds-the-links-key.md)),
so a share id alone opens no write. That is a layer-1 control: the operator, being the server, could write anyway.
