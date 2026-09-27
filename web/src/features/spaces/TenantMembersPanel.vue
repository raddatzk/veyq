<script setup lang="ts">
// uc.spaces.add-member (web, cr-0077) — the "Members" rubric of the tenant settings. Lists the active tenant's
// members (name/email + role + granted/awaiting-access status) and, for the tenant OWNER, opens the invite-by-
// email dialog. Markup + classes mirror the create-tenant admin panel (settings-section-header + veyq-list-row +
// veyq-badge). Operator-blind: only operational metadata is shown — pubkeys/roles/grant-present, never a key.
// Admission (invite) is owner-only (the backend 403s otherwise); the directory is any key-holder's.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import InviteMemberDialog from './InviteMemberDialog.vue'
import SpaceIconDialog from './SpaceIconDialog.vue'
import TenantAvatar from '../../components/app/TenantAvatar.vue'
import {
  listMemberInvites,
  listScopeMembers,
  reissueMemberInvite,
  removeTenantMember,
  revokeMemberInvite,
  type PendingMemberInvite,
  type ScopeMember,
} from './members'
import { listMyTenants, setSpaceIcon, clearSpaceIcon, openMyTenantIcon } from './scopes'
import { bumpSpaceIcon } from './spaceIconSignal'
import { activeSpace, activeScopeKey, activeWriteKey } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { unsealName, type MemberName } from '../account/profile/profile'
import { isUnauthorized, isCodedError, HttpStatus } from '../../shared/httpError'
import { useMemberDirectory } from './useMemberDirectory'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { useToast } from '../../shared/state/useToast'
import { useConfirm } from '../../shared/state/useConfirm'
import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'

const { t } = useI18n()
const toast = useToast()
const { ask } = useConfirm()

// Locally-decrypted real names (first+last) keyed by userId — the sealed blob opened with K_tenant on-device.
const names = ref<Record<string, MemberName | null>>({})
const pendingInvites = ref<PendingMemberInvite[]>([])
const actionError = ref('')
const inviteOpen = ref(false)

// The tenant identity header (uc.spaces.set-space-icon): the tenant's name + its avatar (the decrypted icon, or the
// initial), and — for the owner — set/replace/remove. The name is server-visible plaintext; the icon is sealed and
// opened on-device against the caller's own grant. Mirrors GroupMembersPanel's owner-gated `group-name-header`.
const tenantName = ref('')
const tenantIcon = useObjectUrl() // an owned object URL for the decrypted icon (revoked on replace/unmount)
const iconDialogOpen = ref(false)
const iconBusy = ref(false)
const iconError = ref('')

// A re-issue mails the fresh secret to the invitee and returns none (ADR 0055 §2) — so there is no link to show
// and nothing for the owner to hand on. What stays is the confirmation of which address it went to.
const reissuedTo = ref('')

const inTenant = computed(() => activeSpace.kind === ScopeKind.Tenant && activeSpace.scopeId !== null)

