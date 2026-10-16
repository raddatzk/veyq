# UC: Manage pending member invites (list, re-issue link, revoke)

- **ID:** uc.spaces.manage-member-invites
- **Primary actor:** Tenant **Owner** (the admission gate — the only one who mints or manages member invites).
- **Goal:** give the owner control over the **admission** side after minting — see who has been **invited but not
  yet joined**, **re-issue** the invite link when the old one was lost or ignored, and **revoke** a pending
  invite so its outstanding secret can no longer be accepted.
- **Preconditions:** The owner holds the tenant's OWNER membership; the tenant is ACTIVE; at least one MEMBER
  invite has been minted ([uc.spaces.add-member](uc.spaces.add-member.md)).
- **Postconditions (success):** The owner sees the current pending member invites; a re-issue **mails** a fresh
  single-use secret to the invited address (the prior one superseded); a revoke marks the invite terminal so a
  replay of its secret is rejected. Already-**joined** members are never affected — this manages *invites*,
  not *memberships*. The server saw only invite metadata (email, status, expiry), never a plaintext secret.

This is the management companion to [uc.spaces.add-member](uc.spaces.add-member.md): add-member is deliberately
**additive** ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md)), so tracking/re-issuing/
revoking the *invites* it produces lives here. It mirrors the owner-invite management the Server Owner already
has for tenant-owner invites ([uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md): resend
/ reassign / revoke).

## Main flow

1. The owner opens **"members"** and sees, beside the joined members
   ([uc.spaces.add-member](uc.spaces.add-member.md) directory), a list of **pending invites** — each an email +
   invite status + expiry. Owner-gated; a non-owner sees the directory but not the pending list nor the
   management actions.
2. **Re-issue.** For a pending invite whose link the invitee lost or ignored, the owner re-issues it: the server
   **supersedes** the current invite and mints a **fresh single-use secret** (the old one dies), **emailed to the
   same address**. Same email, new secret, new expiry; the owner never sees the secret
   ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)). Because the supersede
   happens as the fresh one is minted, a tenant that cannot send is refused **first**, leaving the outstanding
   invite alive rather than destroying it on a tenant that could never have delivered the replacement.
3. **Revoke.** The owner revokes a pending invite: it is soft-transitioned to a terminal state, so a later
   replay of that secret at `accept` is rejected as no-longer-valid. The reserved email is freed to re-invite.

## Alternate / exception flows

- **A1 — the invitee joined between list and action:** a re-issue/revoke targets an already-consumed invite → a
  no-op / not-found; the now-joined person shows in the member directory, not the pending list.
- **E1 — a non-owner attempts management:** rejected (403) — admission stays owner-only
  ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md): admission ≠ delivery).

## Quality notes

- **Operator-blind.** Listing returns only invite **metadata** (email, status, expiry) the server already
  stores; the secret is never stored plaintext (only its hash), and a re-issue returns **no secret at all** — not
  even to the owner who asked for it ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)
  §2). Handing a named person's admission secret back to a third party is exactly the routing this flow exists to
  avoid, so the response carries only the invite's state.
- **The invite secret is never logged.** The delivery mailer emits only non-secret metadata (email + expiry); the
  redeemable bootstrap token reaches the invitee **by mail and by nothing else** — not through the API response,
  not through the logging fallback (a member invite is refused before minting when the tenant cannot send, so it
  never reaches it), and never a server log, in any launch mode. A production runtime
  logs a secret-free "not delivered" warning.
- **Admission stays owner-only.** Every action here is gated on the tenant OWNER membership — the same gate that
  mints a member invite.
- **Invites, not memberships.** This never removes a joined member or rotates a scope key — those are a separate,
  later slice ([ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) /
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) defer removal + rotation). Revoking a
  *pending* invite is additive-safe: it touches no grant and no member.
- **Reuse.** The soft invite-lifecycle (ACTIVE → SUPERSEDED / EXPIRED, matched by hash, replay-rejected) already
  exists for owner invites; this applies it to MEMBER-role invites.
- **Re-issue delivery + rate limit (cr-email-invite-send-hardening).** A re-issue reports its delivery result
  inline, but on this path only **`SENT`** is reachable: a tenant that cannot send is refused before the mint
  (`503 mail_unavailable`) and a send that errors is refused after it (`503 mail_send_failed`), so the two
  fallback states the shared enum still carries belong to the ServerOwner bootstrap invite alone
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md) §7). It is **rate-limited**
  per-(tenant, recipient) + per-tenant
  (429 + `Retry-After`, no fresh mint). Re-issue is the **sharpest email-bombing vector** — repeated fresh links
  to one address — so it is the primary thing the limiter bounds.

## User impact

You see who has not joined your space yet, can send their invitation again or cancel one sent by mistake, without
touching anyone already in it.

## Related

- [uc.spaces.add-member](uc.spaces.add-member.md) — mints the member invites this manages.
- [uc.admin.create-tenant](../../admin/use-cases/uc.admin.create-tenant.md) — the owner-invite management this
  mirrors (resend / reassign / revoke).
- [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) — admission vs. delivery; additive-only.
- End-user view (docs site): [Who gets into your space](../../../../site/src/content/docs/space-owners/who-gets-into-your-space.mdx)
