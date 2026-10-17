# UC: Appoint moderators and keep the moderation key

- **ID:** uc.moderation.appoint-moderators
- **Primary actor:** The **Server Owner**, in admin-web's Server → Moderation or with `veyq-admin` over the admin channel
- **Secondary actor:** the appointed account (it accepts in [uc.moderation.serve-as-moderator](uc.moderation.serve-as-moderator.md))
- **Goal:** Decide who may act on reports for the whole instance, and keep the one key a report is sealed to held by
  exactly them ([ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  §§ 1, 3–5).
- **Preconditions:** A set-up instance; the Server Owner signed in to admin-web (or `veyq-admin`). An appointee already
  has an account (a tenant invite or setup made it; ADR 0099 § 1).
- **Postconditions (success):** The roster lists every moderator with its state; the current epoch of the instance
  moderation key is granted to every accepted moderator and to no one else; the server holds the epoch's public bundle
  and the sealed grants, never `M_n`.

## Main flow

1. **Moderation is on from first-run setup** (owner 2026-10-17, [uc.admin.setup-server](../../admin/use-cases/uc.admin.setup-server.md)).
   The setup client, which has just born the Server Owner's identity, draws a 32-byte `M_1`, derives the epoch's
   public bundle ([en.moderation.moderation-key](../enablers/en.moderation.moderation-key.md)), seals `M_1` to that
   identity (`moderation-grant` [epoch 1]) and sends the bundle and the grant with the account, then forgets `M_1`. The
   server stores epoch 1 and records the Server Owner as an accepted moderator holding it, in the setup's own
   transaction. The Server → Moderation panel shows epoch 1 and the Server Owner on the roster.
2. **Appoint.** The Server Owner enters an account's address (or runs `veyq-admin moderator appoint <address>`). The
   server finds the account and records a pending appointment (appointed by, appointed at); the roster shows it
   *pending*.
3. The appointee accepts and confirms their address in web/; a holder's client delivers every live epoch to them
   ([uc.moderation.serve-as-moderator](uc.moderation.serve-as-moderator.md)). The roster shows them *accepted*, then
   *holds the key*.
4. **Remove.** The Server Owner removes a moderator (or `veyq-admin moderator remove <address>`). One client step does
   two requests: the server records the removal and deletes the leaver's grants for **every** epoch at once (their
   `MODERATOR` role is gone with it), then admin-web mints the next epoch for the remaining accepted moderators (step 5,
   reason *removal*).
5. **Rotate.** admin-web (or `veyq-admin moderation rotate --reason <category>`) draws `M_{n+1}`, derives its bundle,
   seals it to the identity public key of every current accepted moderator as the roster serves it, and uploads the
   bundle with one grant per moderator and a reason category (removal, identity rotation, compromise, by hand). The
   server refuses the mint unless the grants cover exactly the accepted moderators. Reports from then on seal to the
   new epoch; older epochs stay granted to whoever holds them (ADR 0099 § 4).

## Alternate / exception flows

- **A1 — No account has the address.** The appointment is refused, and nothing is recorded; the panel says so. (The
  address is the Server Owner's input, read back to them only.)
- **A2 — Appointing someone already on the roster.** Idempotent: a pending appointment stays pending, an accepted one
  stays accepted; nothing is reset.
- **A3 — A mint that leaves someone out.** An accepted moderator who appeared between admin-web's roster read and the
  mint, or a grant dropped by mistake: the server refuses with `moderation_grants_incomplete`, nothing is stored, and
  admin-web shows the refusal and reads the roster again.
- **A4 — Removing the last holder.** Refused (`moderation_last_holder`) while it would leave no accepted moderator
  holding the current epoch: the key would be lost with them (ADR 0099 § 5).
- **A5 — A moderator steps down, or rotates their identity.** Their grants are gone at once, but reports keep sealing
  to the current epoch, which they held, until the Server Owner rotates (owner decision Q3 (a), 2026-10-17). The panel
  and one instance mail to the Server Owner say **rotation due** ([ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
  decision 6); a mint clears it.
- **A6 — The Server Owner steps down as a moderator.** Allowed while another accepted moderator holds the key (ADR 0099
  § 5, decided with the owner 1); they can appoint themselves again like anyone else.
- **A7 — An instance with no epoch** (none that setup made can be one). The panel says moderation is off and
  reporting is off with it; there is no operation that makes epoch 1 afterwards.
- **A8 — On the public listener.** Every operation here is a Server-Owner operation on the admin channel; the public
  port answers it as an unknown route ([ADR 0065](../../../decisions/0065-admin-ui-cli-separate-service.md)).

## Quality notes

- **admin-web holds no vault.** It mints and forgets: it never opens a grant, and its module imports no open function.
- **Grants are unsigned** (owner decision Q4): admin-web has no identity signing key, and a server that substitutes a
  moderator's identity key or a grant must also substitute the published bundle to read anything, which an active
  operator — the moderation authority itself — can do anyway (ADR 0099 § 3). The moderators' published-key comparison
  is the check that matters.
- Every change writes its instance event to the moderation log in the same transaction
  ([en.moderation.log](../enablers/en.moderation.log.md)).

## User impact

A club or a company can name deputies who act on reports while the operator is away, and the key a report is sealed
to follows who is on the roster.

## Related

- [ADR 0099](../../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 11,
  [ADR 0065](../../../decisions/0065-admin-ui-cli-separate-service.md) (the moderator as the fifth audience),
  [ADR 0024](../../../decisions/0024-scope-key-rotation.md) (forward-only rotation, the same property)
- [uc.moderation.serve-as-moderator](uc.moderation.serve-as-moderator.md),
  [en.moderation.moderation-key](../enablers/en.moderation.moderation-key.md),
  [en.moderation.log](../enablers/en.moderation.log.md)
- Docs site: the Run area's [Moderation](../../../../site/src/content/docs/run/moderation.md) (the commands) and the
  Security area's [Roadmap](../../../../site/src/content/docs/security/roadmap.md)
