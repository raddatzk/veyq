# ADR 0111 — An invitee grants itself, a rotator re-keys its own scopes, and a signed key chain anchors who is who

- Status: Accepted 2026-10-27 (owner, the design as a whole, its four owner decisions under § Consequences included);
  **decisions 1–5 are built** (2026-10-28: an invite is a placeholder member, the invitee grants itself and writes a
  join record, a group add wraps directly, the admission tag and *awaiting access* are gone — ADR 0020 states it) and
  **decision 11 is built** (2026-10-27: any member re-keys, a member's write pulls its path forward, a drop box stays
  an owner's — ADR 0109 point 7 states it), **decisions 6–10 are built** (2026-10-28: securing the account replaces
  identity, Master Key and Secret Key in one doubly signed key-chain step, applies the self-grants staged before it, the
  account mints every next epoch itself, a grant names its recipient key on every writer, and an omission is declared —
  [ADR 0060](0060-key-rotation-after-compromise.md) states it), and **decisions 12–17 are built** (2026-10-28: the join
  record anchors a person per tenant, a verification is a signed statement every member of the tenant reads, a
  rotation re-signs the outgoing key's verifications with the new one, the compare shows the key-chain head, a member
  sees *new*, *not verified*, *verified by …* and the anomaly, the pin store is the device's counter only, and the
  chain stays KT-ready —
  [ADR 0028](0028-identity-authenticity-tofu-verification.md) and
  [ADR 0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) state it). **Not built:**
  decision 18 (§ What stays open). [ADR 0020](0020-grant-distribution-invite-wrap.md),
  [ADR 0024](0024-scope-key-rotation.md), ADR 0028, ADR 0060 and [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
  each state in place the part of them this record replaced.
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
- **A tenant admission tag bound no user and no key.** It was an HMAC over the scope, the epoch and an admission id
  (ADR 0020 move 6, removed by decision 5). It proved *"a key-holder admitted someone"*. Against an honest server it
  stopped a session without an unlocked vault from leading to a grant. Against a malicious operator it added little:
  the operator redeemed a genuine admission with a key of its own. Whether it could also **copy** an existing tenant
  admission onto a membership it invented was a hypothesis no test checked; this record never rested on it, and it
  died untested with the tag.
- **After a rotation the rotator was outside every shared scope** until another key-holder accepted the new key
  (ADR 0060's human re-admission, replaced by decisions 6–10). An owner who rotated and then committed a scope's
  rotation was refused by every other member as `granter_key_changed` once that member had accepted the new key.
- **Only an owner re-keys a node** (until decision 11, built 2026-10-27). The re-key endpoint refused anyone else (`RekeyAccess.check`, `not_scope_owner`),
  and a scope's *re-keying* status is served to owners only (`ScopesStore.kt`, the `rekeying` field of a scope row).
  A write into a node not yet re-keyed is `409 node_rekey_pending` (ADR 0109 point 6). So after any rotation a member
  cannot write into an existing Memory until an owner has been online long enough — goal 3 broken.

Facts the design rests on, read in the code on 2026-10-27:

- Access knows only `READ` and `WRITE` as the kind of operation (`src/backend/core/.../membership/ScopeAccess.kt`).
  There is no read-only member role: every accepted member may write. Placing or renaming is refused only into a
  guest's identity folder (`NodesHandler.kt`, the placement rule of [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  Decision 11).
- The Secret-Key proof public key appeared only in the registration requests (`AccountRegistration` in
  `contracts/api/tenants.openapi.yaml` and `contracts/api/setup.openapi.yaml`); no response served another user's.
  (Since decision 7 was built, the key chain serves it to the readers that decision names.)
- A grant names its recipient by user id only (`wrappedGrant`, `grantSignature`, `userId` in
  `contracts/api/scopes.openapi.yaml`), so the server cannot tell which key a grant was wrapped to. (Since decisions
  1–10 were built, every grant writer carries `recipientIdentityDigest`, the SHA-256 of the key it wraps to, and the
  server refuses one that is not the recipient's current key, `409 recipient_key_not_current`.)
- `RotationStore.applyRotation` keeps no copy of the old identity's private material.
- The server mails a member invite (`SmtpTenantInviteMailer`), so the operator knows every link it mails; the secret
  rides the URL fragment.

## Decision

### Invitation: a placeholder member that the invitee replaces

Decisions 1–5 are built (2026-10-28); the code they name is the state.

1. **An invite is a placeholder member with an invite key the owner signed.** Creating an invite mints an **invite
   key** on the owner's client: an identity-shaped bundle derived from a 32-byte seed (`inviteKeyFromSeed`,
   `src/packages/crypto/src/invite.ts`). The owner **signs the invite public key with their identity** — the statement
   `invite-key` over the tenant, the invite id, SHA-256 of the invite bundle and the expiry in Unix seconds; the
   server verifies that signature under the caller's stored identity key before it mints or re-issues (`400
   invalid_request` otherwise, so a session without the owner's vault mints no invite — on an honest server, the job
   the admission tag did) and stores the public half, that signature and the signer's key as it signed (so a later
   rotation of the owner does not void the invite). The seed travels in the link together with the **owner's identity fingerprint** and
   account, **always sealed** (`#<token>.<sealed>`, seal purpose `invite-link`, bound to the tenant and the invite)
   under `Argon2id(secret, salt)` at the production profile, where the secret is a **generated code** — 10 Crockford
   base32 characters, 50 bits, shown once in the invite dialog — or a **password the owner chooses** (the account
   password's length floor). The owner passes it on outside veyq. The seal is mandatory because the server mails the
   link and mail is the only channel ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)), so the operator
   holds every link it mails: an unsealed link would hand it the invite. The server appends the sealed part to the
   mailed link and keeps no copy. The fingerprint sits **inside** that seal, and the invitee's client **derives the
   invite public key from the seed** rather than taking it from the server — otherwise an operator swaps fingerprint
   and key in a mailed link. Against the operator, who can guess offline, the margin is 50 bits behind Argon2id.
2. **The invite holds a grant like a member.** It receives a grant of the current epoch wrapped to the invite public
   key (its recipient is the invite id, and it names the key by `recipientIdentityDigest`); the server takes it only
   at the current epoch, under the tenant scope's lock (`409 stale_epoch`). **Every rotation of the tenant re-grants
   each open invite or declares it pending** (`inviteGrants`, `pendingInviteIds`): the rotator's client re-grants one
   whose owner signature verifies under the stored signer key and whose signer passes its anchor check, and declares the
   rest; the server refuses a rotation that leaves an open invite out (`409 rotation_incomplete`). Every member reads
   the open invites' keys for this (`listOpenInvites`, never an email). **Revoking an invite is removing a member** —
   accept, revoke, supersede, expiry and the erasure of the signer's or the addressee's account null its key and grant
   in the statement that ends it; a reissue is a supersede and a new invite (new key, link and code). A group has no
   invites; the group counterpart, a declined or rescinded add proposal, queues the group's rotation (decision 4).
   Every open invite has a **fixed expiry**, the instance's `veyq.tenant.invite.ttl` (served to the owner's client as
   `memberInviteTtlSeconds`, signed by the owner, checked by the server within a minute): hygiene, which ends the
   window in which the link can be redeemed, not what the link already decrypts (§ Consequences, the revoked invite).
