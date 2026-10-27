# ADR 0111 — An invitee grants itself, a rotator re-keys its own scopes, and a signed key chain anchors who is who

- Status: Accepted 2026-10-27 (owner, the design as a whole, its four owner decisions under § Consequences included);
  **decision 11 is built** (2026-10-27: any member re-keys, a member's write pulls its path forward, a drop box stays
  an owner's — ADR 0109 point 7 states it), **nothing else of it is.** What is built besides is what [ADR 0020](0020-grant-distribution-invite-wrap.md),
  [ADR 0024](0024-scope-key-rotation.md), [ADR 0028](0028-identity-authenticity-tofu-verification.md),
  [ADR 0060](0060-key-rotation-after-compromise.md) and [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
  describe; each of them marks in place the part this record replaces, and that part stays true of the running code
  until this record is built.
- Date: 2026-10-27

## Context

The owner, as a user, wants three things:

1. **To rotate their own identity** — identity keypair, Master Key and Secret Key together, gated on password and
   Secret Key.
2. **To see** when another user's identity is new, changed or verified — as information, never as a block.
3. **Never to be stopped from putting content into a shared space** by other members' identity state. In a large
   space nobody can check everyone.

Goal 3 holds structurally for a post today: posting needs only the current epoch key and wraps to nobody. Whatever is
decided here must not break it.

What today's model makes hard:

- **It is spread over nine records** — ADR 0020, 0024, 0025, 0028, 0058, 0060, 0072, 0104, 0109 — and no page holds
  the whole picture. In the session that led here, both the owner and the assistant got it wrong.
- **A tenant admission tag binds no user and no key.** It is an HMAC over the scope, the epoch and an admission id
  (ADR 0020 move 6; `admissionTarget` in `src/web/src/features/spaces/grant.ts` returns no target for a tenant). It
  proves *"a key-holder admitted someone"*. Against an honest server it stops a session without an unlocked vault from
  leading to a grant. Against a malicious operator it adds little: the operator redeems a genuine admission with a key
  of its own (ADR 0020 § Consequences). Whether it can also **copy** an existing tenant admission onto a membership it
  invents, and have a worker wrap to it, is a **hypothesis no test has checked** — this record does not rest on it
  and does not claim it (§ What stays open).
- **After a rotation the rotator is outside every shared scope** until another key-holder accepts the new key
  (ADR 0060 Decisions 4 and 6). An owner who rotated and then commits a scope's rotation is refused by every other
  member as `granter_key_changed` once that member has accepted the new key (ADR 0028 § What is still open).
- **Only an owner re-keys a node** (until decision 11, built 2026-10-27). The re-key endpoint refused anyone else (`RekeyAccess.check`, `not_scope_owner`),
  and a scope's *re-keying* status is served to owners only (`ScopesStore.kt`, the `rekeying` field of a scope row).
  A write into a node not yet re-keyed is `409 node_rekey_pending` (ADR 0109 point 6). So after any rotation a member
  cannot write into an existing Memory until an owner has been online long enough — goal 3 broken.

Facts the design rests on, read in the code on 2026-10-27:

- Access knows only `READ` and `WRITE` as the kind of operation (`src/backend/core/.../membership/ScopeAccess.kt`).
  There is no read-only member role: every accepted member may write. Placing or renaming is refused only into a
  guest's identity folder (`NodesHandler.kt`, the placement rule of [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 11).
- The Secret-Key proof public key appears only in the registration requests (`AccountRegistration` in
  `contracts/api/tenants.openapi.yaml` and `contracts/api/setup.openapi.yaml`). No response serves another user's.
- A grant names its recipient by user id only (`wrappedGrant`, `grantSignature`, `userId` in
  `contracts/api/scopes.openapi.yaml`), so the server cannot tell which key a grant was wrapped to.
- `RotationStore.applyRotation` keeps no copy of the old identity's private material.
- The server mails a member invite (`SmtpTenantInviteMailer`), so the operator knows every link it mails; the secret
  rides the URL fragment.

## Decision

### Invitation: a placeholder member that the invitee replaces

1. **An invite is a placeholder member with an invite key the owner signed.** Creating an invite mints an **invite
   keypair** on the owner's client. The owner **signs the invite public key with their identity**; the server stores
   the public half and that signature. The private half travels in the link together with the **owner's identity
   fingerprint**, optionally sealed under a **password or a generated code** the owner passes on outside veyq. The
   fingerprint sits **inside** that seal, and the invitee's client **derives the invite public key from the private
   one** rather than taking it from the server — otherwise an operator swaps fingerprint and key in a mailed link. The
   password is stretched by a strong KDF, since the operator holds the grant it protects and can guess offline; a
   generated code, or a link the owner hands over directly with the secret in the fragment, is preferred.
2. **The invite holds a grant like a member.** It receives a grant of the current epoch wrapped to the invite public
   key, and every rotation re-wraps to it like to any member. **Revoking an invite is removing a member** — for a group
   that rotates. Every open invite has a **fixed expiry**: hygiene, which ends the window in which the link can be
   redeemed, not what the link already decrypts (§ Consequences, the revoked invite).
3. **The invitee grants itself and writes a join record.** On accept the invitee's client checks the owner's
   signature on the invite key against the fingerprint from the link, opens the grant with the link (and the
   password), and **grants the epoch to its own new identity**. The server deletes the invite key (single use), and
   the next rotation no longer includes it. The client then writes a **join record** into its tenant profile
   ([ADR 0023](0023-per-tenant-member-identity.md), sealed under the tenant key): its identity public key and its
   Secret-Key proof public key, signed by **the invite private key** and by the new identity. The owner's signature on
   the invite key and the invite key's signature on the join record form a chain **owner → invite → newcomer** that
   neither the operator nor any holder of the tenant key, current or former member, can forge while the owner is not
   compromised. **The trust root of a tenant is its owner**, handed to each newcomer through the link; several owners
   each root their own invitees.
4. **A group needs no invite key.** The target already has an account, so the group owner wraps directly to the key
   the target's join record anchors in the tenant (decision 12).
5. **Gone with it:** the tenant admission tag (ADR 0020 move 6), the grant-delivery worker for new members, and the
   *awaiting access* state (ADR 0020 move 3b).

### Identity rotation: self-service, the scopes included

6. **One publish replaces identity, Master Key and Secret Key**, gated on password and Secret Key and checked by the
   server ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2). The Secret Key is no longer kept across a
   rotation: a rotation is also the remedy for a Secret Key that leaked (inbox #559), and a new Secret Key is a new proof
   key.
7. **Each rotation is a step of a key chain, signed twice** — by the **old identity** and by the **old Secret-Key
   proof key** — naming the new identity and the new proof public key and carrying the hash of the step before it.
   A stolen logged-in device holds the identity but never the Secret Key, so a thief, even with the operator's help,
   cannot forge a step; only someone holding password **and** Secret Key can. The first step's old proof key is the one
   the join record anchors (decision 3). The chain — public keys and signatures, nothing secret — is stored **once**,
   next to the identity, and **served only to users who share a tenant with its person** (server-enforced: an honest
   server's rule; the operator knows the rotations anyway). It has one **head**, the same in every tenant.
8. **The rotator mints the next epoch of every scope it is in, itself**, and grants it to every member and to its own
   new identity — never to the old one. Another member accepts that epoch because its chain link
   `symWrap(E_{n+1}, E_n)` opens under the `E_n` it holds (the minter held `E_n`; the operator does not), and because
   the granter's new key is reachable by the doubly signed chain from the key it anchored.
9. **An omission is allowed, and visible.** The rotator grants every member whose key it can anchor and may leave out
   one whose key is anomalous (not reachable from its anchor, decision 12). The server refuses a rotation that leaves
   anyone out **silently**: every omission is declared and stored as **pending** for that member. The server cannot
   judge an omission — the anchor is not server-side — so **any other member whose client anchors the omitted member's
   key grants them the new epoch**. A narrow grant delivery for pending members therefore stays; a rotator who omits
   everyone delays access, it does not remove it.
10. **The server refuses any grant to a key that is no longer the account's current identity.** Concurrent rotations
    keep ADR 0024 Decision 4's rule: the server takes only `current + 1`, the loser gets `409 stale_epoch` and rebuilds
    from the newest grant with a fresh key. Each rotator excludes by its own choice, so both exclusions survive, and
    "no grant to a retired key" closes the case of a stale directory read wrapping to the old identity.
11. **Any member re-keys a node, eagerly; a member's write pulls its path forward; a guest waits.** A new epoch alone
    does not lock the old identity out of what is added inside existing folders and Memories, which is why
    [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) re-keys every node and holds writes into a node not
    yet re-keyed. What changes is only **who** runs it.
    - **A re-key is not governance.** It changes neither membership nor content nor who holds a link: re-wrapping a
      share keeps it working, and only a re-issue ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md))
      is its creator's decision. So **any member holding the current epoch may re-key a node**.
    - **It stays eager.** ADR 0109's reason for rejecting a purely lazy re-key still holds: every write path of every
      application would have to re-key ancestors, and a guest cannot, holding no key above its root. Any member's
      client continues an unfinished re-key in the background, which also spreads across devices work that today
      lands on one (inbox #553).
    - **A member's write pulls its path forward**, in one central place rather than in every write path: the client
      catches `409 node_rekey_pending`, re-keys that node and its lagging ancestors, and retries the write. For a member
      a write is delayed by the re-key, never refused.
    - **A guest gets `409` until its node is re-keyed**, and waits while no member is online. Goal 3 is about members;
      for a guest it holds once the re-key has reached its node. Writing under the old key instead would let the old
      identity read it.
    - **Re-key where you may write.** A re-key re-seals names, meta and EXIF, and the server cannot check that the
      plaintext stayed the same, so it is a write path onto the node. Every accepted member may write today (§ Context),
      so a member's re-key gains a member nothing — **except** in a drop box's per-guest-identity folders, where
      members place nothing (ADR 0080 Decision 11). **Those folders, and the drop box with its drop key, are re-keyed by
      an owner only**; their guests wait for an owner rather than for any member — the case where only guests write
      anyway. The drop box is the kind whose guests get identity folders (Depot's), not every node with a drop key: a
      Memory or chapter gets one with its first uploader link, which any member may make, and members write into it,
      so it is any member's to re-key. Its drop key's replacement (ADR 0109 point 5) needs only what such a member
      holds — the node's old key opens the drop private key, which opens each guest's `K_up`, and each link's
      `K_share` comes from its recovery copy — and writes nothing a member could not already write there: a member sets
      the drop key with a first link, seals its public half under every link it makes, and trashes any guest's upload.
      Owner-only there, a member's photo into an ordinary Memory with an uploader link would wait for an owner — goal 3
      broken.

### Trust: facts live with the person, verdicts are signed, the device keeps a counter

12. **Two kinds of record, kept apart, and one anchor.**

    | Where | What | Who writes | Protects against |
    |---|---|---|---|
    | Next to the identity, served to tenant co-members | **key chain**: rotation steps (old → new, signed by old identity + old Secret-Key proof key), hash-chained | the person only | a forged step needs password + Secret Key |
    | Tenant profile, sealed under the tenant key | **join record** (the identity and proof key at joining, signed by the owner-signed invite key) | the person, through its invite | the operator and tenant-key holders cannot forge the owner → invite → newcomer chain |
    | Tenant profile, sealed under the tenant key | **verifications** ("Carol verified Bob at head X", signed by Carol), each a separate statement, not chained | each verifier | nobody can forge another's verification; the operator can only withhold one |
    | Tenant profile, sealed under the tenant key | per rotation: **the outgoing key's verifications in this tenant**, signed by the new key | the rotator | a thief with a retired key cannot add a verification after the rotation |
    | Each device (synced, sealed under the Master Key) | **highest key-chain head seen** per person | the device | rollback or withholding of a step it has seen newer |

    - The **key chain** holds only what the person does themselves and changes only on a rotation. The join record is
      not part of it: it is per tenant and only anchors which point of the chain the person joined with.
    - **Verifications** each name the head they confirm. Not chained, so two people verifying Bob at once fork
      nothing, and an unsynced verification never moves a head. A rotation moves the head, so every verification of
      the old head stops counting by itself — that **is** "a rotation drops *verified*", with no rule of its own.
    - **A key is anchored** when it is reachable from the join record by the key chain.
13. **A rotation closes the outgoing key's verifications, per tenant.** In each tenant the rotation writes into the
    rotator's tenant profile, sealed under that tenant's key, the list of the verifications the outgoing key made in
    this tenant, signed by the **new** identity and the new Secret-Key proof key and naming the hash of the rotation
    step. A verification signed with a retired key counts only if the list signed by that key's successor names it.
    Without it a thief holding Carol's old key — and, from her device, the tenant key — could sign "Carol verified
    Mallory" after her rotation and, with the operator's help, place it as if older; the thief cannot sign the list,
    lacking the new key. Carol re-checks nothing.
    - The server-wide chain step says nothing about verifications, and each tenant sees only its own list, so no member
      learns in how many tenants the rotator is.
    - A withheld list only makes the old verifications stop counting: a display falls away, nothing opens.
    - A tenant the rotation did not reach gets its list later: the rotator holds the new key and the tenant key.
14. **The safety-number compare also shows the key-chain head.** Comparing does not change it, so two people see the
    same head unless one of them is missing a step — a withheld step shows up there. Every pair that compares is a
    manual witness, at almost no cost.
15. **What a user sees, and the one gate.**
    - **Display only:** *new* (first contact), *not verified*, *verified by …* (verifications of the current head),
      *new identity, signed by the previous key*. *Verified* returns after a rotation only through a new compare.
    - **The one gate:** a key not reachable from the anchor is **not wrapped to** — a rotation declares it pending
      (decision 9) — and is shown as an anomaly. A legitimate rotation is always signed, so the gate never fires for
      one, and it never blocks anybody's write.
    - **An unknown person** — no join record, a member from before this model being the case — is trusted on first
      use, with no block: here the operator is assumed friendly, and verification by code is the user's.
    - **Per tenant.** Verifications in tenant A count for everyone in A, and only in A; a user in A and B is anchored
      and verified per tenant.
16. **The device's counter is the pin store's job.** The synced, Master-Key-sealed store of
    [ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) holds, per person, the
    highest key-chain head this account has seen, under ADR 0028 Decision 7's rollback rules. What it replaces is the
    pinned fingerprint's role as the thing a key change is judged against, and the personal *verified* mark, which
    becomes the signed verification of decision 12.
17. **Key Transparency stays later, in a design of its own; this record stays KT-ready.** A hash-chained key chain
    with one head, and that head in the compare, is what a later log commits to without a format change.

### Migration

18. **An owner's client signs a join record for each existing member**, over the key it pins for that member today and
    that member's Secret-Key proof public key, so every member gets an anchor rooted at an owner at once. Existing
    members anchor the owner by their current pin of the owner. That one step is trust on first use: the owner's pin
    may itself be a first-use pin, or made only now for a member the owner never wrapped to, and the proof public key
    comes from the server unchecked, once — it is not served today (§ Context), so the directory gains that field.
    If two owners sign join records for the same member over different keys, the contradiction is shown as an anomaly
    and the member's key counts as **not anchored**: no wrap to it, declared pending in a rotation, never a block on
    anyone's write.

## Consequences

- **Goal 3 holds for members through every rotation**, their own included: posting needs only the current epoch, a
  write into an existing node waits at most for its own path's re-key, and a rotator is never outside a scope. For a
  guest it holds once the re-key has reached its node.
- **The first-use gap closes inside a tenant with a password-protected invite** — against the operator and against
  current and former members, alone or together — **as long as the owner is not compromised.** Without a password the
  operator, which mails the link, can redeem the invite itself, and the gap is as wide as today; no worse.
- **The owner is the trust root — accepted (owner, 2026-10-27).** A compromised owner can issue invites for keys of
  their choosing; but an owner can already admit anyone legitimately, so a compromised owner compromises membership
  in any model. Contradictions between owners are anomalies (decision 18). Owners cross-signing each other, or a member
  distrusting one owner, is a later extension, not part of this record.
- **A revoked or expired tenant invite keeps what it was granted — accepted, with a short expiry (owner,
  2026-10-27).** The tenant does not rotate on removal ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)), so link
  and password stay a key to the epochs up to the revoke — useful only with a dump of the ciphertext. Same class as a
  removed tenant member without a re-key. A group rotates on revoke anyway.
- **Verifications forged between theft and rotation — accepted (owner, 2026-10-27).** The list a rotation writes per
  tenant (decision 13) cannot tell them from the verifier's own: the thief held the same key and the Master Key. They
  belong to the compromise window, like anything else done with the identity then.
- **Verifications become visible to a tenant's members.** Today a *verified* mark is a private entry of the verifier's
  sealed pin store; under decision 12 it is a signed statement every member of that tenant reads, which is what lets
  *verified by …* count for everyone there. The operator reads none of it (sealed under the tenant key); a removed
  member, holding the tenant key the tenant does not rotate on removal, keeps reading these records with the operator's
  help — writing a forged one no longer helps them, because of the owner chain.
- **An overview "people I share with"** — per tenant: anchored, verified by whom, rotated — becomes cheap: it is the
  member list plus these records. Today there is only the per-person profile and the per-space member lists.
- **The rotator's device carries the rotation's grants**: one epoch, one chain link and one wrap per member, for every
  scope the rotator is in, the tenant included. That is the `O(N)` re-grant ADR 0060 Decision 5 accepts on a compromise,
  moved from the completing owner to the rotator — and for the tenant it is the burst
  [ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2 meant to spread across devices. What is spread now is the far
  larger node re-key (decision 11); a wrap per member is one asymmetric seal each.
- **What it adds to the server, all opaque:** an invite's public key and the owner's signature on it, and a grant to
  that key; a key chain beside each identity, read only by co-members; the join records, verifications and closing
  lists, sealed in the tenant profile; the Secret-Key proof public key in the directory; a pending marker per declared
  omission; and a grant that names the key it is wrapped to, without which decision 10 cannot be checked. The server
  learns that an invite exists and that an account rotated, as it does today, and nothing about who verified whom.
- **What it changes in the records it builds on**, each marked there in place: ADR 0020 moves 3, 5 and 6 (admission
  and delivery); ADR 0024 Decision 4's admission tags, Decision 5's re-grant of new members and § Guarantee's queued
  account rotation; ADR 0028 Decisions 2, 5 and 6 and the role of the pin; ADR 0060 Decision 2's Secret Key kept across a
  rotation and Decisions 4, 6 and 7; ADR 0104's entry content; ADR 0109 point 7's owner-only re-key; ADR 0080 Decision 11
  gains the owner-only re-key of a drop box. ADR 0109's rejection of a lazy re-key stands.
- **The pinned bundle, the safety number and its vectors change** (decision 14, inbox #560): the compare carries the
  head, and the proof public key joins what a person is known by.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Every check here is a client's: against an active
  operator it holds only on a client the operator does not serve, and honest-operator elsewhere — the limit any model
  shares, including on a first contact anchored through the invitation.

## Alternatives rejected

- **The rotator may not re-admit itself** (ADR 0060 Decision 4). Its reason was that an attacker holding the old
  identity would mint the new epoch for their own new key and shut the person out. The doubly signed step (decision 7)
  answers it: an attacker without the Secret Key cannot make a step anyone accepts, and the server takes no grant to a
  retired key (decision 10). Keeping the rule would keep the rotator outside every shared space until somebody else
  acts, and the human re-admission with it.
- **Keeping the Secret Key across a rotation** (ADR 0060 § Consequences, so the Emergency Kit stays valid). It leaves a
  leaked Secret Key irreplaceable (inbox #559). That the rotation invalidates the kit it required is the point: it
  issues the new one.
- **A signature of the old identity alone on a rotation.** A rotation happens when that identity may be stolen, so its
  signature proves the least exactly then.
- **Owner-only re-key** (ADR 0109 point 7 before decision 11). Members could not write into an existing Memory until an owner is
  online — goal 3 broken.
- **A purely lazy re-key** — still rejected for ADR 0109's reason: every write path of every application would re-key
  ancestors, and a guest cannot.
- **A check that a re-key preserved the plaintext.** The commit overwrites the old ciphertext, so no client has
  anything left to compare.
- **A tenant rotation on every revoked invite** — its `O(N)` cost for a reach that needs a ciphertext dump. **Delivering
  the epoch only after accept** — it brings back the grant-delivery worker.
- **Showing the outgoing key's verifications for review during the rotation** — an extra step in securing the account,
  for verifications the reviewer cannot tell from their own anyway.
- **Chaining verifications into the key chain.** Two people verifying the same person at once would fork it, and an
  unsynced verification would move a head.
- **One hash over all verifications in the rotation step** — no tenant can recompute it, seeing only its own. **A hash
  per tenant in the step** — tells every member the rotator's tenant count. **A Merkle root over per-tenant hashes in the
  step** — works, but needs a fixed tree depth, since a proof's length gives the count away, and a random salt per leaf,
  since a guessable list such as an empty one is testable from a sibling hash.
- **Key Transparency now.** What it would still add is mainly the fork, and for that it needs a witness that is not the
  operator: a central one conflicts with self-hosting, and a gossip among members' clients runs over the same server.
  That witness is the real work, and it gets a design of its own.
- **ADR 0020's two rejected alternatives, now taken, and why their reasons no longer decide.** *The invite carries the
  wrapped key* was rejected because it makes the link key-bearing. But the link already admits whoever redeems it, and
  a key-holder's device then wraps to them with no further human decision (ADR 0020 move 3b), so it was in effect the
  key already; what is new is a link that keeps its reach after a revoke, accepted above, and the optional password
  raises it above today. *The owner signs the admission* was rejected because a worker learns who the owner is from the
  server. A newcomer now learns the owner's fingerprint from the link, inside its seal; an existing member, from its own
  pin of the owner (decision 18). ADR 0020's further point — that a removed owner's old signatures would stay valid
  without signed removals — is not answered here (§ What stays open).

## What stays open

- **Nothing is built.** The CRs that build it are reworked from this record; until then ADRs 0020, 0024, 0028, 0060,
  0104 and 0109 describe the code.
- **The admission-tag replay hypothesis is untested.** Before any doc claims that an operator can copy a tenant
  admission onto an invented membership today, a test must show it.
- **A membership with no join record after the migration.** Decision 15 trusts an unknown person on first use. Once
  decision 18 has given every member a join record, a membership without one can only be one the server made. Whether a
  rotation should still wrap to it, or treat it as an anomaly, is not decided — and how much the admission tag stops of
  that case today depends on the untested hypothesis above.
- **Who counts as an owner is the server's word.** A member checking a join record knows the tenant's owners from the
  server's role list and its own pins; roles are not signed. A former owner, or a member the server presents as owner,
  can with the operator's help root a key of their choosing. It is the class of ADR 0020's departing-member residual,
  and closing it needs signed roles and removals — the membership protocol ADR 0020 declined.
- **Withholding** is detected only by a device that saw a newer step, or by two people comparing heads (decision 14);
  a first-time reader cannot tell on its own (the fork, until Key Transparency).
- **A malicious server on a retry** may re-list a revoked invite to the other of two concurrent rotators — the
  honest-operator class, as today.
