<script setup lang="ts">
// uc.spaces.add-group-member (web, cr-0086) — the "Members" rubric of the GROUP settings (re-homed into the
// settings hub, ADR 0022). Lists the group's members (username + role + active/pending consent) and, for the
// group OWNER, adds an existing tenant member by username (inline). Markup mirrors TenantMembersPanel. Operator-
// blind: only operational metadata (usernames/roles) is shown; the add wraps the group's current key to the target's
// pinned identity key on this device (ADR 0111 decision 4, `addGroupMember`), so the member holds it from the add on.
// Real names stay `@username`
// — they're sealed under K_tenant, which a group owner needn't hold (the active key here is the group's K_SC).
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqCheckbox from '../../shared/ui/VeyqCheckbox.vue'
import { listScopeMembers, addGroupMember, resolveGroupMember, setMemberRole, removeGroupMember, memberError, SetMemberRoleRequestRoleEnum, type ScopeMember } from './members'
import { rotateGroupForRemoval, deleteGroup, restoreGroup, scopeLifecycle, wrapGroupNameKeyForInvitee, renameGroup, setSpaceIcon, clearSpaceIcon, openGroupIcon, revokeScopeShares } from './scopes'
import { IdentityChangedError } from '../account/identityPins'
import { acceptChangedKey } from './acceptChangedKey'
import { TrustStateRefused, refusalMessageKey } from '../account/trustMarks'
import TrustResetLink from '../account/trustReset/TrustResetLink.vue'
import { activeSpace, refreshActiveScope } from '../../app/activeSpace'
import { getMyProfile } from '../account/profile/profile'
import { isUnauthorized, isCodedError, HttpStatus } from '../../shared/httpError'
import { useMemberDirectory } from './useMemberDirectory'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { bumpSpaceIcon } from './spaceIconSignal'
import { useToast } from '../../shared/state/useToast'
import { useConfirm } from '../../shared/state/useConfirm'
import SpaceIconDialog from './SpaceIconDialog.vue'
import TenantAvatar from '../../shared/ui/TenantAvatar.vue'
import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'
import MemberTrustBadge from './MemberTrustBadge.vue'
import CompromiseRotationNotice from './CompromiseRotationNotice.vue'
import type { CompromiseRotation } from '../../api/generated/scopes'

// Addressed by scope id (cr-0087) — the settings hub renders one panel per group the caller owns, not the
// active scope; switching between groups in the rail re-loads via the `scopeId` watch. `name` is the group's
// decrypted display name (the hub already opened it for the nav label); it flows back updated after a rename.
const props = defineProps<{ scopeId: string; name?: string }>()

const { t, locale } = useI18n()
const toast = useToast()
const { ask } = useConfirm()
const emit = defineEmits<{ lifecycleChanged: [] }>()

const myUsername = ref('')
const username = ref('')
const addError = ref('')
const adding = ref(false)
// A pending add blocked because the invitee's identity key CHANGED since we last pinned it (cr-0098, ADR 0028) —
// the human accepts (re-pin) then the add retries. Never silently sealed to the new key.
const pendingKeyChange = ref<{ userId: string; key: string; username: string } | null>(null)
const actionError = ref('')
const pendingRemove = ref<string | null>(null) // userId shown in inline "remove?" confirm (destructive → rotates)
const removing = ref(false)
// ADR 0075: the removal confirmation's opt-in — also turn off every link of the group. Unchecked by default, reset
// every time a confirmation opens.
const revokeLinks = ref(false)
function askRemove(userId: string) {
  revokeLinks.value = false
  pendingRemove.value = userId
}
// Group rename (cr-0097) — owner-only inline edit of the group's own name; re-seals under the current epoch.
const renaming = ref(false)
const nameDraft = ref('')
const renameBusy = ref(false)
const renameError = ref('')
const displayName = computed(() => props.name ?? '')
// The group's icon (uc.spaces.set-space-icon, GROUP slice) — its avatar; owner-only set/replace/remove. Sealed
// under K_icon and opened on-device against the caller's own grant (peeling the epoch chain, unlike a tenant).
const groupIcon = useObjectUrl() // an owned object URL for the decrypted icon (revoked on replace/unmount)
const iconDialogOpen = ref(false)
const iconBusy = ref(false)
const iconError = ref('')
// Group lifecycle (cr-0090): the group's own pending-deletion state + the danger-zone controls.
const purgeAfter = ref<Date | null>(null)
/** Owner rows only: a compromise rotation queued on this group that needs an owner
 *  (uc.spaces.complete-queued-rotation E2/E8) — read from the caller's own scope row, filled only for an owner. */
