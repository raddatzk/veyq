# ADR 0111 — An invitee grants itself, a rotator re-keys its own scopes, and a signed key chain anchors who is who

- Status: Accepted
- Built: yes
- Date: 2026-10-27
- Model: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md)

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
- **A tenant admission tag bound no user and no key.** It was an HMAC over the scope, the epoch and an admission id
  (ADR 0020 move 6, removed by decision 5). It proved *"a key-holder admitted someone"*. Against an honest server it
  stopped a session without an unlocked vault from leading to a grant. Against a malicious operator it added little:
  the operator redeemed a genuine admission with a key of its own. Whether it could also **copy** an existing tenant
  admission onto a membership it invented was a hypothesis no test checked; this record never rested on it, and it
  died untested with the tag.
- **After a rotation the rotator was outside every shared scope** until another key-holder accepted the new key
  (ADR 0060's human re-admission, replaced by decisions 6–10). An owner who rotated and then committed a scope's
  rotation was refused by every other member as `granter_key_changed` once that member had accepted the new key.
- **Only an owner re-keyed a node.** The re-key endpoint refused anyone else (`not_scope_owner`), and a scope's
  *re-keying* status was served to owners only. A write into a node not yet re-keyed is `409 node_rekey_pending`
  (ADR 0109 point 6). So after any rotation a member could not write into an existing Memory until an owner had been
  online long enough — goal 3 broken.

Facts the design rests on, read in the code:

- Access knows only `READ` and `WRITE` as the kind of operation (`src/backend/core/.../membership/ScopeAccess.kt`).
  There is no read-only member role: every accepted member may write. Placing or renaming is refused only into a
  guest's identity folder (`NodesHandler.kt`, the placement rule of [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 11).
- The Secret-Key proof public key appeared only in the registration requests (`AccountRegistration` in
  `contracts/api/tenants.openapi.yaml` and `contracts/api/setup.openapi.yaml`); no response served another user's.
- A grant named its recipient by user id only (`wrappedGrant`, `grantSignature`, `userId` in
  `contracts/api/scopes.openapi.yaml`), so the server could not tell which key a grant was wrapped to.
- `RotationStore.applyRotation` keeps no copy of the old identity's private material.
- The server mails a member invite (`SmtpTenantInviteMailer`), so the operator knows every link it mails; the secret
  rides the URL fragment.

## Decision

### Invitation: a placeholder member that the invitee replaces

1. **An invite is a placeholder member with an invite key the owner signed.** Creating an invite mints an **invite
   key** on the owner's client: an identity-shaped bundle derived from a 32-byte seed (`inviteKeyFromSeed`,
   `src/packages/crypto/src/invite.ts`). The owner **signs the invite public key with their identity** — the statement
   `invite-key` over the tenant, the invite id, SHA-256 of the invite bundle and the expiry in Unix seconds; the
   server verifies that signature under the caller's stored identity key before it mints or re-issues (`400
   invalid_request` otherwise, so a session without the owner's vault mints no invite — on an honest server, the job
   the admission tag did) and stores the public half, that signature and the signer's key as it signed (so a later
   rotation of the owner does not void the invite). The seed travels in the link together with the **owner's key-chain head**
   and account (decision 19), **always sealed** (`#<token>.<sealed>`, seal purpose `invite-link`, bound to the tenant and the invite)
   under `Argon2id(secret, salt)` at the production profile, where the secret is a **generated code** — 10 Crockford
   base32 characters, 50 bits, shown once in the invite dialog — or a **password the owner chooses** (the account
   password's length floor). The owner passes it on outside veyq. The seal is mandatory because the server mails the
   link and mail is the only channel ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)), so the operator
   holds every link it mails: an unsealed link would hand it the invite. The server appends the sealed part to the
   mailed link and keeps no copy. The head sits **inside** that seal, and the invitee's client **derives the
   invite public key from the seed** rather than taking it from the server — otherwise an operator swaps head and key
   in a mailed link. Against the operator, who can guess offline, the margin is 50 bits behind Argon2id.
2. **The invite holds a grant like a member.** It receives a grant of the current epoch wrapped to the invite public
   key (its recipient is the invite id, and it names the key by `recipientIdentityDigest`); the server takes it only
   at the current epoch, under the tenant scope's lock (`409 stale_epoch`), and keeps the owner's own grant beside it
   as the invite's **anchor** (decision 19). **Every rotation of the tenant re-grants
   each open invite or declares it pending** (`inviteGrants`, `pendingInviteIds`): the rotator's client re-grants one
   whose owner signature verifies under the stored signer key and whose signer passes its anchor check, and declares the
   rest; the server refuses a rotation that leaves an open invite out (`409 rotation_incomplete`). Every member reads
   the open invites' keys for this (`listOpenInvites`, never an email). **Revoking an invite is removing a member** —
   accept, revoke, supersede, expiry and the erasure of the signer's or the addressee's account null its key, grant and
   anchor in the statement that ends it; a reissue is a supersede and a new invite (new key, link and code). A group has no
   invites; the group counterpart, a declined or rescinded add proposal, queues the group's rotation (decision 4).
   Every open invite has a **fixed expiry**, the instance's `veyq.tenant.invite.ttl` (served to the owner's client as
   `memberInviteTtlSeconds`, signed by the owner, checked by the server within a minute): hygiene, which ends the
   window in which the link can be redeemed, not what the link already decrypts (§ Consequences, the revoked invite).
