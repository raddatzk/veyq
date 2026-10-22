# UC: Serve as a moderator

- **ID:** uc.moderation.serve-as-moderator
- **Primary actor:** A **moderator** — an account the Server Owner appointed — in src/web/'s moderation surface
  (`/moderation` on any tenant host they can sign in on)
- **Secondary actor:** another moderator holding the key (whose client delivers it)
- **Goal:** Take up the appointment, receive the instance moderation key, hand it on to the next newcomer, and open the
  queue holding that key and nothing of the vault
  ([ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  §§ 1, 3, 6, 7).
- **Preconditions:** The Server Owner appointed this account ([uc.moderation.appoint-moderators](uc.moderation.appoint-moderators.md));
  instance mail is set up for the confirmation code to reach them
  ([ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3, 5).
- **Postconditions (success):** The account holds the `MODERATOR` role and a grant for every live epoch; its address is
  confirmed for moderation notices; the surface shows the queue, holding the moderation key and neither the Master Key
  nor the identity private key.

## Main flow

1. The appointee signs in on a tenant host and opens `/moderation`: from the settings hub's **Moderation** section,
   which the hub shows only to an account whose appointment is pending or accepted (it reads **my appointment** too),
   or by its address. The section is a door, never the surface: it loads `/moderation` afresh in a new tab, because the
   hub runs with the member's vault open and the page that opens reports holds nothing of it (ADR 0099 § 6). The
   surface reads **my appointment**; for a pending one it shows what accepting means: they will see what people report, child sexual abuse material included, and
   every opening of a report is logged with their account.
2. They accept. The server records the acceptance — the account now carries the `MODERATOR` role — and mails a one-time
   code to their account address through the instance mail.
3. They type the code into the surface; their address is confirmed for moderation notices (ADR 0097 decision 5).
4. **Unlock.** The surface asks for the password **and** the Secret Key, whatever the device holds (owner decision Q2
   (a)): it opens the vault, fetches no scope grant and mounts no member shell. It opens every moderation grant the
   account holds and compares each epoch's public bundle derived from the opened `M_n` with the one the server
   publishes; a mismatch stops the unlock (ADR 0099 § 3).
5. **Deliver.** The surface proves the key (a signed `moderation-proof` over a fresh server challenge) and lists the
   accepted moderators still missing an epoch. For each it checks the recipient's identity against this account's pin
   (ADR 0028: first seen is pinned, a changed one is declined as a scope grant's delivery is), seals every live epoch it
   holds to that identity (`moderation-grant` [epoch]) and uploads them. The delivery needs the pin store, so it runs
   in this unlock, before step 6.
6. The surface zeroes the Master Key and the identity private key and keeps only the moderation keys. With a fresh
   proof it opens the queue: empty until reports exist.

## Alternate / exception flows

- **A1 — Not appointed.** The surface says this account is not a moderator here and offers nothing.
- **A2 — Accepted, not yet holding the key.** The surface says the key arrives when a moderator who holds it next opens
  the surface; until then nothing needs the key.
- **A3 — The published key differs from the one opened.** The unlock stops with a warning naming the epoch; nothing is
  delivered and no proof is signed.
- **A4 — A recipient's identity changed since this account pinned it.** That delivery is declined and left for review,
  as a scope grant's is; the others go ahead.
- **A5 — The code expired, was entered wrong too often, or the address changed.** The surface sends a new code on
  request; until one is entered, moderation notices are not mailed to this address.
- **A6 — Step down.** With a key proof the moderator steps down: the role and every grant go at once, and the panel in
  admin-web says rotation due until the Server Owner mints (owner decision Q3 (a)). Refused while it would leave no
  accepted moderator holding the key. A moderator who never received the key asks the Server Owner to remove them.
- **A7 — A stolen session.** Without the moderation key every key-proved operation is refused; with a removed
  moderator's session there is no role at all.
- **A8 — The moderator rotates their identity** ([ADR 0060](../../../decisions/0060-key-rotation-after-compromise.md)).
  The grants their old identity opened are treated as exposed and deleted; a holder delivers the live epochs again at
  the new identity, and the Server Owner is told rotation is due.

## Quality notes

- **The page that opens reports holds the moderation key and nothing of the vault** (ADR 0099 § 6). No trusted-device
  wrap unlocks it, and the surface never offers one (ADR 0099 § 6; owner decision Q2).
- **The role names who acted; the proof makes a stolen session without the key worth nothing** (ADR 0099 § 2). Reading
  my appointment, accepting, confirming the address, reading my grants and asking for a challenge carry no proof — a
  newcomer has no key yet — and every other moderation operation does.
- The surface is operator-served web, passive-operator-safe only ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).

## User impact

A deputy can act on reports from any space they can sign in to, and the page that shows reported content holds no key
to anything else of theirs.

## Related

- [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) (the pin check before sealing to a
  foreign identity), [ADR 0020](../../../decisions/0020-grant-distribution-invite-wrap.md) (a key-holder delivers, the
  pattern reused)
- [uc.moderation.appoint-moderators](uc.moderation.appoint-moderators.md),
  [en.moderation.moderation-key](../enablers/en.moderation.moderation-key.md),
  [en.moderation.log](../enablers/en.moderation.log.md)
- Docs site: [Moderate an instance](../../../../site/src/content/docs/account/moderate.md)
