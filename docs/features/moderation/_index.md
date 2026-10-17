# Feature: Moderation

Acting on what is reported, as a host: who moderates, the key a report is sealed to, the queue and every decision, and
the log that keeps them accountable. A **core** feature
([ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ Decision: node, share, report and hold are platform terms, and every application needs them).

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share-boundary Trust & Safety: the server
  stores the roster, the published key and sealed grants, never a moderation private key).
- **Decisions:** [ADR 0099](../../decisions/0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  (moderators the Server Owner appoints share one instance moderation key, in epochs; review in web/'s moderation
  surface without a vault), [ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  (reports, holds, measures, the moderation log), [ADR 0097](../../decisions/0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md)
  decisions 3, 5 and 6 (instance mail to moderators, their address confirmed by a code).
- **Actors:** the **Server Owner** (appoints, removes, mints the key, in admin-web or `veyq-admin`), a **moderator**
  (an existing account the Server Owner appointed, in web/'s moderation surface), a **reporter** (a member or a guest of
  a link, in the share they reported from).

## Use-cases

- ★ [Appoint moderators and keep the moderation key](use-cases/uc.moderation.appoint-moderators.md) — **planned**
  (ADR 0099 §§ 1, 3–5): the Server Owner turns moderation on, appoints existing accounts by address, removes them and
  rotates the key.
- ★ [Serve as a moderator](use-cases/uc.moderation.serve-as-moderator.md) — **planned** (ADR 0099 §§ 1, 3, 6, 7): an
  appointee accepts, confirms their address, receives the key from a holder, delivers it to the next newcomer, and can
  step down.
- Report a share, an item or an uploader — planned (ADR 0093 §§ 1–2, 4).
- Review a report and decide — planned (ADR 0093 §§ 3, 6–7, 11–13).

## Enablers

- [en.moderation.moderation-key](enablers/en.moderation.moderation-key.md) — **planned** (ADR 0099 § 2: per epoch a
  32-byte `M_n`, its public bundle, a grant per moderator, and the key proof every moderation call carries).
- [en.moderation.log](enablers/en.moderation.log.md) — **planned** (ADR 0093 § 11: an append-only table, structured
  fields in the clear and free text sealed to the moderation key; the instance events and their retention).
