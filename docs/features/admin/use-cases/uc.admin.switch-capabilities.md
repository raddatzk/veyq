# UC: Switch the instance's capabilities on and off

- **ID:** uc.admin.switch-capabilities
- **Primary actor:** The **Server Owner**, signed in to the admin console (admin-web) or using its CLI
- **Goal:** Decide what this instance offers at all, at the instance level of the capability register
  ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)).
  Examples are no public links, no anonymous drop boxes, or no device that unlocks without the password. The
  Server Owner knows before switching what each entry is for and what switching it off does.
- **Preconditions:** The instance is set up and the Server Owner holds a session on the admin channel
  ([uc.admin.admin-channel](uc.admin.admin-channel.md)).
- **Postconditions (success):** The instance holds the set of entries switched off. Every client's manifest
  and every server gate follow it without a restart. Nothing a member stored is deleted.

## Main flow

1. The Server Owner opens **Server → Capabilities** in the admin console, or runs `capabilities list` in the CLI.
2. They see the register as a tree. Each entry shows its title, what it is for, and whether it is **on**, **off**,
   or **off through its parent**. The text is in the console's language, or in the CLI's locale.
3. They switch an entry **off**. Before it takes effect, the console shows what switching it off does to new and
   to existing data, in the entry's own words, and asks for confirmation. The CLI asks nothing: `capabilities off
   <id>` switches and then prints the same text, taken from the backend's answer for the entry it switched.
4. The backend stores the switch and answers with the new state. From then on the entry is missing from the
   pre-auth manifest (`GET /api/setup`). Its gates refuse with `422 capability_disabled`. Where the entry acts on
   existing data (ADR 0088 decision 5), that takes effect too; a member's share list then marks every link a share
   switch stops and says it is switched off on this server (inbox #457).
5. Switching the entry back **on** restores it. An entry's own state survives while its parent is off and applies
   again once the parent is on.

## Alternate / exception flows

- **A1, an unknown id** (CLI typo, an id a later release removed): refused, and nothing is stored.
- **A2, a device switch:** when `core.remember-device` or `core.remember-device.passwordless` is switched off, the
  key material leaves that wrap out, and remembered devices stop unlocking without what it replaced. Without the
  `DEVICE` wrap a member needs the password **and the Secret Key**. Without the `DEVICE_KEY` wrap a member needs the
  password **and the Secret Key** too: a device holds one convenience wrap
  ([ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md) §9), so a password-less device has no
  `DEVICE` wrap to fall back on, only the `PASSWORD` floor. The sign-in screen fills in the email from the device's
  one local record, asks for the Secret Key at once, and the field's hint line says why (`unlock.deviceHeldBack`)
  instead of the first-sign-in hint, before anything is typed; the device's own line in Devices and sessions says
  it is held back (`sessions.deviceHeldBack`) instead of how it unlocks. The device keeps its local key and is
  never re-enrolled to the other tier while its wrap is withheld. A remembered device that signs in meanwhile also
  keeps its persistent session cookie, although the "stay signed in" toggle is hidden: the cookie's lifetime is a
  session matter ([ADR 0004](../../../decisions/0004-session-tokens.md)) that no switch gates, and it opens no vault
  while the wrap is withheld. So switching back on restores every remembered device, across a browser restart too.
  The `PASSWORD` wrap is always served, so nobody is locked out, and the confirmation text says so in those words.
- **A3, a share switch:** when `core.public-shares`, `core.public-shares.contribute` or `core.public-shares.drop-box`
  is switched off (the parent switches all three), every link of the role it reaches stops at once. Every guest
  endpoint, in core and in each application, answers such a link exactly as a link that never existed: the same
  `404`, before any password is asked, so a guest sees the page an expired link shows and learns nothing of the
  instance's policy. Nothing is written onto the link, and what guests added stays, so switching back on serves every
  link again as it was. A member can no longer change a link to look only into one to look and add while
  `.contribute` is off (`422 capability_disabled`, as creating one). A download or upload URL the server signed just
  before runs to its expiry, as after a revoke.
- **E1, not the Server Owner:** `403 not_server_owner`; an ordinary member's session never reaches this.

## Quality notes

- **One source for the words.** The title, purpose and effect-when-off of each entry are written once, in English
  and German, in `contracts/platform/capabilities.json`. The API serves them, and neither the console nor the CLI
  keeps a copy (ADR 0088 decision 13).
- **The text says what is built.** Each entry's effect text says what switching it off does to new and to existing
  data as built, a share switch's included (A3).
- **Operator-blind.** A switch is instance configuration. It carries no member data, and withholding a wrap
  reveals nothing the server did not already hold.

## User impact

The person running a server can decide what it offers, such as public links or remembered devices, without anything
you stored being deleted: switched back on, everything works as before.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.platform.capability-discovery](../../platform/use-cases/uc.platform.capability-discovery.md)
  (the manifest a switch changes), [uc.admin.admin-channel](uc.admin.admin-channel.md),
  [uc.account.trust-device](../../account/use-cases/uc.account.trust-device.md) and
  [uc.account.remember-this-device](../../account/use-cases/uc.account.remember-this-device.md) (the wraps a device
  switch withholds),
  [uc.sharing.share-public-viewer](../../sharing/use-cases/uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) and
  [uc.sharing.share-uploader](../../sharing/use-cases/uc.sharing.share-uploader.md) (the links a share switch stops).
- **Decisions:** [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md),
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md),
  [ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md).
- **On the docs site:** [Capabilities](../../../../site/src/content/docs/run/capabilities.mdx) (the panel, the CLI
  commands and every entry's words, for the Server Owner) and
  [Moderation](../../../../site/src/content/docs/run/moderation.md) (switching as one of the operator's levers).
