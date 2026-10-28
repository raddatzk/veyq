<script setup lang="ts">
// uc.spaces.add-member (web, cr-0077) — the "Members" rubric of the tenant settings. Lists the active tenant's
// members (name/email + role) and, for the tenant OWNER, opens the invite-by-email dialog. Markup + classes mirror the
// create-tenant admin panel (settings-section-header + veyq-list-row + veyq-badge). Operator-blind: only operational
// metadata is shown — pubkeys/roles, never a key. Every listed member holds the tenant's key from its join on (ADR 0111:
// an invitee grants itself at accept). Inviting is owner-only (the backend 403s otherwise); the directory is any
// key-holder's.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import InviteMemberDialog from './InviteMemberDialog.vue'
import SpaceIconDialog from './SpaceIconDialog.vue'
import TenantAvatar from '../../shared/ui/TenantAvatar.vue'
import PersonAvatar from '../../core/people/PersonAvatar.vue'
import { syncAvatars } from '../../core/people/avatarCache'
import {
  listMemberInvites,
  listScopeMembers,
  reissueMemberInvite,
  removeTenantMember,
  revokeMemberInvite,
  setTenantMemberRole,
  SetTenantMemberRoleRequestRoleEnum,
  memberError,
  type PendingMemberInvite,
  type ScopeMember,
} from './members'
import { listMyTenants, setSpaceIcon, clearSpaceIcon, openMyTenantIcon, revokeScopeShares, scopeLifecycle } from './scopes'
import { formatInviteCode, generateInviteCode } from '@veyq/crypto/invite'
import { bumpSpaceIcon } from './spaceIconSignal'
import { activeSpace, activeScopeKey } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { unsealName, type MemberName } from '../account/profile/profile'
import { isUnauthorized, isCodedError, HttpStatus } from '../../shared/httpError'
import { useMemberDirectory } from './useMemberDirectory'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { useToast } from '../../shared/state/useToast'
import { useConfirm } from '../../shared/state/useConfirm'
import MemberTrustBadge from './MemberTrustBadge.vue'
import TrustResetLink from '../account/trustReset/TrustResetLink.vue'
import CompromiseRotationNotice from './CompromiseRotationNotice.vue'
import type { CompromiseRotation } from '../../api/generated/scopes'

const { t } = useI18n()
const toast = useToast()
const { ask, askWithOption } = useConfirm()

// Locally-decrypted real names (first+last) keyed by userId — the sealed blob opened with K_tenant on-device.
const names = ref<Record<string, MemberName | null>>({})
const pendingInvites = ref<PendingMemberInvite[]>([])
const actionError = ref('')
const inviteOpen = ref(false)

// The tenant identity header (uc.spaces.set-space-icon): the tenant's name + its avatar (the decrypted icon, or the
// initial), and — for the owner — set/replace/remove. The name is server-visible plaintext; the icon is sealed and
// opened on-device against the caller's own grant. Mirrors GroupMembersPanel's owner-gated `group-name-header`.
const tenantName = ref('')
/** Owner rows only: a compromise rotation queued on the tenant root that needs an owner
 *  (uc.spaces.complete-queued-rotation E2/E8), from the switcher's own row of this tenant. */
const stuckRotation = ref<CompromiseRotation | null>(null)
/** Owner-only: the tenant's last rotation is still fitting the new lock (ADR 0109). */
const rekeying = ref(false)
const tenantIcon = useObjectUrl() // an owned object URL for the decrypted icon (revoked on replace/unmount)
const iconDialogOpen = ref(false)
const iconBusy = ref(false)
const iconError = ref('')

// A re-issue mails the fresh secret to the invitee and returns none (ADR 0055 §2) — so there is no link to show
// and nothing for the owner to hand on. What stays is the confirmation of which address it went to.
const reissuedTo = ref('')
// The new invite's code, shown once beside the confirmation: the owner passes it on themselves (ADR 0111 decision 1).
const reissuedCode = ref('')

const inTenant = computed(() => activeSpace.kind === ScopeKind.Tenant && activeSpace.scopeId !== null)

