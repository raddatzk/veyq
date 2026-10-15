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
   to existing data, in the entry's own words, and asks for confirmation. The CLI prints the same text.
4. The backend stores the switch and answers with the new state. From then on the entry is missing from the
   pre-auth manifest (`GET /api/setup`). Its gates refuse with `422 capability_disabled`. Where the entry acts on
   existing data (ADR 0088 decision 5), that takes effect too.
5. Switching the entry back **on** restores it. An entry's own state survives while its parent is off and applies
   again once the parent is on.

## Alternate / exception flows

- **A1, an unknown id** (CLI typo, an id a later release removed): refused, and nothing is stored.
- **A2, a device switch:** when `core.remember-device` or `core.remember-device.passwordless` is switched off,
  remembered devices stop unlocking without what that wrap replaced. Without the `DEVICE` wrap a member needs the
  password **and the Secret Key**. Without the `DEVICE_KEY` wrap a member needs the password. The `PASSWORD` wrap
  is always served, so nobody is locked out, and the confirmation text says so in those words.
- **E1, not the Server Owner:** `403 not_server_owner`; an ordinary member's session never reaches this.

## Quality notes

- **One source for the words.** The title, purpose and effect-when-off of each entry are written once, in English
  and German, in `contracts/platform/capabilities.json`. The API serves them, and neither the console nor the CLI
  keeps a copy (ADR 0088 decision 13).
- **The text says what is built.** A share switch does not reach existing links until inbox #442 lands, and its
  effect text says exactly that until then.
- **Operator-blind.** A switch is instance configuration. It carries no member data, and withholding a wrap
  reveals nothing the server did not already hold.

## What this means for you / Was das für dich bedeutet

**EN —** The person who runs your instance can decide that some things are not offered on it, for example public
links or devices that unlock without your password. Switching something off never deletes what you stored. If
remembered devices are switched off, your device asks for your password again, and sometimes for your Secret Key
from the Emergency Kit. Your data stays readable with them.

**DE —** Die Person, die deine Instanz betreibt, kann festlegen, dass manches dort nicht angeboten wird, zum
Beispiel öffentliche Links oder Geräte, die sich ohne dein Passwort entsperren. Abschalten löscht nie etwas, das du
gespeichert hast. Werden gemerkte Geräte abgeschaltet, fragt dein Gerät wieder nach deinem Passwort und manchmal nach
deinem Secret Key aus dem Emergency Kit. Mit ihnen bleiben deine Daten lesbar.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.platform.capability-discovery](../../platform/use-cases/uc.platform.capability-discovery.md)
  (the manifest a switch changes), [uc.admin.admin-channel](uc.admin.admin-channel.md),
  [uc.account.trust-device](../../account/use-cases/uc.account.trust-device.md) and
  [uc.account.remember-this-device](../../account/use-cases/uc.account.remember-this-device.md) (the wraps a device
  switch withholds).
- **Decisions:** [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md),
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md),
  [ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md).
