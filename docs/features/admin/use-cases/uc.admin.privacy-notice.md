# UC: Read the instance's privacy notice

- **ID:** uc.admin.privacy-notice
- **Primary actor:** **Anyone who reaches the instance** — a person about to accept an invite, a member, a reporter, a
  guest — in web/, with or without signing in
- **Secondary actor:** the **Server Owner**, who sets the values it renders in admin-web or `veyq-admin`
- **Goal:** Learn who processes what about you on this instance, why, for how long, and what you can do about it
  ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 8; GDPR Art. 13).
- **Preconditions:** None; the notice is public on every tenant host.
- **Postconditions (success):** The reader has read the current notice in their language, filled with the instance's
  values; nothing was recorded about the reading.

## Main flow

1. The reader follows the privacy link: from the login page, the share page, the signed-in app's footer, the invite
   acceptance, the report dialog, or a guest's first-write notice.
2. web/ fetches the rendered notice without a session and shows it on its own page: the controller and contact, any
   data-protection officer, then one passage per processing — what, why, on which basis, for how long, who receives it
   — then the rights and the supervisory authority, with the right to object set apart.
3. The passages follow the instance: a switch that is off (the guest-write record, the breached-password check, an
   external service) drops or changes its passage, and N and B are the values set now.

## Alternate / exception flows

- **A1 — A value not set:** in the *club, organisation or company* mode the passage shows "not stated by this
  operator" and admin-web warns; in the *private* mode it is absent and nothing warns.
- **A2 — The notice cannot be fetched:** the link's page says so and names the instance contact where the public
  operator values load; nothing is blocked by it.

## Quality notes

- The text is veyq's and fixed per release; the Server Owner sets values, never prose (as the operator section).
- Public, operational, and never a gate: reading it is not consent and is not recorded.

## User impact

Anyone can read, before they sign up and at any time after, what the instance keeps about them and why.

## Related

[ADR 0035](../../../decisions/0035-server-terms-of-service.md) decisions 6–8,
[uc.admin.operator-details](uc.admin.operator-details.md),
[ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 10 and Still open 6, 7 and 10.