3. **The invitee grants itself and writes a join record.** On accept the invitee's client opens the seal with the
   code and checks the invite against the owner's chain walked from the head in the seal and the epoch against the
   owner's anchor grant (decision 19), opens it, and **grants the epoch to its own new identity** (a self-grant naming its key). It proves holding
   the invite key with an `invite-accept` signature the server verifies (`403 invite_proof_invalid`), and names the
   signer key its join record carries, which must still be the invite's (`409 invite_changed`, decision 20). The server
   nulls the invite key and grant (single use), and the next rotation no longer includes it. The client then writes a
   **join record** into its tenant profile ([ADR 0023](0023-per-tenant-member-identity.md)): its identity public key
   and its Secret-Key proof public key with the owner-signed invite, signed — the statement `join-record` — by **the
   invite private key** and by the new identity, sealed under the tenant epoch key the invite's grant carried (field
   purpose `member-join-record`, column `veyq_tenant_profile.sealed_join_record`, served as
   `ScopeMember.sealedJoinRecord`). The owner's signature on the invite key and the invite key's signature on the join
   record form a chain **owner → invite → newcomer** that neither the operator nor any holder of the tenant key,
   current or former member, can forge while the owner is not compromised. **The trust root of a tenant is its
   owner**, handed to each newcomer through the link; several owners each root their own invitees; the tenant's first
   owner, born through the Server Owner's owner invite, has no join record. Every member reads the join records as
   the anchor of who is who in the tenant (decisions 12 and 15). A member accept by an existing account signs in with
   the Secret Key, because the join record names its proof key.
4. **A group needs no invite key.** The target already has an account, so the group owner wraps the group's current
   epoch directly to the target's key, after the anchor check — the key the target's join record anchors in that
   tenant (decision 12). A `pendingConsent` proposal holds that grant from the add on, consenting changes nothing about keys, a
   group rotation re-grants a pending proposal like a member, and a decline or an owner's rescind of a proposal queues
   the group's rotation, since the target held the key.
5. **Gone with it:** the tenant admission tag (ADR 0020 move 6), the grant-delivery worker for new members, and the
   *awaiting access* state (ADR 0020 move 3b). A newcomer holds the tenant key from its accept on. What stays is the
   delivery to a member whose grant is behind — one a rotation skipped, or a newcomer whose invite a rotation declared
   pending.

### Identity rotation: self-service, the scopes included

6. **One publish replaces identity, Master Key and Secret Key, and applies the self-grants staged before it**, gated on
   password and Secret Key and checked by the server ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2).
   The Secret Key is no longer kept across a rotation: a rotation is also the remedy for a Secret Key that leaked, and a
   new Secret Key is a new proof key.
   - **The stage.** Before the publish the client stages one **self-grant** per scope the account holds a grant on —
     personal, tenant and group, in every tenant, a group invitation it has not answered included: the scope's
     **current** epoch key, granted to the new identity, naming it (`recipientIdentityDigest`) and signed by it. The
     stage declares the new identity bundle (`openRotationStage`; one stage per account, a second open replaces the
     first) and takes the grants in chunks of at most 256 (`putRotationStageGrants`); the server verifies each under the
     identity the stage declares at upload (`422 self_grant_invalid`). The same stage carries the re-signed open
     invites (decision 20) and the endorsements of the join records the account rooted (decision 21). **Nothing staged is applied before the
     publish**: a stage is ciphertext and scope ids the server already holds as grant rows, and it expires an hour after
     it opened (`veyq.account.rotation.stage.ttl`, server-observed) and is purged.
   - **The publish names the stage and its digest** (`stageId`, `stagedGrantsDigest`) inside the statement the
     Secret-Key proof signs, and publishes the identity that stage declares. Its one transaction takes each scope's row
     lock and applies the stage **only if every grant-carrying membership of the account is staged at its scope's
     current epoch, or declared unreadable** (`unreadableScopeIds`: a grant this device cannot open, which keeps its
     grant untouched); otherwise `409 rotation_plan_changed`, nothing is written, and the client re-stages the delta.
     From the commit on, every scope the account was in holds its current epoch for the new identity, so a lost answer,
     a closed tab, a failed next epoch or a space in another tenant strands nothing.
   - *Why staged rather than carried in the publish:* no cap bounds an account's
     memberships, and any co-member can add an account to groups (a pending proposal holds a grant from the add on). A
     real self-grant is 1,845 bytes on the wire, and 477 fit beside a full pin store under the default
     10,485,760-byte request limit. A membership cap would be a limit another member can push someone into; smaller
     grants or a larger limit only move the ceiling. The stage bounds nothing and keeps the grants in the publish's
     transaction.
