# ADR 0054 — Password recovery releases the RECOVERY wrap against two factors: address control and the Secret Key

- Status: Accepted
- Date: 2026-08-05

## Context

A `RECOVERY` `MasterKeyWrap` has been written at every account birth since V1
(`src/packages/crypto/src/accountBirth.ts`), and the server has withheld it from the only endpoint
that serves wraps ever since (`KeyMaterialHandler.forUser` filters it out). It has never been read by any
client. The account key model calls a forgotten password "must not mean lost memories"
([en.account.key-model](../features/account/enablers/en.account.key-model.md)) and the published wiki promises
a way back, so the gap is a promise outstanding, not merely a missing feature. Inbox #113 carries it; ADR 0053's
sibling durability bar is stated in [architecture](../architecture/README.md) §10.

Three facts constrain the design. The first two are in the code; the third is the trap the first version of
this record fell into.

**1. The RECOVERY wrap is opened by the Secret Key alone.** `kekRecovery(secretKey, salt)` takes no password,
where the `PASSWORD` floor takes both (`kekPasswordSecret`). So there is **no separate recovery code** in veyq
and never has been: the recovery factor *is* the Secret Key from the Emergency Kit. This ADR does not introduce
one — a second 256-bit secret that unlocks exactly what the first one unlocks doubles the loss surface and adds
no factor.

**2. Releasing that wrap to a session would silently demote the password.** The withholding comment states it:
a live session plus the Secret Key would open the vault *without* the password, turning the password from a
content factor into a login-only one. So release cannot be authenticated by session — it must be gated on
something the ordinary logged-in user does not have.

**3. A hash of the Master Key is not a proof of the Secret Key — the trap this record first fell into.** It
first gated the reset on reproducing a domain-separated SHA-256 of the Master Key against the copy stored at
birth, on the reasoning that it *"needs the master key, i.e. the Secret Key"*. Both halves
of that fail.
- **The Master Key is not the Secret Key.** A linked device holds the Master Key without ever having seen the
  Secret Key ([ADR 0059](0059-device-linking.md) §10).
- **The proof is the stored value itself.** A database dump carries the very tag a reset presents.

A proof the server checks has to be one the server cannot itself produce: a signature under a key only the
Secret Key derives ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2).

The open question was what gates the release. The originally sketched answer was an email gate, recorded in
code as "the (future) email-gated recovery flow (cr-0052)" — a CR that no longer exists. Email verification was
briefly scoped out of V1 on the reasoning that invite-only registration already proves address control; that
reasoning covers *registration*, not *recovery*, and it was reversed on 2026-08-05.

## Decision

**1. Recovery is a password reset, gated on two independent factors.**

- **Address control** — a one-time token mailed to the account's address, proving the requester reads that
  mailbox. This is the *authorization* to be handed the wrap.
- **Possession of the Secret Key** — proven by a signature of the account's **Secret-Key proof key**
  ([ADR 0060](0060-key-rotation-after-compromise.md) Decision 2), which only the Secret Key derives. This is the
  *authentication* that the requester is the account holder.

Neither alone suffices. An attacker with mailbox access gets a wrap they cannot open. An attacker with a stolen
Emergency Kit gets no token. This is why the flow is safe even though the mailbox is, for most people, weaker
than the account it protects.

**What the server stores is only a public key.** A database read therefore yields nothing the reset accepts,
and neither does the Master Key a linked device holds (Context, fact 3). Storing the old tag hashed would have
closed the first of those and not the second.

**2. Recovery is not escrow, and the server gains no new power.** The server hands out ciphertext it already
stores, to a party that must already hold the key that opens it. It cannot open the wrap, cannot derive the
Master Key, and cannot complete a reset on anyone's behalf. Losing both the password *and* the Emergency Kit
remains unrecoverable — deliberately, and as the wiki already says.

**3. The reset re-writes exactly three things, and re-encrypts nothing.** The Master Key is unchanged, so all
content, scope keys and the identity keypair survive untouched — the same property that makes adding an unlock
method cheap ([ADR 0005](0005-unlock-methods-and-device-trust.md)). What changes:

- the `PASSWORD` wrap (fresh salt + nonce, new KEK from the new password),
- `veyq_user.auth_public_key` (the Ed25519 keypair is derived from the password;
  [ADR 0003](0003-operator-blind-password-auth.md)),
- and, by deletion, **every wrap but `PASSWORD` and `RECOVERY`** — today every `DEVICE` and `DEVICE_KEY` wrap. The
  list names survivors, so a method added later dies too until it is declared one
  ([en.account.key-model](../features/account/enablers/en.account.key-model.md) § Adding / removing unlock methods).

The last one is not housekeeping. `DEVICE` wraps mix the old password and would simply fail, but `DEVICE_KEY`
wraps take no password at all and would keep working — a device that was trusted before the reset would retain
password-less access afterwards. Since "someone else has my device" is a reason people reset, keeping those
alive would defeat the flow. All device trust is dropped; every device re-enrols on next sign-in. Live sessions
are revoked with them.

The three writes are **one transaction**. A partial apply leaves an account whose password logs in but does not
unlock, or the reverse — both are lockouts.

**4. Mail is a hard requirement, not a degraded path.** Recovery has **no `FALLBACK_COPYLINK`**, and the reason
is what the link *points at* rather than who would carry it.