const stuckRotation = ref<CompromiseRotation | null>(null)
/** Owner-only: the group's last rotation is still fitting the new lock (ADR 0109) — adding waits per node meanwhile. */
const rekeying = ref(false)
const isPending = computed(() => purgeAfter.value !== null)
const confirmingDelete = ref(false)
const lifecycleBusy = ref(false)
const lifecycleError = ref('')
const purgeDay = computed(() => (purgeAfter.value ? purgeAfter.value.toLocaleDateString(locale.value) : ''))

// Shared directory core (members/trust/review/isOwner/load, cr-0156). The Group load body derives ownership from
// the directory (the group role isn't the tenant role — match my per-tenant username) and reads the lifecycle
// (pending-deletion) state alongside the roster.
const { members, isOwner, trust, trustRefusal, reviewTarget, reviewOpen, loading, loadError, refreshTrust, openReview, reload, on401 } =
  useMemberDirectory(async () => {
    addError.value = ''
    if (!props.scopeId) throw new Error('no-scope')
    const [rows, me, lifecycle, iconBytes] = await Promise.all([
      listScopeMembers(props.scopeId), getMyProfile().catch(() => null), scopeLifecycle(props.scopeId).catch(() => null),
      openGroupIcon(props.scopeId).catch(() => null),
    ])
    myUsername.value = me?.username ?? ''
    purgeAfter.value = lifecycle?.purgeAfter ?? null
    stuckRotation.value = lifecycle?.compromiseRotation ?? null
    rekeying.value = lifecycle?.rekeying ?? false
    // The identity header's avatar: the group's icon opened on-device against our own grant (null → the initial).
    if (iconBytes) groupIcon.set(new Blob([iconBytes as BlobPart], { type: 'image/webp' }))
    else groupIcon.clear()
    return { rows, isOwner: !!me && rows.some((m) => m.username === me.username && m.role === 'OWNER') }
  })
watch(() => props.scopeId, () => reload())

// Min length mirrors the contract (username minLength 2); the server does the real validation + resolve.
const canAdd = computed(() => username.value.trim().length >= 2 && !adding.value)