7. **Each rotation is a step of a key chain, signed twice** — by the **old identity** and by the **old Secret-Key
   proof key** — naming the new identity and the new proof public key and carrying the hash of the step before it.
   A stolen logged-in device holds the identity but never the Secret Key, so a thief, even with the operator's help,
   cannot forge a step; only someone holding password **and** Secret Key can. The chain begins at the account's birth
   with an unsigned **genesis** naming the birth identity and proof key, whose proof key is the one the join record
   anchors (decision 3). The chain — public keys and signatures, nothing secret — is stored **once**, next to the
   identity (`getKeyChain`), and **served only to the person, to users who share a tenant with them, between two
   accepted moderators of the instance, and — the inviting owner's — to the holder of an open invite's token** (decision 19) (server-enforced: an honest server's rule; the operator knows the rotations
   anyway). The moderators are readers because a holder of the moderation key anchors the moderator it delivers to
   ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)), and two moderators may share no
   tenant; the roster is the instance's, so they already know each other. It has one **head** — the hash of its newest
   step — the same in every tenant, and every read that serves another user's key to a check serves that head beside it.
8. **The rotator mints the next epoch of every scope it is in, itself**, and grants it to every member and to its own
   new identity — never to the old one. Another member accepts that epoch because its chain link
   `symWrap(E_{n+1}, E_n)` opens under the `E_n` it holds (the minter held `E_n`; the operator does not), and because
   the granter's new key is reachable by the doubly signed chain from the key it anchored. The publish leaves a
   pending row per scope it re-granted; `rotateScope` accepts a caller who is not an owner of the scope only with such
   a row at the scope's current epoch and no removal (a removal stays an owner's act), so any unlocked device of the
   account, one opened with the new Emergency Kit included, finishes each scope. A commit by someone else in between
   meets the row as well, because it could not grant the old identity (decision 10).
