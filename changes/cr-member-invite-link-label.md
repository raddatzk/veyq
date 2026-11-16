---
id: cr-member-invite-link-label
title: A member invite link carries a private label, sealed as a tenant value bound to its invite
status: planned
satisfies: ["uc.spaces.manage-member-invites", "uc.spaces.add-member"]
adrs: ["0129", "0115", "0093"]
invariants:
  - "the label reaches the server only as ciphertext: a value key of its own, wrapped under the tenant scope's current key (ADR 0129 decision 7, ADR 0115)"
  - "a label opens only for the invite it was sealed for: its value address binds the tenant and the invite id, so a label moved onto another invite does not open"
  - "the sealed label is served only in the owner-gated pending list, never in listOpenInvites, and a rotation's rekey parts carry its wrap, never the sealed label"
  - "an invite that ends (consumed, superseded, revoked, expired-purged) holds no label, nulled in the statement that nulls its key and grant"
---

## Goal

A tenant owner gives a **link** member invite an optional label ("Oma", "Kletterkurs") at mint, sees it as the row's
title in the pending list, and changes or clears it later; a re-issue keeps it. Today the pending list tells link
invites apart only by expiry ([ADR 0129](../docs/decisions/0129-a-member-invite-is-mailed-or-handed-over-as-a-link.md)
decision 2), so with several open links the owner loses track of whom each was for (inbox #860, owner 2026-11-15). The
key is the tenant scope's (owner 2026-11-15): every co-owner managing the list sees it.

## Context & constraints

- ADR 0129 decision 7 (the decision), [uc.spaces.manage-member-invites](../docs/features/spaces/use-cases/uc.spaces.manage-member-invites.md)
  step 4 and E2, [uc.spaces.add-member](../docs/features/spaces/use-cases/uc.spaces.add-member.md) A5.
- The precedent is a share's private label (`sealedShareName`, cr-0116): optional, base64, `maxLength: 512`, the row's
  title with a fallback. It seals purpose-only under `K_share`; this one cannot, because the tenant key is shared by
  every invite — hence the ADR 0115 value with an address bound to the invite.
- The tenant-value pattern is `veyq_tenant_profile`'s (sealed column + `wrapped_*_value_key`), sealed with
  `sealValue`/`openValue` in `src/web/src/features/spaces/valueKey.ts` and re-wrapped at a rotation by
  `src/web/src/features/spaces/scopeRekey.ts` (`rewrapMember`) and `node/rekey/ScopeRekeyCommit.kt`.
- Re-issue already takes `MemberInviteLinkRequest` and mints a new row with a new client-minted `inviteId`, so the
  label fields on that request serve mint and re-issue alike.
- A mailed invite carries no label (it is named by its address); its operations do not change.

## Decisions (technical, settled here)

1. **Purposes.** Field purpose and value purpose `member-invite-label`; value address `{ scopeId: tenantId, inviteId }`.
   Registered in `src/packages/crypto/src/seal.ts` (`FIELD_PURPOSES`, `VALUE_KEY_PURPOSES`) with a `field_seal` and a
   `value_key.addresses` vector in `contracts/crypto/vectors/v1.json`.
2. **Wire.** `MemberInviteLinkRequest` gains `sealedLabel` and `wrappedLabelValueKey`, both optional, both or neither
   (a `dependentSchemas` rule as `InviteeNameKey`'s, and a `400 invalid_request` in the handler). `PendingMemberInvite`
   gains both, nullable, for link invites only. A new owner-gated `setMemberInviteLabel`
   (`PUT /api/tenants/{tenantId}/member-invites/{inviteId}/label`, both fields `null` clears) answers `204`; a mailed
   or ended invite is `404 invite_not_found`, a non-owner `403 not_tenant_owner`. Opaque base64, no
   `x-veyq-visibility` (`validate_field_visibility.py`); `maxLength: 512` for the label, the value-key wrap sized as
   `veyq_tenant_profile`'s.
3. **Storage.** One migration adds `sealed_label TEXT NULL`, `wrapped_label_value_key VARCHAR(128) NULL` and
   `label_editor_user_id` to `veyq_tenant_invite` (ADR 0093 § 8: the editor is the authenticated caller; clearing
   clears it). Every statement that nulls an invite's key and grant nulls all three.
4. **Rotation.** `ScopeRekeyParts` gains the open link invites' label wraps (`inviteId`, `wrappedLabelValueKey`, only
   where one is set); `scopeRekey.ts` re-wraps them under the new key, `ScopeRekeyCommit` writes them in the commit.
   `validate_scope_rekey_arity.py` must classify the new purpose and see the wrap column assigned there.
5. **Re-issue.** The client opens the old label and seals it anew for the new `inviteId`, without asking; a label that
   does not open (E2) is dropped, never re-sealed from what was shown.
6. **UI.** Link mode of `InviteMemberDialog.vue` gets one optional field; `TenantMembersPanel.vue`'s row title is the
   label, else **Link invitation**; a row action **Rename** edits it. Copy, to confirm with the owner at the browser
   pass: EN "Label (optional)" / "Only the owners of this space see it", DE "Bezeichnung (optional)" / "Nur die
   Inhaber dieses Bereichs sehen sie".

## Inventory (locators — run, read as lists)

- Contract: `grep -n "MemberInviteLinkRequest:\|PendingMemberInvite:\|reissueMemberInviteLink\|listMemberInvites" contracts/api/tenants.openapi.yaml`;
  `grep -n "ScopeRekeyParts:" contracts/api/content.openapi.yaml`.
- Backend: `grep -n "inviteMemberByLink\|reissueLinkInvite\|listMemberInvites\|revokeMemberInvite" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/*.kt`;
  every statement nulling an invite's key: `grep -n "wrapped_grant\s*=\s*NULL\|wrappedGrant = null" -r src/backend/core/src/main`.
- Web: `src/web/src/features/spaces/{memberInvite.ts,members.ts,InviteMemberDialog.vue,TenantMembersPanel.vue,scopeRekey.ts}`.
- Guards: `scripts/validate_scope_rekey_arity.py`, `scripts/validate_field_visibility.py`; e2e canary
  `tests/e2e/tests/features/account/field-seal.spec.ts`.
- Published docs: `site/src/content/docs/space-owners/who-gets-into-your-space.mdx` and its `de/` twin;
  `site/src/content/docs/security/what-the-server-sees.md`.

## Phases

1. **Contracts + crypto.** The schema changes and the new operation; both purposes and their vectors.
   Done when: `pnpm run generate:api` and the contracts guards are green, one use of each new model compiles in Kotlin
   and TypeScript, and `src/packages/crypto` `pnpm run check` is green (the conformance tests read the new vectors).
   The web is expected red at `memberInvite.ts`'s request literal only if a field turns out required — it should not.
2. **Backend.** Migration, store, handler, resource, rekey parts and commit. Slice tests: a mint with a label lists it
   back to an owner and to a co-owner; a non-owner is `403`; both-or-neither is `400`; `setMemberInviteLabel` sets,
   clears (editor cleared) and is `404` for a mailed and for a revoked invite; revoke, supersede and redemption null
   the label; `listOpenInvites` carries no label field; a rotation commit re-wraps the label's value key.
   Done when: `./gradlew check` green, `validate_scope_rekey_arity.py` green with the new purpose.
3. **Web + docs.** Seal at mint, open in the list, rename, keep at re-issue, re-wrap at rotation; the dialog field and
   the row title; the e2e canary for the new purpose; both site pages and the security page.
   Unit tests: seal/open round trip; **the swap bite** — invite A's sealed label and wrap served as invite B's opens to
   nothing and the row shows **Link invitation** (red if the address omits `inviteId`); re-issue re-seals for the new
   id; a label that does not open is not re-sealed; the rotation re-wraps it.
   Done when: `pnpm run check` green in `src/web/`, the trust-chain green, and a browser pass: mint two links with
   labels, rename one, re-issue the other, both rows titled right after a reload.

## Coverage

`docs/coverage/features/spaces/use-cases/uc.spaces.manage-member-invites.yaml`: `list-pending-member-invites` names the
label; a new criterion for step 4 (set, change, clear, re-issue keeps it, E2). `uc.spaces.add-member.yaml`'s link
criterion names the optional label.

## Definition of done

The three phases' done-whens; ADR 0129's `Built:` back to `yes`; inbox #860 deleted; this CR deleted after its
lessons moved.