// Add a member (cr-0086) — with the consent opt-in (cr-0096). First resolve the username (records nothing): if
// the target asked to be consulted, derive this group's name-key `K_name` and wrap it to their identity
// (`group-name-key` context), and submit a **proposal** (a soft pendingConsent add, listed to them once they accept).
// Handing the key — not a name copy — lets them open the SAME canonical name; a modified owner cannot show a divergent
// one. Otherwise it is the ordinary direct-add. Either way the add carries a grant of the group's current key to the
// target's identity key, gated by the same pin check (ADR 0111 decision 4). The directory then reloads (the newcomer
// active, or a pending proposal — owner-visible only).
async function submit() {
  if (!canAdd.value || !props.scopeId) return
  addError.value = ''
  adding.value = true
  const uname = username.value.trim().toLowerCase()
  let candidate: Awaited<ReturnType<typeof resolveGroupMember>> | null = null
  try {
    candidate = await resolveGroupMember(props.scopeId, uname)
    if (candidate.requiresConsent) {
      // Wrap the group's name-key to the invitee so they open the canonical name — the group is listed to them once
      // they accept. This wrap to the invitee's identity is TOFU-gated (cr-0098): a CHANGED key throws
      // IdentityChangedError below rather than silently sealing to a possibly-substituted key.
      const sealedNameKey = await wrapGroupNameKeyForInvitee(props.scopeId, candidate.userId, candidate.identityPublicKey)
      if (!sealedNameKey) throw new Error('cannot open the group to seal the invite name-key')
      await addGroupMember(props.scopeId, uname, candidate.userId, candidate.identityPublicKey, sealedNameKey)
      toast.success(t('groups.invitationSent'))
    } else {
      // The grant's wrap is gated the same way: a CHANGED key throws IdentityChangedError, nothing is sent.
      await addGroupMember(props.scopeId, uname, candidate.userId, candidate.identityPublicKey)
    }
    username.value = ''
    pendingKeyChange.value = null
    members.value = await listScopeMembers(props.scopeId) // refresh — the new member, or the pending proposal
    await refreshTrust()
  } catch (e) {
    if (e instanceof IdentityChangedError && candidate) {
      // The invitee's security key changed since we last pinned it (a legitimate rotation, or an operator MITM).
      // Surface a notice + an explicit accept — never silently trust the new key (ADR 0028, the human path).
      pendingKeyChange.value = { userId: candidate.userId, key: candidate.identityPublicKey, username: uname }
      addError.value = t('groups.identityChanged')
      return
    }
    if (e instanceof TrustStateRefused) {
      // No pin can be read, so the invitee's key cannot be judged (ADR 0028 Decision 7) — named, not generic.
      addError.value = t(refusalMessageKey(e))
      return
    }
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    addError.value =
      code === 'member_not_found'
        ? t('groups.memberNotFound')
        : code === 'already_member'
          ? t('members.alreadyMember')
          : code === 'not_scope_owner'
            ? t('members.notOwner')
            : t('groups.addMemberFailed')
  } finally {
    adding.value = false
  }
}

// Accept a changed invitee key (one scope-rotation worker pass, then re-pin — `acceptChangedKey`) and retry the add.
// Minimal human path (cr-0098): accept trusts + re-pins; the safety-number verification elevates it to *verified*.
async function acceptChangedKeyAndRetry() {
  const pk = pendingKeyChange.value
  if (!pk) return
  try {
    await acceptChangedKey(pk.userId, pk.key)
  } catch (e) {
    addError.value = e instanceof TrustStateRefused ? t(refusalMessageKey(e)) : t('groups.addMemberFailed')
    return
  }
  pendingKeyChange.value = null
  addError.value = ''
  username.value = pk.username
  await submit() // the pin now matches → the add proceeds
}

// Rescind a pending consent proposal (cr-0094) — owner withdraws before acceptance. The one delete without a
// rotation of its own: the proposal held the group's key (ADR 0111 decision 4), so the server queues the group's
// rotation for the next online owner, as a decline does (it refuses the bare delete for anyone else).
async function rescind(m: ScopeMember) {
  actionError.value = ''
  try {
    await removeGroupMember(props.scopeId, m.userId)
    members.value = await listScopeMembers(props.scopeId)
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    actionError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('groups.rescindFailed')
  }
}

// Promote a member to co-owner or demote a co-owner (cr-0088) — owner-only; the last-owner guard is enforced
// server-side (409 last_owner). A pure role change; the directory reloads to reflect it.
async function changeRole(m: ScopeMember, role: SetMemberRoleRequestRoleEnum) {
  actionError.value = ''
  try {
    await setMemberRole(props.scopeId, m.userId, role)
    members.value = await listScopeMembers(props.scopeId)
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    actionError.value =
      code === 'last_owner'
        ? t('groups.lastOwner')
        : code === 'not_scope_owner'
          ? t('members.notOwner')
          : t('groups.roleChangeFailed')
  }
}