9. **An omission is allowed, and visible.** The rotator grants every member whose key it can anchor and may leave out
   one whose key is anomalous (not reachable from its anchor, decision 12). The server refuses a rotation that leaves
   anyone out **silently**: every omission is declared and stored as **pending** for that member. The server cannot
   judge an omission — the anchor is not server-side — so **any other member whose client anchors the omitted member's
   key grants them the new epoch**. A narrow grant delivery for pending members therefore stays; a rotator who omits
   everyone delays access, it does not remove it. A member's grant behind the current epoch **is** the stored pending
   marker, and the publish clears the delivery declines that target the rotating account, so a worker that could not
   anchor the old key tries the new one ([keys-grants-and-trust § Rotating a scope](../crosscutting/keys-grants-and-trust.md#rotating-a-scope)).
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
      client continues an unfinished re-key in the background, which also spreads the work across devices instead of
      landing it on one.
    - **A member's write pulls its path forward**, in one central place rather than in every write path: the client
      catches `409 node_rekey_pending`, re-keys that node and its lagging ancestors, and retries the write. For a member
      a write is delayed by the re-key, never refused.
    - **A guest gets `409` until its node is re-keyed**, and waits while no member is online. Goal 3 is about members;
      for a guest it holds once the re-key has reached its node. Writing under the old key instead would let the old
      identity read it.
    - **Re-key where you may write.** A re-key re-seals names, meta and EXIF, and the server cannot check that the
      plaintext stayed the same, so it is a write path onto the node. Every accepted member may write (§ Context),
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
    | The tenant's verifications (`veyq_verification`, one row per verifier and subject), sealed under the tenant key | **verifications** ("in this tenant, Carol at her head Y verified Bob at his head X", signed by Carol's identity), each a separate statement, not chained | each verifier, into their own rows only | nobody can forge another's verification; the operator can only withhold one |
    | Each device (synced, sealed under the Master Key) | **highest key-chain head seen** per person | the device | rollback or withholding of a step it has seen newer |

    - The **key chain** holds only what the person does themselves and changes only on a rotation. The join record is
      not part of it: it is per tenant and only anchors which point of the chain the person joined with.
    - **A key is anchored** in a tenant when the key chain reaches it from the step that installs the identity **and**
      the proof key the person's join record there names, and that record verifies owner → invite → newcomer under an
      owner who is anchored in turn (decision 15). `loadTenantView` (`src/web/src/features/account/tenantTrust.ts`)
      reads a tenant's directory and opens its join records with the tenant's epoch keys; `readJoinedChain`,
      `anchorIdentity` and `anchorChainKey` (`identityPins.ts`) walk the chain from the joined step, and only a key at
      or after it counts there.
    - **Verifications** each name the head they confirm. Not chained, so two people verifying Bob at once fork
      nothing, and an unsynced verification never moves a head. A rotation moves the head, so every verification of
      the old head stops counting by itself — that **is** "a rotation drops *verified*", with no rule of its own.
    - **A verification is written only into its verifier's own row** by `putMyVerification`, which keys the row by the
      authenticated caller and never by a field of the body, and every member of the tenant reads them through
      `listVerifications` (`contracts/api/tenants.openapi.yaml`). Its statement (purpose `verification`) names the
      tenant, both user ids and both heads, signed by the verifier's identity; it is sealed as the field
      `member-verification` under a tenant epoch the verifier holds, bound to the tenant, the verifier, the subject and
      that epoch, so a row moved to another slot neither opens nor verifies (`src/packages/crypto/src/verification.ts`,
      vector `#verification`). A member's removal from the tenant deletes every row naming them; an erasure and a
      tenant's purge take theirs by cascade.
    - **It counts** while it names the subject's **current** head and verifies under the verifier's **current**
      identity, with the verifier's current head as the head it names, and the verifier anchored in the tenant
      (`countVerifications`). An older head of either, or a retired key, is not shown.
13. **A rotation re-signs the outgoing key's verifications with the new key, per tenant.** Before the publish the
    rotator's client collects, in every tenant it can open, its own verifications that verify under its current
    (outgoing) identity at its current head; after the commit it signs each again with the **new** identity, naming the
    same subject and subject head and its new head, and writes it over the old row, one row per write
    (`collectMyVerifications` and `resignVerifications` in `tenantTrust.ts`, around the publish in `rotateKeys.ts`).
    Since a verification counts only under its verifier's current key (decision 12), a statement signed by a retired
    key counts nowhere. Without that rule a thief holding Carol's old key — and, from her device, the tenant key —
    could sign "Carol verified Mallory" after her rotation and, with the operator's help, place it as if older; signed
    by the retired key, it does not count, and the thief cannot sign as the new key. Carol re-checks nothing.
    - **The set is read before the publish**, while the outgoing key is still the account's, so a statement a thief
      writes after the publish is never among what is re-signed.
    - **Only the new identity signs**, not also the new Secret-Key proof key: whoever holds the new identity can sign a
      fresh verification as the current key anyway, so a second signature would defend nothing — and a later device of
      the account holds no Secret Key.
    - **Why re-signing and not a closing list** (a list of the outgoing key's verifications per tenant, signed by the
      new keys, which a retired key's verification would have to appear on): the publish is one request body, and no
      cap bounds the tenants an account is in nor the members of a tenant, so lists in it have no upper bound; a list as
      a write of its own per tenant is still unbounded within the tenant. Re-signing is one bounded row per write, needs
      no second record type, and gives the same guarantee: what the retired key signs after the rotation counts nowhere.
    - The server-wide chain step says nothing about verifications, and each tenant sees only its own rows, so no member
      learns in how many tenants the rotator is.
    - **Best-effort.** A tenant this device could not open carries nothing, and a write lost after the commit leaves a
      row signed by the retired key: that verification stops counting — a display falls away, nothing opens. Nothing
      re-signs it later; a new compare restores it.
14. **The safety-number compare also shows the key-chain head.** Comparing does not change it, so two people see the
    same head unless one of them is missing a step — a withheld step shows up there. Every pair that compares is a
    manual witness, at almost no cost. Beside the identity's 30-digit safety number the profile shows a **chain code**
    of 10 digits (two groups of 5, the same rendering over the head: `v1.chainCodeDigits`, vector
    `fingerprint.chain_code`), and the QR carries both, `veyqid:2:<fingerprint digest>:<head>`. A `veyqid:1` code is read
    as one from an older app, whose holder is asked to update; it never matches. Both must match, and a match writes the
    verification of decision 12 (`verifyPerson`), which signs only while the compared key and head are still the ones
    the directory serves and this device anchors in the tenant. A device shows its own chain code only once the chain
    the server serves it ends at the identity its vault holds, so a step withheld from both sides cannot match.
15. **What a user sees, and the one gate.**
    - **Display only**, per tenant: *new* (this account first pinned them less than 14 days ago), *not verified*,
      *verified by …* (the names of the members whose verification of the current head counts) and *new identity,
      signed by the previous key* (the newest step of their chain is a rotation, with its date). *Verified* returns
      after a rotation only through a new compare. A member list badges *verified*, *new* and the anomaly; *not
      verified* is the ordinary state and shows nothing there.
    - **The one gate:** a key not reachable from the anchor is **not wrapped to** — a rotation declares it pending
      (decision 9) — and is shown as an anomaly. A legitimate rotation is always signed, so the gate never fires for
      one, and it never blocks anybody's write.
    - **Who is anchored, per tenant** (`loadTenantView`'s standing): a member whose join record opens, verifies owner →
      invite → newcomer, names as its signer another member the server lists as an **owner**, and carries a signer key
      that is anchored in this tenant in turn — a retired key of that owner's chain only with the owner's endorsement
      by their current key (decision 21). An owner the server lists with **no** join record is a
      **root** (the tenant's first owner) and is trusted on first use, by the pin; a newcomer anchors its inviting owner
      through the head in the link (decision 19). **Anyone else is an anomaly**: a non-owner without a join record, a record that
      does not open or verify or whose signer is no anchored owner, a pending proposal, a cycle of signers. Every
      newcomer writes a join record and no release predates it, so a non-owner without one is a membership only the
      server can have made.
    - **A pin not on the anchored chain is replaced**, once: when the chain the join record anchors does not hold the
      pinned head and is at least as long as the pinned chain was (the entry keeps the index of its head), the pin
      moves to the anchored head, marked, and the profile says *"The key you saw before wasn't theirs. Their invitation
      confirms this one."* An owner-rooted join record is stronger than a first-use pin, and this is the case of an
      operator who substituted a key at a first contact. A shorter chain is an anomaly instead — it may be a rollback
      of a head this account saw — and a pinned head **on** the chain must carry the pinned key.
    - **Where the tenant cannot be read** (this device cannot open or list it): a wrap there fails closed, as an
      anomaly; the check of a grant's granter falls back to the pin alone (§ What stays open).
    - **Per tenant.** Verifications in tenant A count for everyone in A, and only in A; a user in A and B is anchored
      and verified per tenant. The pin store stays per account (decision 16).
16. **The device's counter is the pin store's job.** The synced, Master-Key-sealed store of
    [ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) holds, per person, the
    highest key-chain head this account has seen, under ADR 0028 Decision 7's rollback rules. It replaces the pinned
    fingerprint's role as the thing a key change is judged against, and the personal *verified* mark, which is now the
    signed verification of decision 12: an entry holds the head, its index in the chain, the first-seen time and the
    mark of a replacement (decision 15), and no state (ADR 0104 decision 2).
17. **Key Transparency stays later, in a design of its own; this record stays KT-ready.** A hash-chained key chain
    with one head, and that head in the compare, is what a later log commits to without a format change.

### Anchors that survive a rotation

19. **An invite carries its anchor: the owner's head in the link, the owner's grant beside the carried one.**
    - The link's sealed payload (`invite-link` v2) is `0x02 ‖ lp(seed) ‖ lp(signerHead) ‖ lp(signerUserId)`. A head
      commits the identity **and** the proof key at its step, so a later key of the owner is reached only through steps
      the old Secret-Key proof key signed, which a thief of a device never holds. A fingerprint pins only the identity:
      an operator holding a stolen device's identity could serve an invented genesis beside it with a proof key of its
      choosing (§ What stays open records why the first design ended invites at the owner's rotation for that reason).
    - The invite keeps the owner's own grant as its **anchor** (`anchor_wrapped_grant`, `anchor_epoch`,
      `anchor_grant_signature`): written at mint, moved only when a tenant rotation carries the invite **and** the
      rotator is the invite's signer under the invite's signer key, nulled when the invite ends. Any other rotation
      writes the carried grant alone.
    - The preview serves the signer's whole key chain, the anchor and the tenant's epoch links in `(anchorEpoch,
      grantedEpoch]`. The newcomer (`checkInvite`, `src/packages/crypto/src/invite.ts`) requires the seed's key to be
      the served invite key, the seal's signer to be the served one, the served chain to pass through the link's head
      and end at the served signer key, the `invite-key` and the anchor's `grant` signature to verify under that key,
      and the carried epoch to peel down through the served links to exactly the anchor's key. The operator holds no
      anchor key, so it cannot make a link that ends there: a tenant key it chose under a granter it fabricated is
      refused (`epoch_unlinked`), and so is anything a retired owner key signed (`signer_chain_mismatch`).
    - An existing account judges the inviting owner against its pin with that chain and pins the walked head with its
      index (`anchorServedChain`, `identityPins.ts`); a carried grant's granter is not pinned — the link to the anchor,
      not its key, vouches for that grant.
20. **An owner's securing re-signs its open invites**, through the stage of decision 6. The plan
    (`getRotationPlanInvites`) lists every open member invite the account signed with its current signatures; the
    client re-signs, with the **new** identity, the `invite-key` statement and the anchor's `grant` statement only when
    both current signatures verify under the outgoing key, and otherwise **declares** the invite (both null) — so an
    invite the server minted itself never gets the new key's signature. Each staged re-signature is verified under the
    stage's identity (`422 invite_resign_invalid`). The publish requires every open invite the account signed to be
    staged at its current anchor epoch (`409 rotation_plan_changed`), writes the re-signed ones (signer key = the new
    identity, and the carried grant's signature where it is the anchor) and **ends** a declared one. The account's own
    tenant rotation then carries the re-signed invites and moves their anchor to its new epoch. An accept names the
    signer key its join record carries (`signerIdentityDigest`), refused unless it is still the invite's (`409
    invite_changed`); the client re-reads the preview and re-checks once with the invite key and the link facts it
    kept — so no join record lands naming a key the publish just retired.
21. **A join record a retired owner key signed counts only through the owner's endorsement.** An endorsement is the
    statement `join-endorsement` over the tenant, the member, the owner and SHA-256 of the member's **sealed** join
    record as stored, signed by the owner's identity (`signJoinEndorsement`, vector `#join_endorsement`), served as
    `ScopeMember.joinEndorsement`. A reader (`loadTenantView`) accepts a record whose signer key is not the owner's served
    current key only when that key is on the owner's chain at or after their join, the owner's served key is anchored in
    the tenant, and the endorsement verifies under it. The server records who rooted each join
    (`veyq_tenant_profile.join_signer_user_id`, at the accept); the owner's securing reads that list
    (`getRotationPlanJoinRecords`), endorses with the **new** identity each record that verifies, names it as signer and
    either names its outgoing key or carries an endorsement by it, declares any other (`null`), and stages them (`422
    join_endorsement_invalid`); the publish requires every record the account rooted to be staged against the record
    stored now (`409 rotation_plan_changed`) and writes the endorsements. So a record a thief signs with the retired key
    after the rotation counts nowhere, and the owner's members stay anchored — atomically, unlike the best-effort
    re-signing of verifications (decision 13). Neither list enters `stagedGrantsDigest`: an endorsement or a re-signed
    invite hands nobody a key, and each carries the new identity's own signature, checked at upload.
22. **The rotation's one body is bounded by the contract's caps.** The publish re-seals the whole pin store (ADR
    0104), so its body is bounded by the store's 25,000 entries beside every other field at its cap: 10,101,123 bytes
    against the default 10,485,760 (2026-10-28). `scripts/validate_rotation_shape.py` computes that bound from the
    contract and fails on a field without a cap or a total above the server's limit; the pin entry's length is exact
    (296 characters). The pin store stays in the publish: staging it would only matter for a higher cap.

### A new epoch links to the key the device held

23. **A device keeps the newest epoch key it held per scope, as a commitment, and opens a later epoch only through its
    links.** The epoch floor (ADR 0024 Decision 6) refuses a lower epoch *number*, not an unlinked *key*: an operator
    can raise a scope's epoch and serve a member a grant at the new one around a key of its choosing — sealed to the
    member's public key, signed by a granter it fabricated, whom the pin meets for the first time where the pin alone
    decides (the tenant scope's own grant, § What stays open) — and the member's next write sealed under its key. So beside
    each floor the device's trust marks (`trustMarks.ts`) keep a commitment to the newest epoch key it held, 16 bytes of
    a labelled HKDF of it, and every grant the device opens at or above that epoch must peel down through the served
    links to exactly that key — at the same epoch, be that key (`requireLinkToHeld`, `src/web/src/features/spaces/scopes.ts`,
    with `peelEpochKey`). Otherwise it is refused as `epoch_unlinked`: the key inside is never used, the scope reads as
    awaiting the current key, and a securing's plan declares it unreadable (decision 6). The operator never held the
    key the link must end at, so no link it makes ends there.
    - **What becomes the held key:** every grant the device opens, every epoch it commits itself, and the tenant key an
      accept checked or minted. The second is what stops the party a removal-rotation excludes: they held the old epoch
      and, with the operator, could link a key of their own down to it, but the remover's device holds the epoch it minted.
    - **The accept holds what it checked.** Once the server took a member's accept, the device holds the carried epoch
      key the checks of decision 19 vouched for — the re-checked invite's after `409 invite_changed` (`acceptCheckedInvite`,
      `src/web/src/features/tenant/tenantInvite.ts`); an owner's accept holds the key its birth minted, at epoch 0
      (`views/TenantJoinView.vue`). Without it the accepting device held nothing until its first open of the tenant and
      took whatever grant was served there as a first grant: an operator could raise the epoch around a key of its choosing
      under a granter it fabricated (inbox #740). A new account holds under the Master Key its birth hands back
      (`BuiltAccount.masterKey`), since no vault holds one before its first sign-in; a refused accept holds nothing.
    - **Every scope kind, in the one place a grant is opened** (`openOwnGrant`), not only the tenant's: a group's
      granter is anchored in its tenant, but a tenant member outside the group could otherwise, with the operator, plant
      a group's next epoch.
    - **The legitimate cases pass.** The accepting device's first open finds the self-grant of the key it holds, or a
      later epoch linked down to it; a device holding nothing yet — another device of the account, or a group's
      newcomer, whose consent accept opens no grant — takes its first grant as it comes (§ What stays open, the group's
      first grant); a member pending across several epochs peels through every link
      in between; a securing's self-grant carries the very key the device held; a grant below the held epoch is no new
      epoch, and the floor is its check.
    - **Local and never synced, like the floor, and not carried by pairing.** The envelope that carries the marks is
      capped (8,192 characters) and a commitment per scope would grow it with every space the account is in; a linked
      device starts without commitments, as a device unlocked afresh starts without marks. A synced commitment in the pin store would reach a
      new device too, at the cost of one more entry kind there — not taken while the no-mark case stays open anyway.

### Migration

18. **Not needed: no release shipped before join records existed (2026-10-28).** It would have had an owner's client
    sign a join record for each member who joined without one. Every newcomer writes a join record at accept and veyq
    has no release, so the only members without one are root owners (decision 15).

## Consequences

- **Goal 3 holds for members through every rotation**, their own included: posting needs only the current epoch, a
  write into an existing node waits at most for its own path's re-key, and a rotator is never outside a scope. For a
  guest it holds once the re-key has reached its node.
- **The first-use gap closes for a newcomer's view of its inviting owner** — against the operator and against
  current and former members, alone or together — **as long as the owner is not compromised and the code is not
  guessed.** The operator, which mails the link, holds its sealed part and can guess the code offline, against 50
  bits behind Argon2id (or the strength of a password the owner chose). The other direction — the members' view of
  the newcomer — the join record anchors, rooted at an owner the server lists (decisions 12 and 15; who is an owner
  stays the server's word, § What stays open).
- **The owner is the trust root — accepted (owner, 2026-10-27).** A compromised owner can issue invites for keys of
  their choosing; but an owner can already admit anyone legitimately, so a compromised owner compromises membership
  in any model. Owners cross-signing each other, or a member
  distrusting one owner, is a later extension, not part of this record.
- **A revoked or expired tenant invite keeps what it was granted — accepted, with a short expiry (owner,
  2026-10-27).** The tenant does not rotate on removal ([ADR 0025](0025-forward-secrecy-policy-per-scope.md)), so link
  and password stay a key to the epochs up to the revoke — useful only with a dump of the ciphertext. Same class as a
  removed tenant member without a re-key. A group rotates on revoke anyway.
- **Verifications forged between theft and rotation — accepted (owner, 2026-10-27).** The re-signing (decision 13)
  carries them with the verifier's own: they verify under the outgoing key at its head, as hers do, because the thief
  held the same key and the Master Key. They belong to the compromise window, like anything else done with the
  identity then.
- **Verifications are visible to a tenant's members, and their address to the server.** A *verified* is no private
  entry of the verifier's pin store any more but a signed statement every member of that tenant reads, which is what
  lets *verified by …* count for everyone there. The operator reads none of its content (sealed under the tenant
  key): not the heads, not whether it still counts. But a row is addressed by tenant, verifier and subject in the clear,
  and carries the time the server wrote it, so the server learns **which member verified which, in which tenant, and
  when** — what a sealed pin store kept from it. A removed member, holding the tenant key the tenant does not rotate on
  removal, could read these records with the operator's help — writing a forged one does not help them, because each
  is signed by its verifier and counts only under an anchored member's current key.
- **An overview "who's who"** — per tenant: anchored, verified by whom, rotated — is the member list plus these
  records, with no read of its own (Settings → Who's who, `src/web/src/features/account/peopleOverview.ts`).
- **The rotator's device carries the rotation's grants**: one epoch, one chain link and one wrap per member, for every
  scope the rotator is in, the tenant included. That is the `O(N)` re-grant ADR 0060 Decision 5 accepts on a compromise,
  moved from the completing owner to the rotator — and for the tenant it is the burst
  [ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2 meant to spread across devices. What is spread now is the far
  larger node re-key (decision 11); a wrap per member is one asymmetric seal each.
- **What it adds to the server, all opaque:** an invite's public key and the owner's signature on it, a grant to
  that key and the owner's anchor grant beside it; a key chain beside each identity, read only by co-members and by the
  holder of an invite its owner signed; the join records in the tenant profile, who rooted each, and the owner's
  endorsement of it; the verifications in a table of their own, sealed under the tenant key; the Secret-Key proof
  public key in the directory; a pending marker per declared omission; and a grant that names the key it is wrapped to,
  without which decision 10 cannot be checked. The server learns that an invite exists, who invited whom and that an
  account rotated, as it does today, and from a verification's address who verified whom in a tenant, never what was
  confirmed.
- **The verification row's address stays in the clear — decided (2026-10-28).** An opaque address (a MAC under the
  tenant key) was weighed and declined: the server authenticates every write, so it knows the verifier whatever the
  row is keyed by; a compare is mutual and its two writes arrive seconds apart, so hiding the subject hides little from
  an operator that reads its own timestamps; and it would cost the one-row-per-pair replace and the removal's delete by
  member. Membership-class metadata: the server already knows both are members and when the verifier's client wrote.
- **What it replaced in the records it builds on** each of them names in its `Replaced in part` header line (ADR 0020,
  0024, 0025, 0028, 0060, 0080, 0104, 0109); ADR 0109's rejection of a lazy re-key stands.
- **The compare and its vectors changed** (decision 14): the safety number stays the identity's, a chain code of the
  head stands beside it, and the QR carries both; the head pins the proof public key with the identity.
- **Tier-consistent ([ADR 0026](0026-client-trust-tiers.md)).** Every check here is a client's: against an active
  operator it holds only on a client the operator does not serve, and honest-operator elsewhere — the limit any model
  shares, including on a first contact anchored through the invitation.

## Alternatives rejected

- **The rotator may not re-admit itself** (ADR 0060's Decision 4 before this record). Its reason was that an attacker holding the old
  identity would mint the new epoch for their own new key and shut the person out. The doubly signed step (decision 7)
  answers it: an attacker without the Secret Key cannot make a step anyone accepts, and the server takes no grant to a
  retired key (decision 10). Keeping the rule would keep the rotator outside every shared space until somebody else
  acts, and the human re-admission with it.
- **Keeping the Secret Key across a rotation** (ADR 0060 before this record, so the Emergency Kit stayed valid). It leaves a
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
  a key-holder's device then wrapped to them with no further human decision (ADR 0020 move 3b), so it was in effect
  the key already; what is new is a link that keeps its reach after a revoke, accepted above, and the mandatory code or
  password, which the operator that mails the link never sees, raises it above that. *The owner signs the admission*
  was rejected because a worker learns who the owner is from the server. A newcomer now learns the owner's head from
  the link, inside its seal; an existing member, from its own pin of the owner. ADR 0020's further point — that a removed owner's old signatures would stay valid
  without signed removals — is not answered here (§ What stays open).

## What stays open

- **The admission-tag replay hypothesis** died untested with the tag (decision 5); nothing claims it.
- **A membership the server made is an anomaly.** Every newcomer
  writes a join record and no release predates it, so a non-owner without one can only be a membership the server
  wrote; trusting it on first use would keep open exactly the door the join record exists to close, and treating
  owners the same would make every root owner an anomaly to everyone they did not invite. It is never wrapped to and is
  declared pending in a rotation, never a block on anyone's write. Closed by it: every wrap a member's client makes in
  a tenant — a rotation's re-grant, the delivery to a behind member, a group add, a group's name key, an open
  invite's carry — and the acceptance of a group's grant in the active tenant. **What stays of the operator's class:**
  an owner is the server's word (below), so a member the server presents as an owner roots keys; a root owner is
  trusted on first use; the tenant scope's own grant from another member is judged by the pin alone, because the
  standings are read with the key that grant carries — a later epoch must link down to the key the device held
  (decision 23), so what stays is a device that holds no key of the scope yet (a new or newly linked device; the device
  that accepted holds the key it checked or minted from the accept on) and a party that holds the device's key (a member, or a member
  removed while the tenant has not re-keyed since, ADR 0025), who with the operator can plant a linked next epoch; and
  a grant in a tenant this device cannot read — a group grant outside the active tenant, read while staging a
  rotation — falls back to the pin. A wrap where the tenant cannot be read fails closed instead.
- **A newcomer does not anchor the member whose rotation carried its invite**, and need not: that grant counts by
  its epoch link to the owner's anchor (decision 19). What remains is a party that **held the anchor epoch** — a
  member or former member, or a thief of a member's device — who, with the operator, can plant a later epoch for a
  newcomer whose invite a rotation by someone other than the owner carried. The owner's own rotation moves the anchor
  past it.
- **Withholding the owner's securing step from a new account.** A newcomer that pinned nothing cannot tell a chain
  served only up to the link's head from the owner's whole chain; with the operator, a thief of the owner's retired key
  can then pass `checkInvite` under that key. The newcomer's join record names the retired key without an endorsement,
  so every co-member reads it as an anomaly (decision 21), and an honest server refuses the accept (`409
  invite_changed`). The withholding class below.
- **The moderation delivery stays pin-only**, and the members a moderator seals a statement's facts to are sealed to
  **unchecked** (`factsForMembers`): the moderation surface has no Master Key and so no pin store (ADR 0099 § 6), and
  the recipients may share no tenant with the moderator, so there is no pinned head and no join record to anchor them
  by — any check would be a first use the operator serves. An operator that substitutes a recipient's key reads that
  statement's facts text (beside the measure's kind, target, legal ground and provision, which it reads anyway) and
  can re-wrap it to the genuine key afterwards. Accepted: the law asks for the statement's delivery, not for its
  secrecy from the provider, and no anchor exists to check against.
- **A pin the anchored chain does not hold is replaced only when that chain is at least as long as the pinned one.** An owner-rooted join record is stronger than a first-use pin,
  and a pin off the chain is the mark of a key an operator substituted at a first contact; but a chain shorter than
  the one this account pinned may be a rollback of a head it saw, which the pin exists to refuse, so that is an
  anomaly. The residual: an operator that got a substituted chain pinned that is longer than the person's real one
  leaves that person an anomaly to this account — shown, never silent, and never a block on a write.
- **A join record is sealed under the tenant epoch its invite's grant carried**, the epoch current at the accept (or
  an older one, for an invite a rotation declared pending), so a later reader opens it with that epoch's key from its
  epoch chain, never with the current key alone.
- **Who counts as an owner stays the server's word — accepted as a residual.** A member checking a join record knows the tenant's owners from the server's role list
  (`ScopeMember.role`) and its own pins; roles are not signed. A former owner, or a member the server presents as
  owner, can with the operator's help root a key of their choosing. It is the class of ADR 0020's departing-member
  residual, and closing it needs signed roles and removals — the membership protocol ADR 0020 declined, which nothing
  here waits for.
- **A group's first grant on a device is judged by its granter's standing in the tenant, not in the group — accepted
  as a residual (inbox #750, measured 2026-10-29).** A device that holds no key of a group — a newcomer's after a
  consent accept, which opens no grant (unlike a tenant accept, decision 23), or a new or newly linked device — opens a
  grant from any member its tenant anchors and holds it. `groupFirstGrant.test.ts` (`src/web/src/features/spaces/`)
  measures it on real keys: a member in no role in the group, with the operator, has the device open and write under a
  key of its choosing; one removed from the group links that key down to the epoch it held, so the device reads the
  group's history too. **The gain is nil**: which groups an account is listed in, who else is in one and who proposed an
  add (`addedBy`, a plaintext username) are the server's word, so the same member makes a group of its own and the
  operator presents it as this one and hides the real one — and the client judges both grants alike (the same test).
  What holds: a granter the tenant does not anchor is refused, and a device that held the group's key refuses the plant.
  **Not closed, because nothing local tells the plant from an honest first grant** — a device that refuses a group's
  first grant refuses the owner's add and every member's re-key too (measured: each honest control turns red) — and a
  signed adder (the add names its proposer, the first open requires the key the proposer committed to) binds the key to
  a proposer whose name and membership in the group stay the server's word, so the lookalike group reopens all of it.
  Closing it needs a group membership the newcomer can verify: signed adds, removals and roles, the membership protocol
  of the residual above.
- **Withholding** is detected only by a device that saw a newer step, or by two people comparing heads (decision 14);
  a first-time reader cannot tell on its own (the fork, until Key Transparency).
- **A malicious server on a retry** may re-list a revoked invite to the other of two concurrent rotators — the
  honest-operator class, as today.
- **What is still trusted on first use**: a root owner, and every contact outside a shared tenant (the moderation
  delivery). A newcomer anchors its inviting owner through the head in the link and the carried epoch through the
  owner's anchor (decision 19). Inside a tenant everyone else is anchored by a join
  record or is an anomaly.
