# UC: Learn of a new release without looking for one

- **ID:** uc.admin.learn-of-new-releases
- **Primary actor:** The **Server Owner**, in the admin console and in their mailbox
- **Goal:** Hear of a newer release, and above all of a new major, from the instance itself — with the date by which
  a planned major must be taken, and an unmistakable signal when one is urgent
  ([ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)).
- **Preconditions:** A running instance stamped with a release (an unstamped build checks nothing — it has no
  release to compare). For mail: instance mail settings saved and the Server Owner's address confirmed.
- **Postconditions (success):** Every release newer than the running one that the check found is shown in the admin
  console; each notice of decision 2 of ADR 0097 is mailed once, a dated one with its date as a calendar entry.

## Main flow

1. Once a day the instance reads the published releases from its source (by default the immutable GitHub Releases,
   [ADR 0096](../../../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)) and compares them with
   its own release **locally**; the request names no release.
2. The admin console's Server → Release panel, beside the running release
   ([uc.admin.identify-instance-release](uc.admin.identify-instance-release.md)), lists what was found: a newer
   release of the running major; a planned new major with the date the running major leaves the client window; an
   unscheduled new major, marked urgent.
3. The Server Owner opens Server → Mail, enters the instance's SMTP settings and saves. A test mail with a
   one-time code goes to the Server Owner's account address; typing the code into Server → Mail confirms it.
4. From then on, each notice is mailed once: what was found, the link to the Run area's upgrade page, and for a dated
   notice the date written out and attached as an `.ics` event. Reminders follow 90 and 30 days before the running
   major's window ends.

## Alternate / exception flows

- **A1 — The check is switched off.** Server → Release has the switch; off, the instance makes no request and shows
  that it does not look. The source can be pointed at a mirror instead.
- **A2 — The source cannot be reached.** Logged, retried the next day; nothing else changes and nothing is blocked.
  The panel shows when the last check succeeded.
- **A3 — No instance mail, or an unconfirmed address.** Notices appear in the console only. Changing the address or
  the settings asks for confirmation again.
- **A4 — The config-secret key is absent or changed.** The instance mail password cannot be unsealed; no mail is
  sent and the panel says why, as the tenant mail panel does ([ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md)).
- **A5 — A tenant owner.** Sees nothing of this; instance mail never sends a tenant's mail, and a tenant's settings
  never send these notices.

## User impact

None for members. The instance makes one outbound request a day unless switched off.

## Related

- [ADR 0097](../../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md),
  [ADR 0085](../../../decisions/0085-one-major-moved-on-a-yearly-train.md) (planned and unscheduled majors, the window),
  [ADR 0048](../../../decisions/0048-provider-neutral-smtp-mail-transport.md) (tenant mail, which this does not touch)
- [uc.admin.install-and-upgrade-instance](uc.admin.install-and-upgrade-instance.md),
  [uc.admin.tenant-mail-settings](uc.admin.tenant-mail-settings.md)
