# UC: Publish who runs the instance

- **ID:** uc.admin.operator-details
- **Primary actor:** The **Server Owner**, in the admin console and in `veyq-admin`
- **Goal:** Set the values a host publishes about itself — the instance contact, the provider identification, the
  competent data-protection supervisory authority, and for the privacy notice the recipients and a data-protection
  officer — each optional, knowing which of them an operator may owe
  ([ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 11 and 14, [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decisions 6–8).
- **Preconditions:** A set-up instance ([uc.admin.setup-server](uc.admin.setup-server.md)). For the notification
  address's code: instance mail configured and its address confirmed
  ([uc.admin.learn-of-new-releases](uc.admin.learn-of-new-releases.md)).
- **Postconditions (success):** The values are stored, the public ones served before sign-in, the notification address
  only on the admin channel and confirmed by a code before anything is mailed to it. Every fixed text that names a value
  shows it, or "not stated by this operator" while it is empty.

## Main flow

1. The Server Owner opens Server → Operator. Above the values it says, as information and not as a choice, what an
   operator may owe (ADR 0093 § 14): the GDPR's household exemption ends once a link is shared publicly; an operator who
   shares links publicly or runs veyq for a club or a company probably owes at least a name, a postal address and the
   controller's contact; it covers every space on the instance; and it is the Server Owner's own assessment, not
   veyq's. Nothing is ticked or chosen.
2. The Server Owner enters, each optional: the instance contact (an address and the languages it answers in), the
   address statements and disclosures name (may equal the contact), the notification address, the provider
   identification (name, address for service, email, and for a legal person its representative), the supervisory
   authority, the **recipients** the privacy notice names — each a name, what they do for the instance as a kind from a
   fixed list (hosts the server, stores the encrypted files, relays the mail, keeps the backups, or *other* with a short
   description of the Server Owner's own, which both language versions show as typed) and their country, picked from a
   searchable list the console names in its own language and stored as its ISO 3166-1 alpha-2 code, which the notice
   names in each of its languages — and a **data-protection officer** where one is appointed
   ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 8). An empty public field shows as not
   stated. The panel offers a suggested recipient row of its kind for what the instance itself knows — an external
   store's host, the instance mail relay's host — which the Server Owner completes and keeps or drops; a suggestion is
   never published until it is saved as a row. Saving stores them.
3. A saved notification address that is not confirmed gets a one-time code through instance mail; typing it into the
   same panel confirms it. Moderation notices reach it only once confirmed.
4. The contact with its languages, the statement address, the identification and the supervisory authority are served
   before sign-in, the same on every host; the web app shows the identification and the contact with its languages
   (the point of contact DSA Art. 11–12 and TCO Art. 15 ask to be public) on the login page, the share page and in
   the signed-in shell, beside a link to the public terms of the instance (the operator section of the terms,
   [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6) and to its privacy notice
   ([uc.admin.privacy-notice](uc.admin.privacy-notice.md)), which the same values fill; the recipients and the
   officer are public only through that notice.

## Alternate / exception flows

- **A1 — Values left empty.** Nothing is refused and nothing warns. The operator section of the terms, the privacy
  notice and the statement of reasons show each empty value as "not stated by this operator" wherever they name it,
  until the Server Owner fills it in; an optional line (a representative, an officer) is left out.
- **A2 — Instance mail not ready.** A notification address saved while instance mail is not configured and confirmed
  gets no code; the panel says so, and saving again once instance mail is confirmed sends one.
- **A3 — A wrong or stale code.** It is refused and spends one of a few attempts; a code expires; a changed
  notification address unconfirms the old confirmation without any further write, and asks again.
- **A4 — `veyq-admin`.** `operator show` prints every value as JSON, `null` where none is set; `operator set|clear`
  sets the values — `--recipients '<name>|<kind>|<country>; …'` replaces the whole list (the kind `hosting`,
  `object-storage`, `mail-relay`, `backup` or `other:<what they do>`; the country a two-letter ISO 3166-1 code such as
  `DE`, which may be left out; `none` empties it) and `--data-protection-officer` sets the officer; the code is typed in
  the console only, as instance mail's is.
- **A5 — The public read fails.** The login and share pages show no identification and block nothing.

## User impact

Members and guests can see who runs the instance and how to reach them, and where the operator stated nothing, that
nothing was stated; the operator learns what they may owe without veyq deciding it for them.

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  §§ 11, 14; [ADR 0035](../../../decisions/0035-server-terms-of-service.md) decisions 6–8;
  [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) decisions 3, 5;
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
- [uc.admin.setup-server](uc.admin.setup-server.md) (the same information at setup),
  [uc.admin.learn-of-new-releases](uc.admin.learn-of-new-releases.md) (instance mail)
- Run area of the docs site: [Moderation](../../../../site/src/content/docs/run/moderation.md) (the values, what an
  operator may owe), [First-run setup](../../../../site/src/content/docs/run/first-run.mdx) (the setup step, the panels,
  the CLI), [Configuration](../../../../site/src/content/docs/run/configuration.md); Use area:
  [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx) (the identification on the share page)