// Shared directory core (members/trust/review/isOwner/load, cr-0156). The load body is Tenant-specific: fetch
// the roster, decrypt each real name with K_tenant (operator-blind, on-device), then read the owner-only invite
// list — and resolve ownership from the tenant role.
const { members, isOwner, trust, unadmitted, reviewTarget, reviewOpen, loading, loadError, refreshTrust, openReview, reload, on401 } =
  useMemberDirectory(async () => {
    if (!inTenant.value || !activeSpace.scopeId || !activeSpace.tenantId) throw new Error('no-active-tenant')
    const rows = await listScopeMembers(activeSpace.scopeId)
    // A grant-less "awaiting access" member has no sealed name yet → null (the row shows only the @username).
    // Each name is bound to (this tenant scope, that member) — the scope we addressed and the row's member id — so a
    // server that moves one member's sealed name onto another's row shows no name there, not the wrong one.
    const kTenant = activeScopeKey()
    const scopeId = activeSpace.scopeId
    names.value = kTenant
      ? Object.fromEntries(rows.map((m) => [m.userId, unsealName(kTenant, { scopeId, userId: m.userId }, m.sealedName)]))
      : {}
    const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
    const owner = mine?.role === 'OWNER'
    // The identity header: the tenant name (plaintext) + its icon opened on-device against our own grant (null when
    // unset / grant-less / unopenable → the initial shows). Every member reads it, not just the owner.
    tenantName.value = mine?.name ?? ''
    const iconBytes = mine ? await openMyTenantIcon(mine) : null
    if (iconBytes) tenantIcon.set(new Blob([iconBytes as BlobPart], { type: 'image/webp' }))
    else tenantIcon.clear()
    // Only the owner may read the pending-invite list (the backend 403s any non-owner).
    pendingInvites.value = owner ? await listMemberInvites(activeSpace.tenantId) : []
    return { rows, isOwner: owner }
  }, async () => {
    // The active tenant's write key — the one its admissions are tagged under. Owned by the active space: no release.
    const held = activeWriteKey()
    return held && activeSpace.scopeId
      ? { scopeId: activeSpace.scopeId, kind: ScopeKind.Tenant, key: held.key, epoch: held.epoch }
      : null
  })

/** The member's decrypted "First Last", or '' when not yet sealed (awaiting access) / unreadable. */
function fullName(userId: string): string {
  const n = names.value[userId]
  return n ? `${n.firstName} ${n.lastName}`.trim() : ''
}

async function onInvited() {
  toast.success(t('members.inviteSent'))
  await reload()
}

// Set/replace the tenant icon: the dialog baked + encoded the bytes; here we seal + POST (retry-once-on-stale is in
// setSpaceIcon), then refresh the header and signal the always-mounted sidebar to re-decrypt (bumpSpaceIcon).
async function onIconSave(bytes: Uint8Array) {
  if (!activeSpace.scopeId) return
  iconBusy.value = true
  iconError.value = ''
  try {
    await setSpaceIcon(activeSpace.scopeId, bytes)
    iconDialogOpen.value = false
    bumpSpaceIcon()
    await reload()
    toast.success(t('spaceIcon.saved'))
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) iconError.value = (await isCodedError(e, HttpStatus.conflict, 'stale_epoch')) ? t('spaceIcon.stale') : t('spaceIcon.failed')
  } finally {
    iconBusy.value = false
  }
}

