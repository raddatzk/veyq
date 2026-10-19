# UC: Choose the operating mode and publish who runs the instance

- **ID:** uc.admin.operator-details
- **Primary actor:** The **Server Owner**, in the admin console and in `veyq-admin`
- **Goal:** Say how the instance is run — *private* (family, circle of friends) or *club, organisation or company* —
  on the operator's own assessment, and set the values a host publishes about itself: the instance contact, the
  provider identification, the competent data-protection supervisory authority, and for the privacy notice the
  recipients and a data-protection officer
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 11 and 14, [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decisions 6–7,
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 14).
- **Preconditions:** A set-up instance ([uc.admin.setup-server](uc.admin.setup-server.md), which asks the mode). For
  the notification address's code: instance mail configured and its address confirmed
  ([uc.admin.learn-of-new-releases](uc.admin.learn-of-new-releases.md)).
- **Postconditions (success):** The mode is stored, and the instance-level switch of every register entry the catalog
  marks per mode is set to that mode's value; the values are stored, the public ones served before sign-in, the
  notification address only on the admin channel and confirmed by a code before anything is mailed to it.

## Main flow

1. The Server Owner opens Server → Operating mode. It shows the mode in effect (an instance where none was ever chosen
   runs as *club, organisation or company*), the notice — that the choice is the operator's own assessment, what
   *private* leaves off and why a public link probably ends the household exemption — and, for the other mode, every
   switch its preset would change.
2. Choosing *private* needs a tick that the notice was read. Setting the mode stores it and, in the same transaction,
   sets the instance-level switch of each entry the catalog's `modes` names to that mode's value. A tenant's switches
   and every entry without a preset stay as they are; each preset switch stays an ordinary switch afterwards.
3. The Server Owner opens Server → Operator and enters, each optional: the instance contact (an address and the
   languages it answers in), the address statements and disclosures name (may equal the contact), the notification
   address, the provider identification (name, address for service, email, and for a legal person its
   representative), the supervisory authority, the **recipients** the privacy notice names — each a name, what they
   do for the instance as a kind from a fixed list (hosts the server, stores the encrypted files, relays the mail,
   keeps the backups, or *other* with a short description of the Server Owner's own, which both language versions show
   as typed) and their country, picked from a searchable list the console names in its own language and stored as
   its ISO 3166-1 alpha-2 code, which the notice names in each of its languages — and a **data-protection officer**
   where one is appointed
   ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 8). The panel offers a suggested recipient
   row of its kind for what the instance itself knows — an external store's host, the instance mail relay's host —
   which the Server Owner completes and keeps or drops; a suggestion is never published until it is saved as a row. Saving
   stores them.
4. A saved notification address that is not confirmed gets a one-time code through instance mail; typing it into the
   same panel confirms it. Moderation notices reach it only once confirmed.
5. The contact with its languages, the statement address, the identification and the supervisory authority are served
   before sign-in, the same on every host; the web app shows the identification and the contact with its languages
   (the point of contact DSA Art. 11–12 and TCO Art. 15 ask to be public) on the login page, the share page and in
   the signed-in shell, beside a link to the public terms of the instance (the operator section of the terms,
   [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6) and to its privacy notice
   ([uc.admin.privacy-notice](uc.admin.privacy-notice.md)), which the same values fill; the recipients and the
   officer are public only through that notice.

```mermaid
---
title: Operating mode — what a change sets
---
sequenceDiagram
  actor O as Server Owner
  participant A as admin-web / veyq-admin
  participant S as Server
  A->>S: read the mode and the preset
  S-->>A: mode, each preset entry with its value per mode and its switch now
  A->>O: the notice#59; the switches the other mode would change
  O->>A: choose (private: tick / --accept-notice)
  A->>S: set the mode
  S->>S: store the mode and each preset entry's instance switch, one transaction
  S-->>A: the mode and the preset as they now stand
```

## Alternate / exception flows

- **A1 — Values left empty.** Nothing is refused, in either mode. In *club, organisation or company* the admin console
  warns while public links (`core.public-shares`) are on and no contact is set, and, independently, while the
  identification lacks its name, its address for service or its email (a half-filled identification is as
  incomplete as an empty one; the representative is never asked for, since the console cannot tell a legal person),
  and, independently, while the privacy notice lacks its recipients or the supervisory authority (it then shows "not
  stated by this operator"; the officer is never asked for, since only some controllers must appoint one).
  In *private* no warning shows; a value that is set is still published.
- **A2 — Instance mail not ready.** A notification address saved while instance mail is not configured and confirmed
  gets no code; the panel says so, and saving again once instance mail is confirmed sends one.
- **A3 — A wrong or stale code.** It is refused and spends one of a few attempts; a code expires; a changed
  notification address unconfirms the old confirmation without any further write, and asks again.
- **A4 — `veyq-admin`.** `mode show` prints the mode, the notice and the preset; `mode set private` refuses without
  `--accept-notice` and prints the notice either way. `operator show|set|clear` sets the values — `--recipients
  '<name>|<kind>|<country>; …'` replaces the whole list (the kind `hosting`, `object-storage`, `mail-relay`, `backup`
  or `other:<what they do>`; the country a two-letter ISO 3166-1 code such as `DE`, which may be left out; `none`
  empties it) and
  `--data-protection-officer` sets the officer; the code is typed in the console only, as instance mail's is.
- **A5 — The public read fails.** The login and share pages show no identification and block nothing.

## User impact

Members and guests can see who runs the instance and how to reach them; a family instance is not nagged into an
imprint it may not need, and the operator's choice is theirs and stated as such.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 11, 14; [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decisions 6–7;
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 14;
  [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3, 5;
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
- [uc.admin.setup-server](uc.admin.setup-server.md) (the first choice of the mode),
  [uc.admin.switch-capabilities](uc.admin.switch-capabilities.md) (the switches a preset sets),
  [uc.admin.learn-of-new-releases](uc.admin.learn-of-new-releases.md) (instance mail)
- Run area of the docs site: [Moderation](../../../../site/src/content/docs/run/moderation.md) (the mode, the values,
  the warnings), [First-run setup](../../../../site/src/content/docs/run/first-run.mdx) (the setup step, the panels, the
  CLI), [Configuration](../../../../site/src/content/docs/run/configuration.md); Use area:
  [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx) (the identification on the share page)
