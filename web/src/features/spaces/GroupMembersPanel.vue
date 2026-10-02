<script setup lang="ts">
// uc.spaces.add-group-member (web, cr-0086) — the "Members" rubric of the GROUP settings (re-homed into the
// settings hub, ADR 0022). Lists the group's members (username + role + granted/awaiting-access) and, for the
// group OWNER, adds an existing tenant member by username (inline). Markup mirrors TenantMembersPanel. Operator-
// blind: only operational metadata (usernames/roles/grant-present) is shown; the add records a grant-less
// membership, the key is delivered later by grant-delivery (cr-0076), no crypto here. Real names stay `@username`
// — they're sealed under K_tenant, which a group owner needn't hold (the active key here is the group's K_SC).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Checkbox from 'primevue/checkbox'
import { listScopeMembers, addGroupMember, resolveGroupMember, setMemberRole, removeGroupMember, memberError, SetMemberRoleRequestRoleEnum, type ScopeMember } from './members'
import { rotateGroupForRemoval, deleteGroup, restoreGroup, scopeLifecycle, wrapGroupNameKeyForInvitee, renameGroup, setSpaceIcon, clearSpaceIcon, openGroupIcon, openScopeById } from './scopes'
import { zeroEpochKeys } from './epochs'
import { acceptKeyChange, IdentityChangedError } from '../account/identityPins'
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
import { revokeScopeShares } from '../memory/share'
import SpaceIconDialog from './SpaceIconDialog.vue'
import TenantAvatar from '../../shared/ui/TenantAvatar.vue'
import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'

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
const isPending = computed(() => purgeAfter.value !== null)
const confirmingDelete = ref(false)
const lifecycleBusy = ref(false)
const lifecycleError = ref('')
const purgeDay = computed(() => (purgeAfter.value ? purgeAfter.value.toLocaleDateString(locale.value) : ''))

// Shared directory core (members/trust/review/isOwner/load, cr-0156). The Group load body derives ownership from
// the directory (the group role isn't the tenant role — match my per-tenant username) and reads the lifecycle
// (pending-deletion) state alongside the roster.
const { members, isOwner, trust, trustRefusal, unadmitted, reviewTarget, reviewOpen, loading, loadError, refreshTrust, refreshAdmission, openReview, reload, on401 } =
  useMemberDirectory(async () => {
    addError.value = ''
    if (!props.scopeId) throw new Error('no-scope')
    const [rows, me, lifecycle, iconBytes] = await Promise.all([
      listScopeMembers(props.scopeId), getMyProfile().catch(() => null), scopeLifecycle(props.scopeId).catch(() => null),
      openGroupIcon(props.scopeId).catch(() => null),
    ])
    myUsername.value = me?.username ?? ''
    purgeAfter.value = lifecycle?.purgeAfter ?? null
    // The identity header's avatar: the group's icon opened on-device against our own grant (null → the initial).
    if (iconBytes) groupIcon.set(new Blob([iconBytes as BlobPart], { type: 'image/webp' }))
    else groupIcon.clear()
    return { rows, isOwner: !!me && rows.some((m) => m.username === me.username && m.role === 'OWNER') }
  }, async () => {
    // The group's current epoch key, opened for this read and zeroed after it — the key its admissions are tagged under.
    const opened = props.scopeId ? await openScopeById(props.scopeId) : null
    return opened
      ? { scopeId: opened.scopeId, kind: opened.kind, key: opened.key, epoch: opened.epochs.current, release: () => zeroEpochKeys(opened.epochs) }
      : null
  })
watch(() => props.scopeId, () => reload())

// Min length mirrors the contract (username minLength 2); the server does the real validation + resolve.
const canAdd = computed(() => username.value.trim().length >= 2 && !adding.value)