async function removeIcon() {
  if (!activeSpace.scopeId) return
  const accepted = await ask({
    header: t('spaceIcon.removeConfirmTitle'),
    message: t('spaceIcon.removeConfirm'),
    acceptLabel: t('spaceIcon.remove'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  iconBusy.value = true
  iconError.value = ''
  try {
    await clearSpaceIcon(activeSpace.scopeId)
    bumpSpaceIcon()
    await reload()
    toast.success(t('spaceIcon.removed'))
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) iconError.value = t('spaceIcon.failed')
  } finally {
    iconBusy.value = false
  }
}

async function reissue(invite: PendingMemberInvite) {
  actionError.value = ''
  reissuedTo.value = ''
  try {
    await reissueMemberInvite(activeSpace.tenantId!, invite.inviteId)
    reissuedTo.value = invite.email
    toast.success(t('members.inviteSentTo', { email: invite.email }))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) {
      actionError.value = (await isCodedError(e, HttpStatus.tooManyRequests, 'rate_limited'))
        ? t('members.inviteRateLimited')
        : (await isCodedError(e, HttpStatus.serviceUnavailable, 'mail_unavailable'))
            ? t('members.mailUnavailable')
            : (await isCodedError(e, HttpStatus.serviceUnavailable, 'mail_send_failed'))
                ? t('members.mailSendFailed')
                : t('members.actionFailed')
    }
  }
}

// Remove a member from the tenant (uc.spaces.remove-tenant-member, cr-0091) — owner-only, inline confirm. The
// server runs the whole cascade; here we just fire it and refresh the directory (the ex-member drops out). Not
// offered on OWNER rows (owners are managed via co-ownership, not removed from the roster here).
async function remove(member: ScopeMember) {
  const accepted = await ask({
    header: t('members.removeConfirmTitle'),
    message: t('members.removeConfirm'),
    acceptLabel: t('members.remove'),
    acceptIcon: 'pi pi-user-minus',
  })
  if (!accepted) return
  actionError.value = ''
  reissuedTo.value = ''
  try {
    await removeTenantMember(activeSpace.tenantId!, member.userId)
    toast.success(t('members.removed'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
  }
}

async function revoke(invite: PendingMemberInvite) {
  const accepted = await ask({
    header: t('members.revokeConfirmTitle'),
    message: t('members.revokeConfirm'),
    acceptLabel: t('members.revoke'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  actionError.value = ''
  reissuedTo.value = ''
  try {
    await revokeMemberInvite(activeSpace.tenantId!, invite.inviteId)
    toast.success(t('members.inviteRevoked'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <!-- The tenant identity (uc.spaces.set-space-icon): its avatar + name; owner-only set/replace/remove icon.
           Mirrors GroupMembersPanel's owner-gated `group-name-header`, one scope-kind up (a tenant has no rename —
           its name is server-visible metadata, so only the icon is editable here). -->
      <div class="tenant-identity-header">
        <TenantAvatar :name="tenantName" :icon="tenantIcon.url.value ?? undefined" />
        <h1 class="tenant-identity-name">{{ tenantName }}</h1>
        <div v-if="isOwner" class="tenant-identity-actions">
          <Button
            :label="tenantIcon.url.value ? t('spaceIcon.change') : t('spaceIcon.add')"
            icon="pi pi-image"
            text
            size="small"
            :disabled="iconBusy"
            @click="iconDialogOpen = true"
          />
          <Button
            v-if="tenantIcon.url.value"
            :label="t('spaceIcon.remove')"
            icon="pi pi-trash"
            text
            severity="danger"
            size="small"
            :disabled="iconBusy"
            @click="removeIcon"
          />
        </div>
      </div>
      <Message v-if="iconError" severity="error" :closable="false">{{ iconError }}</Message>

      <div class="settings-section-header">
        <div>
          <h2>{{ t('members.title') }}</h2>
          <p>{{ t('members.sub') }}</p>
        </div>
        <Button
          v-if="isOwner"
          :label="t('members.invite')"
          icon="pi pi-user-plus"
          size="small"
          @click="inviteOpen = true"
        />
      </div>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <!-- A re-issue has no link to surface any more: the fresh secret went to the invitee (ADR 0055 §2). This
           is the confirmation, dismissible, not a handoff. -->
      <div v-if="reissuedTo" class="reissue-link">
        <div class="reissue-link-head">
          <p class="settings-muted">{{ t('members.inviteSentTo', { email: reissuedTo }) }}</p>
          <Button
            icon="pi pi-times"
            severity="secondary"
            outlined
            size="small"
            :aria-label="t('common.done')"
            :title="t('common.done')"
            @click="reissuedTo = ''"
          />
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('members.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">
        {{ t('members.notAuthorized') }}
      </Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">
        {{ t('members.loadFailed') }}
      </Message>
      <p v-else-if="members.length === 0" class="settings-muted">{{ t('members.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="member in members" :key="member.userId" class="veyq-list-row member-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ fullName(member.userId) || '@' + member.username }}</strong>
              <span class="veyq-row-subtitle">
                <span v-if="fullName(member.userId)">@{{ member.username }} · </span>
                <span v-if="member.email">{{ member.email }} · </span>{{ member.role }}
              </span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="member.hasGrant ? 'success' : 'warning'">
                  {{ member.hasGrant ? t('members.statusActive') : t('members.statusAwaiting') }}
                </span>
                <!-- Changed-identity warning (cr-0099): the ONLY trust surface — the directory key differs from the
                     pin (a rotation or an operator substitution). Verified/recognized are intentionally not shown. -->
                <span v-if="trust[member.userId] === 'changed'" class="veyq-badge changed">
                  <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ t('verify.badgeChanged') }}
                </span>
                <!-- Admission not verified (ADR 0020 move 6): no key-holder's worker delivers to this member. -->
                <span v-if="unadmitted[member.userId]" class="veyq-badge changed">
                  <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ t('members.badgeNotAdmitted') }}
                </span>
              </div>
            </div>
            <div class="management-actions">
              <!-- Review a changed key (cr-0099), available to ANY member: compare expected-vs-current out-of-band,
                   then accept (re-pin) or refuse (leave blocked). -->
              <Button
                v-if="trust[member.userId] === 'changed'"
                :label="t('verify.review')"
                icon="pi pi-exclamation-triangle"
                severity="warn"
                text
                size="small"
                @click="openReview(member)"
              />
              <Button
                v-if="isOwner && member.role !== 'OWNER'"
                icon="pi pi-user-minus"
                severity="danger"
                outlined
                size="small"
                :aria-label="t('members.remove')"
                :title="t('members.remove')"
                @click="remove(member)"
              />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Pending member invites (uc.spaces.manage-member-invites, cr-0082) — owner-only: invited-but-not-joined,
         each re-issuable (fresh copyable link) or revocable. Hidden entirely for a non-owner and when empty. -->
    <section v-if="isOwner && !loading && pendingInvites.length > 0" class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('members.pendingTitle') }}</h2>
          <p>{{ t('members.pendingSub') }}</p>
        </div>
      </div>

      <div class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="invite in pendingInvites" :key="invite.inviteId" class="veyq-list-row invite-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ invite.email }}</strong>
              <div class="veyq-row-meta">
                <span class="veyq-badge warning">{{ t('members.statusInvited') }}</span>
              </div>
            </div>
            <div class="management-actions">
              <Button
                icon="pi pi-link"
                severity="secondary"
                outlined
                size="small"
                :aria-label="t('members.reissue')"
                :title="t('members.reissue')"
                @click="reissue(invite)"
              />
              <Button
                icon="pi pi-trash"
                severity="danger"
                outlined
                size="small"
                :aria-label="t('members.revoke')"
                :title="t('members.revoke')"
                @click="revoke(invite)"
              />
            </div>
          </div>
        </div>
      </div>
    </section>

    <InviteMemberDialog
      v-if="activeSpace.tenantId"
      v-model:visible="inviteOpen"
      :tenant-id="activeSpace.tenantId"
      @invited="onInvited"
    />

    <!-- The owner's icon editor (uc.spaces.set-space-icon): pick + frame 1:1; on save the crop is baked + encoded
         on-device and this panel seals + POSTs it. -->
    <SpaceIconDialog v-if="iconDialogOpen" :busy="iconBusy" @save="onIconSave" @close="iconDialogOpen = false" />

    <!-- Review a changed identity key (cr-0099) — the only verify surface until scope-wide strict-mode (cr-0100). -->
    <ChangedKeyReviewDialog v-if="reviewTarget" v-model:visible="reviewOpen" :member="reviewTarget" @accepted="refreshTrust" />
  </section>
</template>

<style scoped>

.reissue-link {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.reissue-link-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.reissue-link-head p {
  margin: 0;
  flex: 1;
}

/* The tenant identity header — the avatar + name on one row, owner actions trailing (mirrors group-name-header).
   A bottom rule (the --app-line divider the settings sections use between stacked blocks) sets it apart from the
   Members section below, rather than letting the two crowd together. */
.tenant-identity-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding-bottom: 1.2rem;
  margin-bottom: 1.2rem;
  border-bottom: 1px solid var(--app-line);
}
.tenant-identity-name {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}
.tenant-identity-actions {
  display: flex;
  gap: 0.25rem;
  margin-left: auto;
}

</style>