3. **The invitee grants itself and writes a join record.** On accept the invitee's client opens the seal with the
   code, requires the key the seed derives to be the served one, requires the served signer key to hash to the
   fingerprint in the seal, verifies the owner's signature, verifies the invite's grant (an owner-wrapped grant only
   under that checked key; one a rotation re-wrapped names another member, trusted on first use — § What stays
   open), opens it, and **grants the epoch to its own new identity** (a self-grant naming its key). It proves holding
   the invite key with an `invite-accept` signature the server verifies (`403 invite_proof_invalid`). The server
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
     identity the stage declares at upload (`422 self_grant_invalid`). **Nothing staged is applied before the
     publish**: a stage is ciphertext and scope ids the server already holds as grant rows, and it expires an hour after
     it opened (`veyq.account.rotation.stage.ttl`, server-observed) and is purged.
   - **The publish names the stage and its digest** (`stageId`, `stagedGrantsDigest`) inside the statement the
     Secret-Key proof signs, and publishes the identity that stage declares. Its one transaction takes each scope's row
     lock and applies the stage **only if every grant-carrying membership of the account is staged at its scope's
     current epoch, or declared unreadable** (`unreadableScopeIds`: a grant this device cannot open, which keeps its
     grant untouched); otherwise `409 rotation_plan_changed`, nothing is written, and the client re-stages the delta.
     From the commit on, every scope the account was in holds its current epoch for the new identity, so a lost answer,
     a closed tab, a failed next epoch or a space in another tenant strands nothing.
   - *Why staged rather than carried in the publish, measured when it was built:* no cap bounds an account's
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
   identity (`getKeyChain`), and **served only to the person, to users who share a tenant with them, and between two
   accepted moderators of the instance** (server-enforced: an honest server's rule; the operator knows the rotations
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
   everyone delays access, it does not remove it. Built as: `rotateScope` names the omitted in `pendingUserIds`, and the
   server refuses a rotation whose grants, pending members and removal do not cover every membership
   (`409 rotation_incomplete`); a member's grant behind the current epoch **is** the stored pending marker. The delivery
   worker declines a target it cannot anchor (`identity_unanchored`), and the publish clears the declines of every job
   whose target is the rotating account, so a worker that could not anchor the old key tries the new one.
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
      that is anchored in this tenant in turn — a retired key of that owner's chain included, so the members an owner
      invited before securing their account keep their anchor. An owner the server lists with **no** join record is a
      **root** (the tenant's first owner) and is trusted on first use, by the pin; a newcomer anchors its inviting owner
      through the link (decision 1). **Anyone else is an anomaly**: a non-owner without a join record, a record that
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
- **The first-use gap closes for a newcomer's view of its inviting owner** — against the operator and against
  current and former members, alone or together — **as long as the owner is not compromised and the code is not
  guessed.** The operator, which mails the link, holds its sealed part and can guess the code offline, against 50
  bits behind Argon2id (or the strength of a password the owner chose). The other direction — the members' view of
  the newcomer — the join record anchors, rooted at an owner the server lists (decisions 12 and 15; who is an owner
  stays the server's word, § What stays open).
- **The owner is the trust root — accepted (owner, 2026-10-27).** A compromised owner can issue invites for keys of
  their choosing; but an owner can already admit anyone legitimately, so a compromised owner compromises membership
  in any model. Contradictions between owners are anomalies (decision 18). Owners cross-signing each other, or a member
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
- **An overview "people I share with"** — per tenant: anchored, verified by whom, rotated — is cheap now: it is the
  member list plus these records. Today there is only the per-person profile and the per-space member lists.
- **The rotator's device carries the rotation's grants**: one epoch, one chain link and one wrap per member, for every
  scope the rotator is in, the tenant included. That is the `O(N)` re-grant ADR 0060 Decision 5 accepts on a compromise,
  moved from the completing owner to the rotator — and for the tenant it is the burst
  [ADR 0025](0025-forward-secrecy-policy-per-scope.md) §2 meant to spread across devices. What is spread now is the far
  larger node re-key (decision 11); a wrap per member is one asymmetric seal each.
- **What it adds to the server, all opaque:** an invite's public key and the owner's signature on it, and a grant to
  that key; a key chain beside each identity, read only by co-members; the join records in the tenant profile and the
  verifications in a table of their own, each sealed under the tenant key; the Secret-Key proof public key in the
  directory; a pending marker per declared omission; and a grant that names the key it is wrapped to, without which
  decision 10 cannot be checked. The server learns that an invite exists and that an account rotated, as it does
  today, and from a verification's address who verified whom in a tenant, never what was confirmed.
- **What it changes in the records it builds on**, each stated there in place: ADR 0020 moves 3, 5 and 6 (admission
  and delivery); ADR 0024 Decision 4's admission tags, Decision 5's re-grant of new members and § Guarantee's queued
  account rotation; ADR 0028 Decisions 2, 5 and 6 and the role of the pin; ADR 0060 Decision 2's Secret Key kept across a
  rotation and Decisions 4, 6 and 7; ADR 0104's entry content; ADR 0109 point 7's owner-only re-key; ADR 0080 Decision 11
  gains the owner-only re-key of a drop box. ADR 0109's rejection of a lazy re-key stands.
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
  was rejected because a worker learns who the owner is from the server. A newcomer now learns the owner's fingerprint from the link, inside its seal; an existing member, from its own
  pin of the owner (decision 18). ADR 0020's further point — that a removed owner's old signatures would stay valid
  without signed removals — is not answered here (§ What stays open).

## What stays open

- **Decision 18 is not built**, and has nothing to do while veyq has no release: the join record landed before the
  first one, so the only members without one are root owners (decision 15). If a release ships members without join
  records, each of them reads as an anomaly to every co-member, and decision 18 is then needed
  (`changes/cr-join-records-for-existing-members.md`).
- **The admission-tag replay hypothesis** died untested with the tag (decision 5); nothing claims it.
- **A membership the server made is an anomaly — decided (assistant, 2026-10-27, for owner review).** Every newcomer
  writes a join record and no release predates it, so a non-owner without one can only be a membership the server
  wrote; trusting it on first use would keep open exactly the door the join record exists to close, and treating
  owners the same would make every root owner an anomaly to everyone they did not invite. It is never wrapped to and is
  declared pending in a rotation, never a block on anyone's write. Closed by it: every wrap a member's client makes in
  a tenant — a rotation's re-grant, the delivery to a behind member, a group add, a group's name key, an open
  invite's carry — and the acceptance of a group's grant in the active tenant. **What stays of the operator's class:**
  an owner is the server's word (below), so a member the server presents as an owner roots keys; a root owner is
  trusted on first use; the tenant scope's own grant from another member is judged by the pin alone, because the
  standings are read with the key that grant carries (every new epoch must still link to the one held, ADR 0024); and
  a grant in a tenant this device cannot read — a group grant outside the active tenant, read while staging a
  rotation — falls back to the pin. A wrap where the tenant cannot be read fails closed instead.
- **The invite accept and the moderation delivery stay pin-only**: there is no tenant the two sides share whose join
  records could anchor the other. So a grant a rotation re-wrapped to an open invite names a member the newcomer cannot
  anchor: only a grant the inviting owner wrapped verifies under the key the link anchors; one a rotation re-wrapped
  is signed by the rotator, whose key the newcomer trusts on first use (`memberInviteAnchored`,
  `src/web/src/features/tenant/tenantInvite.ts`; inbox #565). Neither the join records nor the key chain close it: the
  newcomer reads no join record before it is a member, and the chain anchors a key from a head someone pinned, while
  a newcomer has pinned no rotator. Nor is that rotator pinned at the accept: the grant's granter key may be one its
  granter has since retired, and pinning it beside their current head would pin a pair that never existed, a
  permanent anomaly; the newcomer pins them through their chain at its first contact as a co-member, where their join
  record anchors them. The members a moderator seals a statement's facts to are sealed to unanchored (inbox #570).
- **A join record signed by an owner's retired key still anchors** (decision 15), so the members an owner invited
  before securing their account keep their anchor. The cost: a thief holding that retired key could, with the
  operator, mint a join record after the rotation — the class this record accepts for verifications forged between
  theft and rotation (§ Consequences), here not ended by the rotation (inbox #571).
- **A pin the anchored chain does not hold is replaced only when that chain is at least as long as the pinned one —
  decided (assistant, 2026-10-27, for owner review).** An owner-rooted join record is stronger than a first-use pin,
  and a pin off the chain is the mark of a key an operator substituted at a first contact; but a chain shorter than
  the one this account pinned may be a rollback of a head it saw, which the pin exists to refuse, so that is an
  anomaly. The residual: an operator that got a substituted chain pinned that is longer than the person's real one
  leaves that person an anomaly to this account — shown, never silent, and never a block on a write.
- **A join record is sealed under the tenant epoch its invite's grant carried**, the epoch current at the accept (or
  an older one, for an invite a rotation declared pending), so a later reader opens it with that epoch's key from its
  epoch chain, never with the current key alone.
- **An owner's open invites end with the owner's rotation.** The link anchors the owner's identity **fingerprint**
  only, and a grant the inviting owner wrapped must verify under that key. Accepting the rotated owner through the key
  chain would need the invitee to anchor the step old → new, but a fingerprint pins the identity and not the proof key:
  an operator holding a stolen device's identity could serve an invented genesis naming that identity with a proof key
  of its choosing, then sign a step to a key of its own — the prefix decision 16's pinned head exists to refuse.
  Re-signing the invite under the new identity fails the fingerprint for the same reason. So a rotator re-grants an
  open invite only while the chain from the head it pinned for the owner ends at the signer key, the invitee refuses one
  whose signer is not the key at the served head, and is told to ask for a new invite; the ceremony's entry tells an
  owner who signed open invites that they stop working. Keeping them soundly needs the owner's **head** inside the
  link's seal, so the invitee walks the chain from a pinned head — a change of the built link format (inbox #568).
- **Who counts as an owner stays the server's word — accepted as a residual (assistant, 2026-10-27, for owner
  review).** A member checking a join record knows the tenant's owners from the server's role list
  (`ScopeMember.role`) and its own pins; roles are not signed. A former owner, or a member the server presents as
  owner, can with the operator's help root a key of their choosing. It is the class of ADR 0020's departing-member
  residual, and closing it needs signed roles and removals — the membership protocol ADR 0020 declined, which nothing
  here waits for.
- **Withholding** is detected only by a device that saw a newer step, or by two people comparing heads (decision 14);
  a first-time reader cannot tell on its own (the fork, until Key Transparency).
- **A malicious server on a retry** may re-list a revoked invite to the other of two concurrent rotators — the
  honest-operator class, as today.
- **What is still trusted on first use**: a root owner, the invite accept's unknown inviter and foreign granter, and
  every contact outside a shared tenant (the moderation delivery). Inside a tenant everyone else is anchored by a join
  record or is an anomaly.