// Shared directory core (members/trust/review/isOwner/load, cr-0156). The load body is Tenant-specific: fetch
// the roster, decrypt each real name with K_tenant (operator-blind, on-device), then read the owner-only invite
// list — and resolve ownership from the tenant role.
const { members, isOwner, trust, trustRefusal, loading, loadError, reload, on401 } =
  useMemberDirectory(async () => {
    if (!inTenant.value || !activeSpace.scopeId || !activeSpace.tenantId) throw new Error('no-active-tenant')
    const rows = await listScopeMembers(activeSpace.scopeId)
    // A member who set no name has no sealed name → null (the row shows only the @username).
    // Each name is bound to (this tenant scope, that member) — the scope we addressed and the row's member id — so a
    // server that moves one member's sealed name onto another's row shows no name there, not the wrong one.
    const kTenant = activeScopeKey()
    const scopeId = activeSpace.scopeId
    names.value = kTenant
      ? Object.fromEntries(rows.map((m) => [m.userId, unsealName(kTenant, { scopeId, userId: m.userId }, m.sealedName)]))
      : {}
    // The members' pictures follow the roster (ADR 0084): the version integers it carries decide what is fetched. Not awaited -
    // the rows draw their letters at once; the key copy is zeroed when the fetches settle.
    if (kTenant) {
      const copy = kTenant.slice()
      void syncAvatars({ tenantId: activeSpace.tenantId, scopeId, key: copy }, rows).finally(() => copy.fill(0))
    }
    const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
    const owner = mine?.role === 'OWNER'
    // The identity header: the tenant name (plaintext) + its icon opened on-device against our own grant (null when
    // unset / unopenable → the initial shows). Every member reads it, not just the owner.
    tenantName.value = mine?.name ?? ''
    stuckRotation.value = mine?.compromiseRotation ?? null
    rekeying.value = owner && activeSpace.scopeId ? ((await scopeLifecycle(activeSpace.scopeId).catch(() => null))?.rekeying ?? false) : false
    const iconBytes = mine ? await openMyTenantIcon(mine) : null
    if (iconBytes) tenantIcon.set(new Blob([iconBytes as BlobPart], { type: 'image/webp' }))
    else tenantIcon.clear()
    // Only the owner may read the pending-invite list (the backend 403s any non-owner).
    pendingInvites.value = owner ? await listMemberInvites(activeSpace.tenantId) : []
    return { rows, isOwner: owner }
  })