// Add a member (cr-0086) — with the consent opt-in (cr-0096). First resolve the username (records nothing): if
// the target asked to be consulted, derive this group's name-key `K_name` and wrap it to their identity
// (`group-name-key` context), and submit a **proposal** (a soft pendingConsent add grant-delivery skips until they
// accept). Handing the key — not a name copy — lets them open the SAME canonical name; a modified owner cannot show
// a divergent one. Otherwise it is the ordinary direct-add. The directory then reloads (newcomer = awaiting access,
// or a pending proposal — owner-visible only).
async function submit() {
  if (!canAdd.value || !props.scopeId) return
  addError.value = ''
  adding.value = true
  const uname = username.value.trim().toLowerCase()
  let candidate: Awaited<ReturnType<typeof resolveGroupMember>> | null = null
  try {
    candidate = await resolveGroupMember(props.scopeId, uname)
    if (candidate.requiresConsent) {
      // Wrap the group's name-key to the invitee so they open the canonical name — K_SC stays gated until they
      // accept. This is the invite's first wrap to the invitee's identity, so it is TOFU-gated (cr-0098): a
      // CHANGED key throws IdentityChangedError below rather than silently sealing to a possibly-substituted key.
      const sealedNameKey = await wrapGroupNameKeyForInvitee(props.scopeId, candidate.userId, candidate.identityPublicKey)
      if (!sealedNameKey) throw new Error('cannot open the group to seal the invite name-key')
      await addGroupMember(props.scopeId, uname, candidate.userId, sealedNameKey)
      toast.success(t('groups.invitationSent'))
    } else {
      await addGroupMember(props.scopeId, uname, candidate.userId)
    }
    username.value = ''
    pendingKeyChange.value = null
    members.value = await listScopeMembers(props.scopeId) // refresh — awaiting access, or the pending proposal
    await refreshTrust()
    await refreshAdmission()
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

// Accept a changed invitee key (re-pin) and retry the add. Minimal human path (cr-0098) — the out-of-band
// safety-number verification that would elevate this to *verified* is the follow-up CR; here accept trusts + re-pins.
async function acceptChangedKeyAndRetry() {
  const pk = pendingKeyChange.value
  if (!pk) return
  try {
    await acceptKeyChange(pk.userId, pk.key)
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
// rotation: a soft proposal was never keyed (the server refuses the bare delete for anyone else).
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
    acceptIcon: 'pi pi-link',
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
    acceptIcon: 'pi pi-trash',
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
          <InputText
            v-model="nameDraft"
            class="group-name-input"
            autocomplete="off"
            :placeholder="t('groups.renamePlaceholder')"
            @keyup.enter="saveRename"
          />
          <Button :label="t('common.save')" icon="pi pi-check" size="small" :loading="renameBusy" @click="saveRename" />
          <Button :label="t('common.cancel')" severity="secondary" outlined size="small" :disabled="renameBusy" @click="renaming = false" />
        </template>
        <template v-else>
          <TenantAvatar :name="displayName" :icon="groupIcon.url.value ?? undefined" />
          <h1 class="group-name-title">{{ displayName || t('groups.untitled') }}</h1>
          <Button v-if="isOwner" :label="t('groups.rename')" icon="pi pi-pencil" text size="small" @click="startRename" />
          <div v-if="isOwner" class="group-icon-actions">
            <Button
              :label="groupIcon.url.value ? t('spaceIcon.change') : t('spaceIcon.add')"
              icon="pi pi-image"
              text
              size="small"
              :disabled="iconBusy"
              @click="iconDialogOpen = true"
            />
            <Button
              v-if="groupIcon.url.value"
              :label="t('spaceIcon.remove')"
              icon="pi pi-trash"
              text
              severity="danger"
              size="small"
              :disabled="iconBusy"
              @click="removeIcon"
            />
          </div>
        </template>
      </div>
      <Message v-if="renameError" severity="error" :closable="false">{{ renameError }}</Message>
      <Message v-if="iconError" severity="error" :closable="false">{{ iconError }}</Message>

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
          <InputText id="group-add-username" v-model="username" autocomplete="off" :placeholder="t('groups.usernamePlaceholder')" />
          <Button type="submit" :label="t('groups.addMember')" icon="pi pi-user-plus" :loading="adding" :disabled="!canAdd" />
        </div>
        <Message v-if="addError" severity="error" :closable="false">{{ addError }}</Message>
        <!-- Changed-identity notice (cr-0098): accept re-pins the new key and retries the add (human path). -->
        <div v-if="pendingKeyChange" class="group-key-change">
          <Button
            type="button"
            severity="warn"
            size="small"
            :label="t('groups.acceptKeyChange')"
            icon="pi pi-shield"
            :loading="adding"
            @click="acceptChangedKeyAndRetry"
          />
        </div>
      </form>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
      <!-- This device refused its own pin store (ADR 0028 Decision 7): no wrap to a foreign key until it reads. -->
      <Message v-if="trustRefusal" severity="warn" :closable="false">
        {{ t(trustRefusal) }}
        <TrustResetLink />
      </Message>

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
          <div v-for="m in members" :key="m.userId" class="veyq-list-row member-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">@{{ m.username }}</strong>
              <span class="veyq-row-subtitle">{{ m.role }}</span>
              <div class="veyq-row-meta">
                <span class="veyq-badge" :class="m.pendingConsent ? 'muted' : m.hasGrant ? 'success' : 'warning'">
                  {{ m.pendingConsent ? t('members.statusPendingConsent') : m.hasGrant ? t('members.statusActive') : t('members.statusAwaiting') }}
                </span>
                <!-- Changed-identity warning (cr-0099): the ONLY trust surface — the directory key differs from the
                     pin (a rotation or an operator substitution). Verified/recognized are intentionally not shown. -->
                <span v-if="trust[m.userId] === 'changed'" class="veyq-badge changed">
                  <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ t('verify.badgeChanged') }}
                </span>
                <!-- Admission not verified (ADR 0020 move 6): no key-holder's worker delivers to this member. -->
                <span v-if="unadmitted[m.userId]" class="veyq-badge changed">
                  <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ t('members.badgeNotAdmitted') }}
                </span>
              </div>
            </div>
            <!-- Review a changed key (cr-0099), available to ANY member: compare expected-vs-current out-of-band,
                 then accept (re-pin) or refuse (leave blocked). -->
            <div v-if="trust[m.userId] === 'changed'" class="trust-actions">
              <Button :label="t('verify.review')" icon="pi pi-exclamation-triangle" severity="warn" text size="small" @click="openReview(m)" />
            </div>
            <!-- Owner-only controls (cr-0088/0089): promote a member to co-owner, demote a co-owner, or remove a
                 member. Remove is destructive (rotates the scope key forward) so it takes an inline confirm. The
                 last-owner guard is enforced server-side. You can't remove yourself here. -->
            <div v-if="isOwner && pendingRemove === m.userId" class="management-actions">
              <span class="settings-muted remove-confirm-label">{{ t('groups.removeConfirm') }}</span>
              <div class="remove-revoke-links">
                <Checkbox v-model="revokeLinks" input-id="remove-revoke-links" binary :disabled="removing" />
                <label for="remove-revoke-links">
                  <span>{{ t('groups.removeRevokeLinks') }}</span>
                  <span class="settings-muted remove-revoke-links-hint">{{ t('groups.removeRevokeLinksHint') }}</span>
                </label>
              </div>
              <Button
                :label="t('groups.removeConfirmYes')"
                icon="pi pi-user-minus"
                severity="danger"
                size="small"
                :loading="removing"
                @click="removeMember(m)"
              />
              <Button
                :label="t('common.cancel')"
                severity="secondary"
                outlined
                size="small"
                :disabled="removing"
                @click="pendingRemove = null"
              />
            </div>
            <!-- A pending consent proposal (cr-0094): the only owner action is to rescind (withdraw). No promote/
                 demote/remove — they are not yet a real member, and nothing was keyed, so rescind never rotates. -->
            <div v-else-if="isOwner && m.pendingConsent" class="management-actions">
              <Button
                :label="t('groups.rescind')"
                icon="pi pi-times"
                severity="secondary"
                outlined
                size="small"
                @click="rescind(m)"
              />
            </div>
            <div v-else-if="isOwner" class="management-actions">
              <Button
                v-if="m.role === 'MEMBER'"
                :label="t('groups.promote')"
                icon="pi pi-arrow-up"
                severity="secondary"
                outlined
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Owner)"
              />
              <Button
                v-else
                :label="t('groups.demote')"
                icon="pi pi-arrow-down"
                severity="secondary"
                outlined
                size="small"
                @click="changeRole(m, SetMemberRoleRequestRoleEnum.Member)"
              />
              <Button
                v-if="m.username !== myUsername"
                :label="t('groups.remove')"
                icon="pi pi-user-minus"
                severity="danger"
                outlined
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
          <Button
            :label="t('spaceLinks.revokeAll')"
            icon="pi pi-link"
            severity="danger"
            outlined
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

        <Message v-if="lifecycleError" severity="error" :closable="false">{{ lifecycleError }}</Message>

        <div v-if="isPending" class="danger-row">
          <Message severity="warn" :closable="false">{{ t('groups.pendingDeletion', { day: purgeDay }) }}</Message>
          <Button :label="t('groups.restore')" icon="pi pi-undo" :loading="lifecycleBusy" @click="onRestore" />
        </div>
        <div v-else-if="confirmingDelete" class="danger-row">
          <span class="settings-muted">{{ t('groups.deleteConfirm') }}</span>
          <Button :label="t('groups.deleteConfirmYes')" icon="pi pi-trash" severity="danger" :loading="lifecycleBusy" @click="onDelete" />
          <Button :label="t('common.cancel')" severity="secondary" outlined :disabled="lifecycleBusy" @click="confirmingDelete = false" />
        </div>
        <div v-else class="danger-row">
          <Button :label="t('groups.deleteGroup')" icon="pi pi-trash" severity="danger" outlined @click="confirmingDelete = true" />
        </div>
      </section>
    </section>

    <!-- The owner's icon editor (uc.spaces.set-space-icon): pick + frame 1:1; on save the crop is baked + encoded
         on-device and this panel seals + POSTs it (peeling the epoch chain to seal under the current epoch). -->
    <SpaceIconDialog v-if="iconDialogOpen" :busy="iconBusy" @save="onIconSave" @close="iconDialogOpen = false" />

    <!-- Review a changed identity key (cr-0099) — the only verify surface until scope-wide strict-mode (cr-0100). -->
    <ChangedKeyReviewDialog v-if="reviewTarget" v-model:visible="reviewOpen" :member="reviewTarget" @accepted="refreshTrust" />
  </section>
</template>

<style scoped>
/* The removal confirmation's opt-in (ADR 0075) takes a row of its own, like NodeMutationDialog's opt-in. */
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
.danger-row :deep(.p-message) {
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
.group-add-row :deep(.p-inputtext) {
  flex: 1;
  min-width: 0;
}
</style>
