# UC: Read the instance's privacy notice

- **ID:** uc.admin.privacy-notice
- **Primary actor:** **Anyone who reaches the instance** — a person about to accept an invite, a member, a reporter, a
  guest — in web/, with or without signing in
- **Secondary actor:** the **Server Owner**, who sets the values it renders in admin-web or `veyq-admin` — the
  provider identification, the contact, the supervisory authority, the recipients and a data-protection officer
  ([uc.admin.operator-details](uc.admin.operator-details.md))
- **Goal:** Learn who processes what about you on this instance, why, for how long, and what you can do about it
  ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 8; GDPR Art. 13).
- **Preconditions:** None; the notice is public on every tenant host.
- **Postconditions (success):** The reader has read the current notice in their language, filled with the instance's
  values; nothing was recorded about the reading.

## Main flow

1. The reader follows the privacy link: from the login page, the share page, the signed-in app's footer, the invite
   acceptance, the report dialog, or a guest's first-write notice.
2. web/ fetches the rendered notice without a session and shows it on its own page (`/privacy`), the passage the link
   named in view: the controller and contact, any data-protection officer, then one passage per processing — what,
   why, on which basis, for how long, who receives it — then the recipients the Server Owner named, the rights and the
   supervisory authority, with each right to object set apart (GDPR Art. 21(4)).
3. The passages follow the instance and the host: a switch that is off there (the guest-write record, the
   breached-password check, an external service a member's browser calls) drops its passage, a service's passage names
   the address the instance actually uses, and N, B and the erasure's window are the values set now.

## Alternate / exception flows

- **A1 — A value not set:** in the *club, organisation or company* mode the passage shows "not stated by this
  operator" and admin-web warns; in the *private* mode it is absent and nothing warns.
- **A2 — The notice cannot be fetched:** the link's page says so and names the instance contact where the public
  operator values load; nothing is blocked by it.
- **A3 — A guest objects to the guest-write record:** a guest has no account, so the line on a link's page that says
  whose session the device holds shows the guest's id, to copy into the objection the record's passage asks them to
  write (GDPR Art. 21(1) with 12(2)). The id is the guest identity's random id and says nothing else. The Server Owner
  erases that identity's rows (`veyq-admin guest-write-record forget`); an objection erases what was recorded so far
  and does not stop a later write from being recorded, and the passage says so (owner 2026-10-18).

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