/** The member's decrypted "First Last", or '' when none is sealed / unreadable. */
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
    acceptIcon: 'trash',
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
  reissuedCode.value = ''
  try {
    // A re-issue is a new invite (ADR 0111): a new key, link and code — the old code opens nothing any more.
    const code = generateInviteCode()
    await reissueMemberInvite(activeSpace.tenantId!, invite.inviteId, invite.email, { mode: 'code', code })
    reissuedCode.value = formatInviteCode(code)
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

// Remove a member from the tenant (uc.spaces.remove-tenant-member, cr-0091) — owner-only, behind the confirmation
// dialog. The server runs the whole cascade; here we just fire it and refresh the directory (the ex-member drops
// out). Not offered on OWNER rows (owners are managed via co-ownership, not removed from the roster here). The
// dialog's opt-in (ADR 0075 point 5), unchecked by default, also turns off every link of the space in the same act.
async function remove(member: ScopeMember) {
  const answer = await askWithOption({
    header: t('members.removeConfirmTitle'),
    message: t('members.removeConfirm'),
    acceptLabel: t('members.remove'),
    acceptIcon: 'user-minus',
    option: { label: t('members.removeRevokeLinks'), hint: t('members.removeRevokeLinksHint') },
  })
  if (!answer) return
  actionError.value = ''
  reissuedTo.value = ''
  try {
    await removeTenantMember(activeSpace.tenantId!, member.userId, answer.option)
    toast.success(t('members.removed'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('members.actionFailed')
  }
}

// Make a member an owner, or an owner a member again (ADR 0101 decision 9) — owner-only, mirroring GroupMembersPanel's
// promote/demote one scope-kind up. A pure role change, no key moves; the last-owner guard is the server's (`409
// last_owner`), and the directory reloads to show the new role. Stepping back yourself takes this panel's owner
// actions away with the reload — what the role says.
const roleBusy = ref<string | null>(null)
async function changeRole(member: ScopeMember, role: SetTenantMemberRoleRequestRoleEnum) {
  if (!activeSpace.tenantId) return
  actionError.value = ''
  reissuedTo.value = ''
  roleBusy.value = member.userId
  try {
    await setTenantMemberRole(activeSpace.tenantId, member.userId, role)
    toast.success(role === SetTenantMemberRoleRequestRoleEnum.Owner ? t('members.promoted') : t('members.demoted'))
    await reload()
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    actionError.value = code === 'last_owner' ? t('members.lastOwner') : t('members.roleChangeFailed')
  } finally {
    roleBusy.value = null
  }
}

// Turn off every link of the space (ADR 0075 point 5) — owner-only, behind the confirmation dialog. Each link then
// comes back only as a new one its owner renews; the toast names how many went off.
const revokingLinks = ref(false)
async function revokeAllLinks() {
  const accepted = await ask({
    header: t('spaceLinks.revokeAll'),
    message: t('spaceLinks.revokeAllConfirm'),
    acceptLabel: t('spaceLinks.revokeAll'),
    acceptIcon: 'link',
  })
  if (!accepted || !activeSpace.scopeId) return
  actionError.value = ''
  revokingLinks.value = true
  try {
    const revoked = await revokeScopeShares(activeSpace.scopeId)
    toast.success(t('spaceLinks.revokedAll', { count: revoked }, revoked))
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('spaceLinks.revokeAllFailed')
  } finally {
    revokingLinks.value = false
  }
}

async function revoke(invite: PendingMemberInvite) {
  const accepted = await ask({
    header: t('members.revokeConfirmTitle'),
    message: t('members.revokeConfirm'),
    acceptLabel: t('members.revoke'),
    acceptIcon: 'trash',
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
          <VeyqButton
            :label="tenantIcon.url.value ? t('spaceIcon.change') : t('spaceIcon.add')"
            icon="image"
            appearance="text"
            size="small"
            :disabled="iconBusy"
            @click="iconDialogOpen = true"
          />
          <VeyqButton
            v-if="tenantIcon.url.value"
            :label="t('spaceIcon.remove')"
            icon="trash"
            appearance="text"
            variant="danger"
            size="small"
            :disabled="iconBusy"
            @click="removeIcon"
          />
        </div>
      </div>
      <VeyqMessage v-if="iconError" tone="error">{{ iconError }}</VeyqMessage>
      <CompromiseRotationNotice
        v-if="isOwner && activeSpace.scopeId"
        :scope-id="activeSpace.scopeId"
        :state="stuckRotation"
        @requeued="stuckRotation = $event"
      />
      <VeyqMessage v-if="isOwner && rekeying" tone="info">{{ t('rotation.statusHint.rekeying') }}</VeyqMessage>

      <div class="settings-section-header">
        <div>
          <h2>{{ t('members.title') }}</h2>
          <p>{{ t('members.sub') }}</p>
        </div>
        <VeyqButton
          v-if="isOwner"
          :label="t('members.invite')"
          icon="user-plus"
          size="small"
          @click="inviteOpen = true"
        />
      </div>

      <VeyqMessage v-if="actionError" tone="error">{{ actionError }}</VeyqMessage>
      <!-- This device refused its own pin store (ADR 0028 Decision 7): no wrap to a foreign key until it reads. -->
      <VeyqMessage v-if="trustRefusal" tone="warn">
        {{ t(trustRefusal) }}
        <TrustResetLink />
      </VeyqMessage>

      <!-- A re-issue has no link to surface: the fresh link went to the invitee (ADR 0055 §2). What the owner passes on
           is the new code, shown here once (ADR 0111 decision 1). Dismissible. -->
      <div v-if="reissuedTo" class="reissue-link">
        <div class="reissue-link-head">
          <p class="settings-muted">{{ t('members.inviteSentTo', { email: reissuedTo }) }}</p>
          <VeyqButton
            icon="times"
            variant="secondary"
            appearance="outlined"
            size="small"
            :aria-label="t('common.done')"
            :title="t('common.done')"
            @click="reissuedTo = ''"
          />
        </div>
        <p v-if="reissuedCode" class="settings-muted">{{ t('members.inviteCodeNote') }}</p>
        <code v-if="reissuedCode" class="invite-code" data-testid="reissued-invite-code">{{ reissuedCode }}</code>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('members.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="warn">
        {{ t('members.notAuthorized') }}
      </VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">
        {{ t('members.loadFailed') }}
      </VeyqMessage>
      <p v-else-if="members.length === 0" class="settings-muted">{{ t('members.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="member in members" :key="member.userId" class="veyq-list-row member-row">
            <PersonAvatar :name="fullName(member.userId) || member.username" :user-id="member.userId" class="member-avatar" />
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ fullName(member.userId) || '@' + member.username }}</strong>
              <span class="veyq-row-subtitle">
                <span v-if="fullName(member.userId)">@{{ member.username }} · </span>
                <span v-if="member.email">{{ member.email }} · </span>{{ member.role }}
              </span>
              <div class="veyq-row-meta">
                <span class="veyq-badge success">{{ t('members.statusActive') }}</span>
                <!-- Behind the tenant's epoch, declared pending by a rotation (ADR 0111 decision 9). -->
                <span v-if="member.pending" class="veyq-badge muted" data-testid="member-pending">{{ t('members.pending') }}</span>
                <!-- Identity trust (ADR 0028 Decision 2): "Verified" after a compare on the member's profile,
                     an anomaly when the served key does not follow from the pinned head. -->
                <MemberTrustBadge :badge="trust[member.userId]" />
              </div>
            </div>
            <div class="management-actions">
              <VeyqButton
                v-if="isOwner && member.role === 'MEMBER'"
                :label="t('members.promote')"
                icon="arrow-up"
                variant="secondary"
                appearance="outlined"
                size="small"
                :loading="roleBusy === member.userId"
                :data-promote="member.userId"
                @click="changeRole(member, SetTenantMemberRoleRequestRoleEnum.Owner)"
              />
              <VeyqButton
                v-else-if="isOwner && member.role === 'OWNER'"
                :label="t('members.demote')"
                icon="arrow-down"
                variant="secondary"
                appearance="outlined"
                size="small"
                :loading="roleBusy === member.userId"
                :data-demote="member.userId"
                @click="changeRole(member, SetTenantMemberRoleRequestRoleEnum.Member)"
              />
              <VeyqButton
                v-if="isOwner && member.role !== 'OWNER'"
                icon="user-minus"
                variant="danger"
                appearance="outlined"
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
              <VeyqButton
                icon="link"
                variant="secondary"
                appearance="outlined"
                size="small"
                :aria-label="t('members.reissue')"
                :title="t('members.reissue')"
                @click="reissue(invite)"
              />
              <VeyqButton
                icon="trash"
                variant="danger"
                appearance="outlined"
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

    <!-- Every public link of the space at once (ADR 0075 point 5) — owner-only: each comes back only by renewal. -->
    <section v-if="isOwner && !loading" class="settings-form space-links">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('spaceLinks.title') }}</h2>
          <p>{{ t('spaceLinks.sub') }}</p>
        </div>
        <VeyqButton
          :label="t('spaceLinks.revokeAll')"
          icon="link"
          variant="danger"
          appearance="outlined"
          size="small"
          :loading="revokingLinks"
          @click="revokeAllLinks"
        />
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

    <!-- Review a changed identity key (cr-0099); the proactive compare is on the member's profile (ADR 0028 Decision 3). -->
  </section>
</template>

<style scoped>
/* A member's row leads with their avatar (picture, else the letter): one more grid column in front of the shared three. */
.member-row {
  grid-template-columns: auto minmax(0, 1fr) auto;
}
@media (max-width: 820px) {
  .member-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .member-row .management-actions {
    grid-column: 1 / -1;
  }
}

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
