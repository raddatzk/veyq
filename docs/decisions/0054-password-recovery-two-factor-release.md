# ADR 0054 — Password recovery releases the RECOVERY wrap against two factors: address control and the Secret Key

- Status: Accepted
- Date: 2026-08-05

## Context

A `RECOVERY` `MasterKeyWrap` has been written at every account birth since V1
(`packages/crypto/src/accountBirth.ts`), and the server has withheld it from the only endpoint
that serves wraps ever since (`KeyMaterialHandler.forUser` filters it out). It has never been read by any
client. The account key model calls a forgotten password "must not mean lost memories"
([en.account.key-model](../features/account/enablers/en.account.key-model.md)) and the published wiki promises
a way back, so the gap is a promise outstanding, not merely a missing feature. Inbox #113 carries it; ADR 0053's
sibling durability bar is stated in [architecture](../architecture/README.md) §10.

Three facts constrain the design, and all three are already in the code — this ADR chooses very little that was
not already implied.

**1. The RECOVERY wrap is opened by the Secret Key alone.** `kekRecovery(secretKey, salt)` takes no password,
where the `PASSWORD` floor takes both (`kekPasswordSecret`). So there is **no separate recovery code** in veyq
and never has been: the recovery factor *is* the Secret Key from the Emergency Kit. This ADR does not introduce
one — a second 256-bit secret that unlocks exactly what the first one unlocks doubles the loss surface and adds
no factor.

**2. Releasing that wrap to a session would silently demote the password.** The withholding comment states it:
a live session plus the Secret Key would open the vault *without* the password, turning the password from a
content factor into a login-only one. So release cannot be authenticated by session — it must be gated on
something the ordinary logged-in user does not have.

**3. `recoveryAuthTag` already exists for exactly this.** `sha256("veyq/v1/recovery-auth" ‖ masterKey)` is
written to `veyq_user.recovery_master_key_tag` at birth and read by nothing. Its own comment names the threat:
*"a reset must reproduce it — which needs the master key, i.e. the Secret Key — so an email-token-only attacker
cannot hijack the server account."* It is a commitment, not a secret: the master key is 32 random bytes and the
hash is not invertible.

The open question was what gates the release. The originally sketched answer was an email gate, recorded in
code as "the (future) email-gated recovery flow (cr-0052)" — a CR that no longer exists. Email verification was
briefly scoped out of V1 on the reasoning that invite-only registration already proves address control; that
reasoning covers *registration*, not *recovery*, and it was reversed on 2026-08-05.

## Decision

**1. Recovery is a password reset, gated on two independent factors.**

- **Address control** — a one-time token mailed to the account's address, proving the requester reads that
  mailbox. This is the *authorization* to be handed the wrap.
- **Possession of the Secret Key** — proven by reproducing `recoveryAuthTag(masterKey)`, which is only
  computable after actually opening the RECOVERY wrap. This is the *authentication* that the requester is the
  account holder.

Neither alone suffices. An attacker with mailbox access gets a wrap they cannot open. An attacker with a stolen
Emergency Kit gets no token. This is why the flow is safe even though the mailbox is, for most people, weaker
than the account it protects.

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
- and, by deletion, **every `DEVICE` and `DEVICE_KEY` wrap**.

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

The same reasoning reaches the member-invite copy-link, which handed a tenant owner a token minted for somebody
else. That is decided in [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md), which retires it and makes
a working mailer a precondition rather than a hope. **One exception survives it:** the ServerOwner's invite to a
tenant's first owner, where a `PENDING` tenant has no mail settings by construction and no channel can exist yet.

**5. The mail obeys [ADR 0049](0049-email-delivery-boundary.md) unchanged** — link, domain, expiry, and nothing
else. It may not name the user, the tenant beyond its domain, or what is being recovered. Recovery copy is
**veyq-fixed and not owner-authorable**: the per-tenant template exists so an owner can word an invitation in
their own voice, but an owner-authored *recovery* mail is a phishing surface aimed at their own members.

**6. Request is non-enumerable — about the *address*.** A recovery request answers identically for a known and
an unknown address, mirroring the login challenge ([ADR 0004](0004-session-tokens.md)) — the response is never
an oracle for "is this address registered here". Rate limiting is per-address and per-source, not per-tenant: an
account is tenant-global, so the invite limiter's key does not apply.

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
- **Email verification (#112) becomes load-bearing for V1**, and for a different reason than that row argues.
  Its semantics are now *recovery-gating* first: what matters is that the address is provably controlled at the
  time of release.
- **`recovery_master_key_tag` stops being write-only.** A column carried since V1 for an unbuilt flow finally
  has its reader, which also means it is now a correctness dependency: an account whose tag is missing or wrong
  cannot recover.
- **The invite-token lifecycle is the template**, not a new invention: `Tokens.random()` → store only
  `Tokens.hash()` with an expiry and a status → mail the raw token in a URL fragment → look up by hash, check
  liveness, flip status on consumption → scheduled expire-then-purge. Recovery wants a far shorter TTL than an
  invite's `P7D`.
- **A trap this creates.** `KeyMaterialStore.find` returns the RECOVERY wrap unfiltered; only the handler
  filters it. A second caller that forgets to filter leaks a password-less path to the Master Key. The slice
  test pins the *endpoint*, not the store. This ADR makes that a live risk rather than a latent one, because
  recovery adds the first legitimate second caller.