Sort veyq's one-time links by whose thing they open:

| Link | Opens | May pass through another person's hands |
|---|---|---|
| A share link | something the sharer chose to share | **yes** — being passed on is its purpose |
| An invite token | an account that **does not exist yet** | it creates, it does not reach into anything |
| A recovery token | an **existing account, with its content** | **no** |

A copy-link is a link handed to whoever is looking at the screen. For a recovery token that is by definition
the wrong person: it is minted for one specific account holder, and nobody else has any business holding it —
not because they could open it (they could not; the Secret Key is not theirs) but because a secret addressed to
someone else should never be routed through a third party at all. That the operator *also* cannot read the
content is a separate, weaker argument, and it is not the one this decision rests on.

So an instance with no working SMTP has **no recovery**, and must say so plainly rather than offer a control
that cannot work. That is a real cost of self-hosting and belongs in the operator documentation, not hidden
behind a spinner.

**The link travels only through a relay of a tenant the account belongs to.** Mail settings are per tenant and
are chosen by the domain the request arrives on, and each relay is run by that tenant's owner. For an account
that is not a member of that tenant, the owner is exactly the third party this section excludes. Routing that
account's token through them would also make every tenant owner an existence oracle for the whole instance,
which breaks §6. So a request reaches only an account that is a member of the tenant it resolved to, and for
any other address it answers and does nothing, exactly as for an unknown one. A member's own tenant is the
channel they joined, and the two factors are what hold there. Two consequences follow. A member of several
tenants may ask on any of them. An account that belongs to no tenant, which is how first-run setup leaves the
Server Owner, has no recovery until it joins one. That is the no-SMTP rule above, applied to a single account.

The same reasoning reaches the member-invite copy-link, which handed a tenant owner a token minted for somebody
else. That is decided in [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md), which retires it and makes
a working mailer a precondition rather than a hope. **One exception survives it:** the ServerOwner's invite to a
tenant's first owner, where a `PENDING` tenant has no mail settings by construction and no channel can exist yet.

**5. The mail obeys [ADR 0049](0049-email-delivery-boundary.md) unchanged** — link, domain, expiry, and nothing
else. It may not name the user, the tenant beyond its domain, or what is being recovered. Recovery copy is
**veyq-fixed and not owner-authorable**: the per-tenant template exists so an owner can word an invitation in
their own voice, but an owner-authored *recovery* mail is a phishing surface aimed at their own members.

**6. Request is non-enumerable — about the *address*.** A recovery request answers identically for a known and
an unknown address, and for the address of an account in another tenant (§4), mirroring the login challenge
([ADR 0004](0004-session-tokens.md)). The response is never an oracle for "is this address registered here",
and neither is the mail, which only an account of the addressed tenant can receive. Rate limiting is
per-address and per-source, not per-tenant: an account is tenant-global, so the invite limiter's key does not
apply.

**Identical includes timing, so the answer comes before anything that depends on the address.** The request
checks the tenant's transport and charges the limiters, answers, and only then looks the address up, checks
membership, mints and sends. A request that waited for its own SMTP delivery would answer a known address one
mail transaction slower than an unknown one, which is an oracle any stopwatch can read. The limiters stay on
the synchronous side, before the hand-off, and that is what bounds the work queued behind the answer.

That promise is about the address and nothing else. Whether the addressed tenant can send mail at all is a
property of the deployment, is answered plainly with `503 mail_unavailable`, and is checked **before** the rate
limiter so the two answers cannot be paired into an address-level oracle
([ADR 0055 §4/§5](0055-mail-is-a-precondition-not-a-capability.md)).

## Consequences

- **The published promise gets narrower and truer.** `wiki/{en,de}` describes a single-use *recovery code* that
  issues a fresh kit. No such code exists; the Secret Key is the recovery factor, and a reset does not re-issue
  the kit (the Secret Key is unchanged, so the old kit stays valid). The wiki must be corrected to describe what
  veyq actually does. Re-issuing a kit — which means rotating the Secret Key and re-wrapping — is a separate
  capability that does not exist today and is not part of this decision.
- **Recovery never gates on a stored confirmation.** The mailed token proves address control at the time of
  release, which is what matters; a flag recorded months earlier adds no factor. Address confirmation
  ([ADR 0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md)) is therefore recorded and shown,
  never gated on (its Decision 3) — and a completed reset is one of the events that sets it.
- **The Secret-Key proof key is a correctness dependency.** An account whose proof key is missing or wrong
  cannot recover, cannot change its password, and cannot rotate.
- **The invite-token lifecycle is the template**, not a new invention: `Tokens.random()` → store only
  `Tokens.hash()` with an expiry and a status → mail the raw token in a URL fragment → look up by hash, check
  liveness, flip status on consumption → scheduled expire-then-purge. Recovery wants a far shorter TTL than an
  invite's `P7D`.
- **A trap this creates.** `KeyMaterialStore.find` returns the RECOVERY wrap unfiltered; only the handler
  filters it. A second caller that forgets to filter leaks a password-less path to the Master Key. The slice
  test pins the *endpoint*, not the store. This ADR makes that a live risk rather than a latent one, because
  recovery adds the first legitimate second caller.
