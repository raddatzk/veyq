# UC: Set requirements on capabilities

- **ID:** uc.admin.capability-requirements
- **Primary actor:** The **Server Owner** (admin console or CLI) for the instance, and a **tenant owner** (the web
  client's tenant settings) for their tenant
- **Goal:** Tighten how a capability may be used instead of switching it off: every public link needs a password, a
  link lives at most N days, nobody is added to a group without saying yes, a signed-in device asks for the password
  again after N days ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 8). Whoever sets one knows before saving what it does to what is new and to what already exists.
- **Preconditions:** The actor holds the session the level needs: the Server Owner on the admin channel, the tenant
  owner an OWNER membership on the tenant's root scope.
- **Postconditions (success):** The level holds the value. The effective value is the strictest of both levels: a
  flag is set when either level sets it, a number of days is the smaller one. A tenant therefore tightens and never
  loosens. Every gate and every client follows it without a restart. Nothing a member stored is deleted.
- **Enabler:** [en.platform.capability-register](../../platform/enablers/en.platform.capability-register.md) (the register, its two levels and what "off" does).

## The requirements built

| Entry | Requirement | Levels | What the server refuses, new | What it does to what exists |
|---|---|---|---|---|
| `core.public-shares` | `linkPassword`: a link needs a password | instance, tenant | creating or renewing a link without one, removing a link's password (`422 link_password_required`) | a link without a password answers guests as an unknown link (`404`) until its owner gives it one |
| `core.public-shares` | `linkMaxDays`: a link lives at most N days | instance, tenant | a link with no end date or one more than N days after it was made (`422 link_expiry_required`) | a link with no end date, or one further than N days from when it was made, answers guests `404` until its owner sets an end within N days of when it was made; an older link is renewed |
| `core.group-space` | `addConsent`: being added to a group needs the person's yes | instance, tenant | a direct add: the owner's client is told consent is required, and an add without the sealed name key is `400 consent_required` | members already added stay; nothing changes for them |
| `core.remember-device` | `passwordMaxDays`: a device asks for the password again after N days | instance | renewing a session more than N days after it began (`401`, as an expired session) | every session older than N days ends at its next renewal; the device signs in with the password, and a remembered device then opens as before |

The requirements apply to every role of link (`.contribute` and `.drop-box` hang below `core.public-shares`). Each
requirement's words, in English and German, are the catalog's (`contracts/platform/capabilities.json`), served with the
register (ADR 0088 decision 13).

## Main flow

1. The actor opens the register (admin console **Server → Capabilities**, `capabilities list` in the CLI, or the web
   client's **Settings → Capabilities**). Each entry lists its requirements with what each does and its value at this
   level; the tenant owner also sees the server's value and the effective one.
2. They set a requirement: switch a flag on, or enter a number of days (1 to 3650). The panel first shows what it does
   to what exists, in the requirement's own words, and asks for confirmation.
3. The server stores the value and answers with the requirement as it now stands. From then on every client reads the
   effective value with the capability (`[{ id, …values }]`, ADR 0032 §2), the share dialogs ask for what is required,
   and every gate refuses what does not meet it.
4. A member's share list marks every link a requirement stops and names the requirement (with the days in effect).
   Its owner gives that same link what it misses from the row, and it serves again with its address and key; a link
   older than the lifetime is renewed as a new link, and the row says so (inbox #457,
   [uc.sharing.share-shared-scope](../../sharing/use-cases/uc.sharing.share-shared-scope.md) A8).
5. Clearing a requirement restores everything: a link it refused serves again, because nothing was written onto it.

## Alternate / exception flows

- **A1, a looser tenant value:** stored, and shown as not in effect: the server's stricter value wins.
- **A2, a requirement of an instance-only entry at the tenant level** (`passwordMaxDays`): refused
  (`404 requirement_not_found`), as an unknown requirement is.
- **A3, a flag with a value other than on:** `400 invalid_request`; a number of days outside 1–3650: the same.
- **E1, not the owner of the level:** `403 not_server_owner` / `403 not_tenant_owner`; a non-member `404`.

## Quality notes

- **Operator-blind.** A requirement is configuration. Checking it reads what the server already holds about a link
  (whether a password verifier exists, the end date, when it was made) and about a session (when it began); it reads
  no content and learns no password.
- **What it cannot do.** A link password is chosen by the member; the server sees only that a verifier exists, never
  how strong the password is. `passwordMaxDays` counts from the session's sign-in or its device linking: a device added
  from another one without typing the password starts a fresh count, which `core.device-linking` switched off prevents.

## User impact

Whoever runs your server, or owns your space, can make links safer and adds to groups consensual without anything
stored being deleted: a link that does not meet a new rule pauses until its owner fixes it.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.admin.switch-capabilities](uc.admin.switch-capabilities.md),
  [uc.admin.tenant-switch-capabilities](uc.admin.tenant-switch-capabilities.md),
  [uc.sharing.share-public-viewer](../../sharing/use-cases/uc.sharing.share-public-viewer.md),
  [uc.spaces.consent-group-add](../../spaces/use-cases/uc.spaces.consent-group-add.md),
  [uc.account.remember-this-device](../../account/use-cases/uc.account.remember-this-device.md)
- **Decisions:** [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md),
  [ADR 0004](../../../decisions/0004-session-tokens.md) (the session family a renewal belongs to)
- **On the docs site:** [Capabilities](../../../../site/src/content/docs/run/capabilities.mdx) (both levels, the CLI),
  [What your space offers](../../../../site/src/content/docs/space-owners/capabilities.mdx) (a tenant owner's) and
  [Create a link](../../../../site/src/content/docs/sharing/create-a-link.mdx) (what a member sees in the form).