// Remove a member — the removal and its rotation are ONE request (ADR 0024 Decision 4): the server deletes the
// membership and commits the new epoch in one transaction, so a failed call leaves the member in place and a
// closed tab cannot leave the group unrotated. The re-grant set is the directory the owner picked the member from,
// minus that member, by the owner's own choice — never a list read after the removal, which is the server's word.
// Owner-only; last-owner guarded server-side. If the rotated group is the active browsing scope, refresh its epoch
// keys so the owner's next new content seals under the fresh epoch, not the stale one.
async function removeMember(m: ScopeMember) {
  actionError.value = ''
  removing.value = true
  try {
    await rotateGroupForRemoval(props.scopeId, members.value, m.userId, revokeLinks.value)
    // ADR 0109: the removal's rotation re-keys every node of the group, on this device, right away. One that stops
    // (a closed tab, a conflict it gives up on) leaves the group re-keying, and an owner's worker resumes it.
    await rekeyScope(props.scopeId).catch(() => {})
    // Shown without the removed member however the server lists them: the directory is display, not a key decision.
    members.value = (await listScopeMembers(props.scopeId)).filter((r) => r.userId !== m.userId)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    actionError.value =
      code === 'last_owner'
        ? t('groups.lastOwner')
        : code === 'not_scope_owner'
          ? t('members.notOwner')
          : t('groups.removeMemberFailed')
  } finally {
    removing.value = false
    pendingRemove.value = null
  }
}

// Turn off every link of the group (ADR 0075 point 5) — owner-only, behind the confirmation dialog. Each link then
// comes back only as a new one its owner renews; the toast names how many went off. The tenant panel offers the same.
const revokingLinks = ref(false)
async function revokeAllLinks() {
  const accepted = await ask({
    header: t('spaceLinks.revokeAll'),
    message: t('spaceLinks.revokeAllConfirm'),
    acceptLabel: t('spaceLinks.revokeAll'),
    acceptIcon: 'link',
  })
  if (!accepted) return
  lifecycleError.value = ''
  revokingLinks.value = true
  try {
    const revoked = await revokeScopeShares(props.scopeId)
    toast.success(t('spaceLinks.revokedAll', { count: revoked }, revoked))
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    lifecycleError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('spaceLinks.revokeAllFailed')
  } finally {
    revokingLinks.value = false
  }
}

// Soft-delete the group (cr-0090) — enters the 30-day grace period (owner-only, inline confirm). The group stays
// readable but read-only + marked; any owner can restore it in-window. Refresh the active scope if it's this group
// (so its banner/read-only state shows) and let the settings hub re-badge its group list.
async function onDelete() {
  lifecycleError.value = ''
  lifecycleBusy.value = true
  try {
    await deleteGroup(props.scopeId)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
    await reload()
    emit('lifecycleChanged')
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    lifecycleError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('groups.deleteFailed')
  } finally {
    lifecycleBusy.value = false
    confirmingDelete.value = false
  }
}

// Rename the group (cr-0097) — owner-only. Re-seal the new name under K_name of the current epoch (renameGroup);
// on success refresh the active scope (the switcher label) if it's this group + let the hub re-label its group list.
function startRename() {
  nameDraft.value = displayName.value
  renameError.value = ''
  renaming.value = true
}

async function saveRename() {
  const next = nameDraft.value.trim()
  if (!next || next === displayName.value) {
    renaming.value = false
    return
  }
  renameBusy.value = true
  renameError.value = ''
  try {
    await renameGroup(props.scopeId, next)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
    emit('lifecycleChanged') // the hub reloads its owned-groups list → the nav label + our `name` prop update
    renaming.value = false
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    renameError.value =
      code === 'not_scope_owner'
        ? t('members.notOwner')
        : code === 'stale_epoch'
          ? t('groups.renameStale')
          : t('groups.renameFailed')
  } finally {
    renameBusy.value = false
  }
}

