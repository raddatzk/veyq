# Architecture Decision Records (ADRs)

Short, numbered records of **stable** architectural or product decisions — the durable trace of *why*
something is the way it is. One file per decision: `NNNN-kebab-title.md`, numbered from `0001`.

- **Status:** `Proposed` · `Accepted` · `Superseded by NNNN`. Supersede when the decision *changes*; **delete
  when its subject is gone** — five went with the native surfaces (`7a1c4e9f`) and there was nothing to
  supersede them *to*. A deletion is not done until its citations are swept with it: ADRs are cited from
  `contracts/` and from code, **not only from `docs/`** — checked by `scripts/validate_doc_citations.py`. The
  native drop was thorough in `docs/` but left 27 references to the deleted ADR 0009 behind in a spec and the
  web crypto layer — swept only later, in cr-0178, by repointing them to the surviving ADR 0017. The gap that
  let them linger (no validator watched whether a cited ADR still resolves) is **now closed** by that guard,
  scoped to the load-bearing surfaces (inbox #95).
- Keep them concise: context, the decision, consequences — not a tutorial.
- **An ADR records a decision that had a real alternative.** If it could not have gone another way, it is a
  description, not a decision — put it where it binds instead of filing one. Apply the test before adding a
  record, not in a review afterwards.
- Refinements that produce a durable decision create an ADR; change-requests link the ADRs they touch.
- **Current state, edited in place.** State the decision *as it is now*; when it changes, **edit the ADR** and
  let **git** hold the history — **no** `Amended: <date>` lines or `## Decision — addendum <date>` sections
  (a change-log belongs in git, not the doc). Keep the full current reasoning + rejected alternatives. Canonical
  rule: [`../README.md`](../README.md).

- **What is true now lives in the topic's current-picture doc**, named by `- Model:` and listed under
  [Topics](#topics); an ADR keeps the choice and its why. A decision that changes part of an earlier ADR edits
  that doc and gives the earlier ADR one `- Replaced in part:` line, never a mark in its body. Canonical rule:
  [`../README.md`](../README.md).

## Format

```
# ADR NNNN — Title
- Status: Accepted
- Built: partly (not built: § 3, decision 7)
- Date: YYYY-MM-DD
- Model: <a link to the topic's current-picture doc>
- Replaced in part: § 2, § 4 by ADR NNNN (not built)

## Context
What forces the decision.

## Decision
What we chose.

## Consequences
What follows — including the costs and the alternatives rejected.
```

The header lines, each one line:

- **`Status`** — `Proposed`, `Accepted` or `Superseded by NNNN`, and nothing else.
- **`Built`** — `yes`, `no` or `partly (not built: …)`, naming the parts still missing as the ADR numbers them. A
  decision about method or documentation is built once it is followed. It says *what* is missing, never how the
  rest was built — the code, its tests and `coverage/` hold that.
- **`Date`** — the day it was decided, alone.
- **`Model`** — the topic's current-picture doc; left out while the topic has none.
- **`Replaced in part`** — only when a later ADR replaced part of this one; one line naming every such ADR.

None of them carries a date beyond `Date`, a CR id or an owner's answer. An answer that still binds is part of
the decision: it goes into the body, as an obligation.

## Topics

Every ADR under the one topic it mainly decides; *Also bears on it* lists the records filed elsewhere that a reader
of the topic needs too. The current picture is the doc that holds what is true now — read it first, and the ADRs
for why. Numbers stay global and files stay flat: a topic is a row here, never a folder, because a record often
spans two topics and a move would break every path that links it. A new ADR joins its topic's row in the same
change.

| Topic | Current picture | ADRs | Also bears on it |
|---|---|---|---|
| **Account: sign-in, sessions, unlock, devices, recovery** | [en.account.key-model](../features/account/enablers/en.account.key-model.md) | [0003](0003-operator-blind-password-auth.md) · [0004](0004-session-tokens.md) · [0005](0005-unlock-methods-and-device-trust.md) · [0054](0054-password-recovery-two-factor-release.md) · [0058](0058-revoke-never-takes-key-material.md) · [0059](0059-device-linking.md) · [0070](0070-a-session-ends-with-a-new-document.md) |  |
| **Keys: content keys, grants, rotation, identity trust** | none yet — written with ADR 0111's build (it folds the rotation half of `en.account.key-model` and `en.spaces.scope-key-rotation` in) | [0012](0012-content-key-hierarchy.md) · [0020](0020-grant-distribution-invite-wrap.md) · [0024](0024-scope-key-rotation.md) · [0025](0025-forward-secrecy-policy-per-scope.md) · [0028](0028-identity-authenticity-tofu-verification.md) · [0060](0060-key-rotation-after-compromise.md) · [0072](0072-a-seal-binds-its-object.md) · [0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) · [0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) · [0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) | [0005](0005-unlock-methods-and-device-trust.md) · [0027](0027-group-invite-name-shared-key.md) · [0029](0029-public-viewer-share.md) · [0054](0054-password-recovery-two-factor-release.md) · [0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) |
| **Security boundary: operator-blindness, client trust, crypto primitives** | [security-model](../crosscutting/security-model.md) | [0017](0017-crypto-primitives-and-post-quantum-strategy.md) · [0026](0026-client-trust-tiers.md) · [0044](0044-field-visibility-declared-at-the-field.md) · [0053](0053-wire-assertion-canary-sweep.md) | [0092](0092-logs-are-operational-and-name-no-person.md) |
| **Scopes, membership, tenancy, governance** | [scopes-and-tenancy](../crosscutting/scopes-and-tenancy.md) | [0002](0002-personal-scope-per-tenant.md) · [0006](0006-cold-tenant-scope-birth.md) · [0007](0007-membership-role-grant-relation.md) · [0014](0014-group-scopes-and-governance.md) · [0019](0019-token-less-tenant-home.md) · [0023](0023-per-tenant-member-identity.md) · [0027](0027-group-invite-name-shared-key.md) · [0066](0066-tenant-suspension.md) · [0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md) | [0008](0008-centralized-http-auth.md) · [0063](0063-database-driven-tenant-ingress.md) · [0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) · [0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) |
| **Sharing: public links and guests** | none yet — wave 2, after ADR 0111's build (an `en.sharing.*` enabler) | [0029](0029-public-viewer-share.md) · [0030](0030-contributor-public-share.md) · [0031](0031-uploader-public-share.md) · [0034](0034-media-uploader-buckets.md) · [0036](0036-editable-share-note-recoverable-share-key.md) · [0037](0037-password-protected-shares.md) · [0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) · [0076](0076-a-guest-write-proves-it-holds-the-links-key.md) | [0012](0012-content-key-hierarchy.md) · [0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) · [0109](0109-a-scope-rotation-re-keys-every-node-below-it.md) · [0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md) |
| **Nodes: tree, metadata, URLs, deletion, export** | [en.memory.tree](../features/memory/enablers/en.memory.tree.md) | [0015](0015-operator-blind-readable-urls.md) · [0061](0061-node-deletion-reversible-node-trash.md) · [0077](0077-a-write-carries-the-version-it-read.md) · [0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) · [0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) · [0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md) | [0019](0019-token-less-tenant-home.md) · [0033](0033-live-media-updates-revision-polling.md) |
| **Media: blobs, transport, live lists** | none yet — wave 2 (`en.memory.media` holds the upload transport only) | [0013](0013-blob-storage-presigned-direct-to-store.md) · [0033](0033-live-media-updates-revision-polling.md) · [0047](0047-inline-video-playback-range-streaming.md) | [0034](0034-media-uploader-buckets.md) · [0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) · [0105](0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md) |
| **Platform: core, applications, capabilities, words** | [Applications](../apps/README.md) | [0016](0016-geocoding-provider-neutrality.md) · [0018](0018-map-tiles-provider-neutrality.md) · [0032](0032-capability-discovery-version-gating.md) · [0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) · [0086](0086-one-word-per-meaning.md) · [0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) | [0013](0013-blob-storage-presigned-direct-to-store.md) · [0068](0068-favorites-two-confidentiality-levels.md) · [0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) |
| **Memories** | [Application: Memories](../apps/memories.md) | [0068](0068-favorites-two-confidentiality-levels.md) · [0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) · [0102](0102-a-memorys-banner-is-its-own-attachment-framed-by-a-focal-point.md) | [0015](0015-operator-blind-readable-urls.md) · [0061](0061-node-deletion-reversible-node-trash.md) |
| **Expenses** | [Application: Expenses](../apps/expenses.md) | [0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) |  |
| **Moderation and legal duties** | none yet — wave 2 (the mechanism is in the four `en.moderation.*` enablers) | [0035](0035-server-terms-of-service.md) · [0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) · [0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) | [0092](0092-logs-are-operational-and-name-no-person.md) · [0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md) · [0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) · [0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) |
| **Privacy: retention, erasure, logs** | none yet — wave 2 (a retention register) | [0092](0092-logs-are-operational-and-name-no-person.md) · [0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md) · [0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) | [0035](0035-server-terms-of-service.md) · [0056](0056-instance-durable-state-and-cold-logical-backup.md) · [0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) |
| **Mail** | none yet — wave 2 | [0048](0048-provider-neutral-smtp-mail-transport.md) · [0049](0049-email-delivery-boundary.md) · [0055](0055-mail-is-a-precondition-not-a-capability.md) · [0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md) | [0050](0050-operator-config-secret-encryption.md) · [0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) · [0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) · [0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) |
| **Object store, quota, backup** | none yet — wave 2 | [0056](0056-instance-durable-state-and-cold-logical-backup.md) · [0062](0062-store-object-reclaim-is-a-durable-work-list.md) · [0067](0067-store-provisioning-from-a-declared-type.md) · [0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) · [0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) | [0013](0013-blob-storage-presigned-direct-to-store.md) · [0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) · [0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) |
| **Rate limits** | [cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md) | [0051](0051-declarative-rate-limit-posture.md) · [0052](0052-token-bucket-rate-limit-core.md) | [0094](0094-a-clients-address-is-the-hop-the-network-vouches-for.md) |
| **Background work** | [background-tasks](../crosscutting/background-tasks.md) | [0021](0021-operator-blind-background-tasks.md) | [0062](0062-store-object-reclaim-is-a-durable-work-list.md) |
| **Release, deployment, administration** | none yet — wave 2 (the operator's view is the site's Run area) | [0050](0050-operator-config-secret-encryption.md) · [0057](0057-release-identity-and-deployment-recipe.md) · [0063](0063-database-driven-tenant-ingress.md) · [0064](0064-kubernetes-helm-deployment-target.md) · [0065](0065-admin-ui-cli-separate-service.md) · [0069](0069-backend-serves-the-web-app-shell.md) · [0071](0071-runtime-hardening-on-both-targets.md) · [0085](0085-one-major-moved-on-a-yearly-train.md) · [0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md) · [0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) · [0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md) · [0106](0106-deployment-under-infra-tests-under-tests.md) | [0056](0056-instance-durable-state-and-cold-logical-backup.md) · [0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) · [0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) |
| **Testing and CI** | none yet in `docs/` — wave 2 (the stage map is the site's *How veyq is tested*) | [0040](0040-coverage-gaps-and-http-not-a-surface.md) · [0045](0045-mutation-testing-trust-chain-unit-layer.md) · [0090](0090-end-to-end-tests-boot-the-release-candidate.md) · [0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) · [0100](0100-pentests-attack-a-reset-candidate-locally-and-publish-every-runs-record.md) | [0053](0053-wire-assertion-canary-sweep.md) · [0078](0078-production-reads-committed-like-its-tests.md) |
| **Backend structure and HTTP** | [developer-styleguide](../crosscutting/developer-styleguide.md) | [0001](0001-backend-http-adapter-vs-application-slicing.md) · [0008](0008-centralized-http-auth.md) · [0038](0038-contracts-shared-components-bundling.md) · [0042](0042-http-failure-projection.md) · [0078](0078-production-reads-committed-like-its-tests.md) · [0103](0103-the-application-compresses-its-answers-except-the-ones-that-carry-a-token.md) |  |
| **Web UI** | [ui-web-styleguide](../crosscutting/ui-web-styleguide.md) | [0022](0022-settings-information-architecture.md) · [0039](0039-transient-feedback-via-toast.md) · [0105](0105-a-gallery-renders-what-is-near-the-viewport-and-opens-its-listing-in-a-worker.md) · [0107](0107-the-clients-own-their-ui-components-reka-ui-underneath-primevue-out.md) · [0110](0110-a-node-header-shows-its-actions-by-priority-as-many-as-fit.md) | [0069](0069-backend-serves-the-web-app-shell.md) |
| **Working method and documentation** | none — each rule lives where it binds: [`../ai/README.md`](../ai/README.md), [`../README.md`](../README.md), [`CONTRIBUTING.md`](../../CONTRIBUTING.md) | [0046](0046-operative-lore-in-skills-and-agents.md) · [0087](0087-published-docs-are-a-starlight-site-docs-is-for-development.md) · [0095](0095-contributions-come-in-under-the-agpl-with-a-dco-sign-off.md) | [0086](0086-one-word-per-meaning.md) |
