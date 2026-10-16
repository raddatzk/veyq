# UC: Know which release an instance runs, and which release a backup came from

- **ID:** uc.admin.identify-instance-release
- **Primary actor:** The **Server Owner** — in the common single-node deployment the same person as the operator
  ([ADR 0006](../../../decisions/0006-cold-tenant-scope-birth.md)), here signed in to the app rather than at a
  shell
- **Goal:** Be able to **name** the release an instance is running, and the release a given backup was taken
  from, so that an upgrade has a rollback target that can be stated rather than guessed
  ([ADR 0057](../../../decisions/0057-release-identity-and-deployment-recipe.md),
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) §6).
- **Preconditions:** A session as the **Server Owner**. The instance is running an image produced by the
  release pipeline (a build with no release stamp is A1, not an error).
- **Postconditions (success):** The Server Owner can read the running release version; a backup manifest states
  the release of the instance it was captured from; and neither value appears on any unauthenticated surface.

## Why this is not the version the client already reads

`GET /api/setup` advertises `version: { major, build }` pre-auth, and that is a **different number answering a
different question** ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md)): *can this
client and this server talk to each other?* It gates before login because that is where a client must find out.

This use-case is about the other question — *which bits are running, and which do I go back to?* — and its
audience is the operator, not the client. The two are kept apart deliberately
([ADR 0057](../../../decisions/0057-release-identity-and-deployment-recipe.md) §4): a precise release number is
what maps an instance to a list of known vulnerabilities, and a self-hosted instance is the kind that upgrades
late. The feature set is already public via the capability manifest; the release number is not, and does not
become so.

## Main flow

1. The Server Owner, signed in, **reads the instance's release version** — the version the running image was
   stamped with at publish.
2. The Server Owner **takes a backup** before an upgrade. The capture records the release it was taken from in
   the backup's manifest, next to the schema version it already records.
3. An upgrade that goes wrong therefore has a **stated** target: the manifest names the release to return to,
   and that release is a tag that exists and cannot have been republished with different bits
   (ADR 0057 §1).

## Alternate / exception flows

- **A1 — The build carries no release stamp.** A locally built image or a `quarkusDev` run was never published,
  so there is no version to report. It says exactly that, and does not synthesise a number. A fabricated version
  is worse than an absent one here: it is the value an operator would later try to roll back *to*.
- **A2 — The caller is not the Server Owner.** A member, a share guest, or an unauthenticated caller does not
  receive the release version — from any surface, including `GET /api/setup`, whose response is unchanged. This
  is the negative that keeps §4 of ADR 0057 true, and it is asserted rather than assumed.
- **A3 — The backup came from an unversioned build.** The manifest records the absence, in the same shape as
  A1. A backup that cannot name its source release is still a valid backup; what it cannot do is tell you which
  image to start it under, and it says so instead of implying one.
- **A4 — The running container is not what the deployment file pins.** Reading the pin from a compose file
  answers what was *configured*; this answers what is *running*. They diverge after a pull that was never
  restarted, or a restart that silently reused a cached image — which is the case where an upgrade appears to
  have happened and did not.
- **A5 — The instance runs a snapshot.** The version reads `<major>.<minor>-SNAPSHOT`, and it is reported as
  it is: a snapshot is a published, signed build, but its name is the one that moves (ADR 0057 §1). So it is not
  a rollback target, and a backup of a snapshot instance names a build that may no longer exist under that tag.
  What does still name it is the digest the operator pinned.

## Quality notes

- **A rollback target is only as good as the immutability of the name.** This use-case is worth nothing on its
  own: it depends on ADR 0057 §1, where a publish onto an existing tag fails rather than replacing it. A version
  that can be republished names a moving artifact, and reporting it accurately would still mislead.
- **Absence is a first-class answer.** Both A1 and A3 report "no release stamp" rather than a placeholder,
  because every consumer of this value uses it to *choose an image*. This is the same rule the backup manifest's
  `app.version` broke by being structurally always `null` while looking like data.
- **Nothing here weakens operator-blindness.** The release version is a property of the software, not of any
  user's content or keys — and it is disclosed to fewer parties after this than the capability manifest already
  discloses the feature set to.

## User impact

When an update goes wrong, the person running your server can name the exact release they came from and put it back,
instead of guessing with your data.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.admin.back-up-and-restore-instance](uc.admin.back-up-and-restore-instance.md) (the capture
  whose manifest carries the release), [uc.platform.capability-discovery](../../platform/use-cases/uc.platform.capability-discovery.md)
  (the pre-auth version this one is deliberately not).
- **Decisions:** [ADR 0057](../../../decisions/0057-release-identity-and-deployment-recipe.md) (release identity
  and the two version notions), [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md)
  (the upgrade that begins with a backup and rolls back by restoring it),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the protocol version).
- **Ops:** the docs site's [Verify a release](../../../../site/src/content/docs/run/verify-a-release.md) owns the commands; this use-case owns the goal and the
  failure modes ([admin feature note](../_index.md)).