// Set/replace the group icon: the dialog baked + encoded the bytes; here setSpaceIcon seals + POSTs (retry-once-
// on-stale is inside it), then we refresh the header and signal the always-mounted sidebar to re-decrypt.
async function onIconSave(bytes: Uint8Array) {
  iconBusy.value = true
  iconError.value = ''
  try {
    await setSpaceIcon(props.scopeId, bytes)
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
    await clearSpaceIcon(props.scopeId)
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

/** Restore the group from pending-deletion (owner-only, in-window). */
async function onRestore() {
  lifecycleError.value = ''
  lifecycleBusy.value = true
  try {
    await restoreGroup(props.scopeId)
    if (activeSpace.scopeId === props.scopeId) await refreshActiveScope()
    await reload()
    emit('lifecycleChanged')
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await memberError(e)
    lifecycleError.value = code === 'not_scope_owner' ? t('members.notOwner') : t('groups.restoreFailed')
  } finally {
    lifecycleBusy.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <!-- The group's own name (cr-0097): owner-only inline rename (re-seals under the current epoch, forward-secret). -->
      <div class="group-name-header">
        <template v-if="renaming">
          <VeyqInput
            v-model="nameDraft"
            class="group-name-input"
            autocomplete="off"
            :placeholder="t('groups.renamePlaceholder')"
            @keyup.enter="saveRename"
          />
          <VeyqButton :label="t('common.save')" icon="check" size="small" :loading="renameBusy" @click="saveRename" />
          <VeyqButton :label="t('common.cancel')" variant="secondary" appearance="outlined" size="small" :disabled="renameBusy" @click="renaming = false" />
        </template>
        <template v-else>
          <TenantAvatar :name="displayName" :icon="groupIcon.url.value ?? undefined" />
          <h1 class="group-name-title">{{ displayName || t('groups.untitled') }}</h1>
          <VeyqButton v-if="isOwner" :label="t('groups.rename')" icon="pencil" appearance="text" size="small" @click="startRename" />
          <div v-if="isOwner" class="group-icon-actions">
            <VeyqButton
              :label="groupIcon.url.value ? t('spaceIcon.change') : t('spaceIcon.add')"
              icon="image"
              appearance="text"
              size="small"
              :disabled="iconBusy"
              @click="iconDialogOpen = true"
            />
            <VeyqButton
              v-if="groupIcon.url.value"
              :label="t('spaceIcon.remove')"
              icon="trash"
              appearance="text"
              variant="danger"
              size="small"
              :disabled="iconBusy"
              @click="removeIcon"
            />
          </div>
        </template>
      </div>
      <VeyqMessage v-if="renameError" tone="error">{{ renameError }}</VeyqMessage>
      <VeyqMessage v-if="iconError" tone="error">{{ iconError }}</VeyqMessage>
      <CompromiseRotationNotice v-if="isOwner" :scope-id="scopeId" :state="stuckRotation" @requeued="stuckRotation = $event" />
      <VeyqMessage v-if="isOwner && rekeying" tone="info">{{ t('rotation.statusHint.rekeying') }}</VeyqMessage>

      <div class="settings-section-header">
        <div>
          <h2>{{ t('groups.members') }}</h2>
          <p>{{ t('groups.membersSub') }}</p>
        </div>
      </div>

      <!-- Owner-only: add an existing tenant member by username (inline; the tenant owner has no say, ADR 0014). -->
      <form v-if="isOwner" class="group-add-form" @submit.prevent="submit">
        <label class="settings-muted" for="group-add-username">{{ t('groups.addMemberIntro') }}</label>
        <div class="group-add-row">
          <VeyqInput id="group-add-username" v-model="username" autocomplete="off" :placeholder="t('groups.usernamePlaceholder')" />
          <VeyqButton type="submit" :label="t('groups.addMember')" icon="user-plus" :loading="adding" :disabled="!canAdd" />
        </div>
        <VeyqMessage v-if="addError" tone="error">{{ addError }}</VeyqMessage>
        <!-- Changed-identity notice (cr-0098): accept re-pins the new key and retries the add (human path). -->
        <div v-if="pendingKeyChange" class="group-key-change">
          <VeyqButton
            variant="warn"
            size="small"
            :label="t('groups.acceptKeyChange')"
            icon="shield"
            :loading="adding"
            @click="acceptChangedKeyAndRetry"
          />
        </div>
      </form>

      <VeyqMessage v-if="actionError" tone="error">{{ actionError }}</VeyqMessage>
      <!-- This device refused its own pin store (ADR 0028 Decision 7): no wrap to a foreign key until it reads. -->
      <VeyqMessage v-if="trustRefusal" tone="warn">
        {{ t(trustRefusal) }}
        <TrustResetLink />
      </VeyqMessage>

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
          <div v-for="m in members" :key="m.userId" class="veyq-list-row member-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">@{{ m.username }}</strong>
              <span class="veyq-row-subtitle">{{ m.role }}</span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="m.pendingConsent ? 'muted' : 'success'">
                  {{ m.pendingConsent ? t('members.statusPendingConsent') : t('members.statusActive') }}
                </span>
                <!-- Identity trust (ADR 0028 Decision 3): "Verified" after a compare on the member's profile, "Key changed"
                     when the directory key differs from the pin (a rotation or an operator substitution) - louder for a
                     verified contact. -->
                <MemberTrustBadge :trust="trust[m.userId]" />
              </div>
            </div>
            <!-- Review a changed key (cr-0099), available to ANY member: compare expected-vs-current out-of-band,
                 then accept (re-pin) or refuse (leave blocked). -->
            <div v-if="trust[m.userId]?.state === 'changed'" class="trust-actions">
              <VeyqButton :label="t('verify.review')" icon="exclamation-triangle" variant="warn" appearance="text" size="small" @click="openReview(m)" />
            </div>
            <!-- Owner-only controls (cr-0088/0089): promote a member to co-owner, demote a co-owner, or remove a
                 member. Remove is destructive (rotates the scope key forward) so it takes an inline confirm. The
                 last-owner guard is enforced server-side. You can't remove yourself here. -->
            <div v-if="isOwner && pendingRemove === m.userId" class="management-actions">
              <span class="settings-muted remove-confirm-label">{{ t('groups.removeConfirm') }}</span>
              <div class="remove-revoke-links">
                <VeyqCheckbox v-model="revokeLinks" input-id="remove-revoke-links" :disabled="removing" />
                <label for="remove-revoke-links">
                  <span>{{ t('groups.removeRevokeLinks') }}</span>
                  <span class="settings-muted remove-revoke-links-hint">{{ t('groups.removeRevokeLinksHint') }}</span>
                </label>
              </div>
              <VeyqButton
                :label="t('groups.removeConfirmYes')"
                icon="user-minus"
                variant="danger"
                size="small"
                :loading="removing"
                @click="removeMember(m)"
              />
              <VeyqButton
                :label="t('common.cancel')"
                variant="secondary"
                appearance="outlined"
                size="small"
                :disabled="removing"
                @click="pendingRemove = null"
              />
            </div>
            <!-- A pending consent proposal (cr-0094): the only owner action is to rescind (withdraw). No promote/
                 demote/remove — they are not yet a real member; the proposal held the key, so the server queues the
                 group's rotation on a rescind (ADR 0111 decision 4). -->
            <div v-else-if="isOwner && m.pendingConsent" class="management-actions">
              <VeyqButton
                :label="t('groups.rescind')"
                icon="times"
                variant="secondary"
                appearance="outlined"
                size="small"
                @click="rescind(m)"
              />
            </div>
            <div v-else-if="isOwner" class="management-actions">
              <VeyqButton
                v-if="m.role === 'MEMBER'"
                :label="t('groups.promote')"
                icon="arrow-up"
                variant="secondary"
                appearance="outlined"
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Owner)"
              />
              <VeyqButton
                v-else
                :label="t('groups.demote')"
                icon="arrow-down"
                variant="secondary"
                appearance="outlined"
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Member)"
              />
              <VeyqButton
                v-if="m.username !== myUsername"
                :label="t('groups.remove')"
                icon="user-minus"
                variant="danger"
                appearance="outlined"
                size="small"
                @click="askRemove(m.userId)"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Every public link of the group at once (ADR 0075 point 5), beside the danger zone — owner-only, and not while
           the group is being deleted (it is read-only then): each link comes back only by renewal. -->
      <section v-if="isOwner && !loading && loadError === '' && !isPending" class="settings-form space-links">
        <div class="settings-section-header">
          <div>
            <h2>{{ t('spaceLinks.title') }}</h2>
            <p class="settings-muted">{{ t('spaceLinks.sub') }}</p>
          </div>
        </div>
        <div class="danger-row">
          <VeyqButton
            :label="t('spaceLinks.revokeAll')"
            icon="link"
            variant="danger"
            appearance="outlined"
            :loading="revokingLinks"
            @click="revokeAllLinks"
          />
        </div>
      </section>

      <!-- Danger zone (cr-0090): owner-only group lifecycle. Active → delete (30-day grace, inline confirm);
           pending-deletion → a countdown + restore. Deletion is soft + reversible in-window, so it's not a hard
           destructive action here. -->
      <section v-if="isOwner && !loading && loadError === ''" class="settings-form danger-zone">
        <div class="settings-section-header">
          <div>
            <h2>{{ t('groups.dangerZone') }}</h2>
            <p class="settings-muted">{{ isPending ? t('groups.pendingSub') : t('groups.deleteSub') }}</p>
          </div>
        </div>

        <VeyqMessage v-if="lifecycleError" tone="error">{{ lifecycleError }}</VeyqMessage>

        <div v-if="isPending" class="danger-row">
          <VeyqMessage tone="warn">{{ t('groups.pendingDeletion', { day: purgeDay }) }}</VeyqMessage>
          <VeyqButton :label="t('groups.restore')" icon="undo" :loading="lifecycleBusy" @click="onRestore" />
        </div>
        <div v-else-if="confirmingDelete" class="danger-row">
          <span class="settings-muted">{{ t('groups.deleteConfirm') }}</span>
          <VeyqButton :label="t('groups.deleteConfirmYes')" icon="trash" variant="danger" :loading="lifecycleBusy" @click="onDelete" />
          <VeyqButton :label="t('common.cancel')" variant="secondary" appearance="outlined" :disabled="lifecycleBusy" @click="confirmingDelete = false" />
        </div>
        <div v-else class="danger-row">
          <VeyqButton :label="t('groups.deleteGroup')" icon="trash" variant="danger" appearance="outlined" @click="confirmingDelete = true" />
        </div>
      </section>
    </section>

    <!-- The owner's icon editor (uc.spaces.set-space-icon): pick + frame 1:1; on save the crop is baked + encoded
         on-device and this panel seals + POSTs it (peeling the epoch chain to seal under the current epoch). -->
    <SpaceIconDialog v-if="iconDialogOpen" :busy="iconBusy" @save="onIconSave" @close="iconDialogOpen = false" />

    <!-- Review a changed identity key (cr-0099); the proactive compare is on the member's profile (ADR 0028 Decision 3). -->
    <ChangedKeyReviewDialog v-if="reviewTarget" v-model:visible="reviewOpen" :member="reviewTarget" @accepted="refreshTrust" />
  </section>
</template>

<style scoped>
/* The removal confirmation's opt-in (ADR 0075) takes a row of its own, like MemoryNodeDialog's opt-in. */
.management-actions:has(.remove-revoke-links) {
  flex-wrap: wrap;
}
.remove-revoke-links {
  flex: 1 0 100%;
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
}
.remove-revoke-links label {
  display: grid;
  gap: 0.12rem;
  font-size: 0.92rem;
  line-height: 1.35;
}

.danger-zone {
  margin-top: 1.5rem;
  padding-top: 1.25rem;
  border-top: 1px solid var(--app-line);
}
.trust-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.danger-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}
.danger-row :deep(.veyq-message) {
  margin: 0;
  flex: 1;
  min-width: 0;
}
.group-name-header {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 1rem;
}
.group-name-title {
  margin: 0;
  flex: 1;
  min-width: 0;
}
.group-icon-actions {
  display: flex;
  gap: 0.25rem;
}
.group-name-input {
  flex: 1;
  min-width: 0;
}
.group-add-form {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.group-add-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.group-add-row :deep(.veyq-input) {
  flex: 1;
  min-width: 0;
}
</style>
