<script setup lang="ts">
// uc.admin.create-tenant + uc.admin.suspend-tenant (web) — the per-tenant detail page of the Server settings:
// one tenant's status, its availability lever (take offline / bring back online, ADR 0066), its storage usage and
// quota (uc.admin.tenant-storage-quota — set, change or clear, GiB in and bytes out) and, for a PENDING one, its
// owner-invite management (reassign email / resend / revoke). uc.admin.delete-tenant (ADR 0101 decision 10): an ACTIVE or
// SUSPENDED tenant is deleted behind its domain typed as the confirmation, a PENDING_DELETION one shows when it is erased
// and can be stopped; an ownerless one (its last owner's account erased at the Server Owner's door, decision 9) says so
// and offers its two ways on — an owner invite to one of its members, or that deletion. Addressed by tenantId
// (the Tenants collection in the settings rail routes here); mirrors the old TenantAdminPanel row + edit, scoped
// to a single tenant. Operator-blind: only operational metadata (name/domain/owner email/status). The backend
// enforces Server-Owner with a 403 regardless of what the client renders.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  changeTenantDomain,
  deleteTenant,
  domainConfirmed,
  formatQuotaGib,
  inviteTenantOwner,
  listTenants,
  parseQuotaGib,
  reactivateTenant,
  reassignInvite,
  resendInvite,
  revokeTenant,
  setTenantStorageQuota,
  stopTenantDeletion,
  suspendTenant,
  tenantError,
  type TenantSummary,
} from './tenants'
import { formatBytes } from '../../shared/formatBytes'
import { buildInviteLink, copyText } from '../../shared/inviteLink'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'
import StepStatementFields from '../instance/StepStatementFields.vue'
import { stepStatement, StepStatementInvalid, type StepStatementDraft } from '../instance/stepStatement'

// Addressed by id (cr-0087) — the settings rail renders one panel per tenant; the tenantId watch re-loads on switch.
const props = defineProps<{ tenantId: string }>()
// The console owns the list and the selection, so a revoked tenant is its to drop — this surface has one route.
// `changed` = the tenant's status moved (deleted / deletion stopped): the list's marker and order are the console's.
const emit = defineEmits<{ revoked: [tenantId: string]; changed: [] }>()

const { t, locale } = useI18n()
const toast = useToast()
// Confirmation is the console's own dialog (`shared/ui/ConfirmDialog.vue`, mounted once in App.vue), never the
// browser's box — `no-alert` in eslint.config.js is what keeps it that way (cr-admin-web-own-confirm-dialog).
const { ask } = useConfirm()
const router = useRouter()
const on401 = handle401(router)

const tenant = ref<TenantSummary | null>(null)
const actionError = ref('')

const email = ref('')
// The change-address form is prefilled with the current domain, so the field shows where the tenant is now.
const domain = ref('')
// After a resend/reassign, show the fresh invite link to copy (cr-0080 — the owner hands it off, no mailer).
const inviteUrl = ref('')
const copied = ref(false)
// The quota as typed, in GiB — prefilled with the current one (empty when none is set).
const quotaGib = ref('')
// The quota as loaded: a field nobody edited saves these bytes, never the rounded GiB it shows — otherwise saving it
// unchanged moves the quota, and one under 0.01 GiB, shown as "0", stopped every upload into the tenant.
const loadedQuota = ref<{ text: string; bytes: number } | null>(null)
// The delete confirmation as typed: the tenant's domain (uc.admin.delete-tenant). Cleared on every load.
const deleteTyped = ref('')

const isPending = computed(() => tenant.value?.status === 'PENDING')
// ADR 0066 — the third status. Availability only: SUSPENDED says nothing about the tenant's content, which is
// exactly as encrypted and exactly as untouched as it was ACTIVE; only reachability changed.
const isSuspended = computed(() => tenant.value?.status === 'SUSPENDED')
const isActive = computed(() => tenant.value?.status === 'ACTIVE')
// ADR 0101 decision 10 — offline like SUSPENDED, and erased at `deletionDueAt` unless stopped.
const isPendingDeletion = computed(() => tenant.value?.status === 'PENDING_DELETION')
const statusLabel = computed(() =>
  isPending.value ? t('tenant.statusPending')
    : isSuspended.value ? t('tenant.statusSuspended')
      : isPendingDeletion.value ? t('tenant.statusPendingDeletion')
        : t('tenant.statusActive'),
)
const statusTone = computed(() => (isPending.value ? 'warning' : isSuspended.value || isPendingDeletion.value ? 'danger' : 'success'))
// An ownerless tenant's stored owner address is its first owner's, whose account may be the very one erased: the
// header shows the domain alone rather than name an address that owns nothing any more.
const ownerless = computed(() => tenant.value?.ownerless === true)
const at = (d: Date) => d.toLocaleString(locale.value)
const deletionDue = computed(() => (tenant.value?.deletionDueAt ? at(tenant.value.deletionDueAt) : ''))

const { pending: loading, error: loadErr, reload } = useAsyncData(async () => {
  actionError.value = ''
  inviteUrl.value = ''
  deleteTyped.value = ''
  const found = (await listTenants()).find((x) => x.tenantId === props.tenantId) ?? null
  tenant.value = found
  if (found) {
    email.value = found.ownerEmail
    domain.value = found.domain
    quotaGib.value = found.storageQuotaBytes == null ? '' : formatQuotaGib(found.storageQuotaBytes)
    loadedQuota.value = found.storageQuotaBytes == null ? null : { text: quotaGib.value, bytes: found.storageQuotaBytes }
  }
  return found
}, { onError: on401 })
watch(() => props.tenantId, () => reload())
// The load error, plus the business `gone` state (loaded fine, no such tenant) the error slot can't carry.
const loadError = computed<'' | 'notAuthorized' | 'failed' | 'gone'>(() => {
  const kind = loadErrorKind(loadErr.value)
  if (kind) return kind
  if (!loading.value && tenant.value === null) return 'gone'
  return ''
})

// Resend/reassign both re-mint the invite → capture the fresh secret + show its link; reloads the tenant behind it.
const { run: runMint, pending: busy } = useAsyncAction(
  async (action: () => Promise<{ bootstrapSecret: string }>, successKey: string) => {
    const state = await action()
    toast.success(t(successKey))
    if (tenant.value) inviteUrl.value = buildInviteLink(state.bootstrapSecret, tenant.value.domain)
  },
  { onError: (e) => { on401(e); if (!isUnauthorized(e)) actionError.value = t('tenant.actionFailed') } },
)
function mint(action: () => Promise<{ bootstrapSecret: string }>, successKey: string) {
  if (!tenant.value) return
  actionError.value = ''
  return runMint(action, successKey)
}

const resend = () => mint(() => resendInvite(props.tenantId), 'tenant.resent')
function reassign() {
  if (email.value.trim().length === 0) return
  return mint(() => reassignInvite(props.tenantId, email.value.trim()), 'tenant.reassigned')
}

// A suspension over content carries a statement of reasons to the tenant's owners, by mail (ADR 0093 § 7): the Server
// Owner ticks it — the server insists when a moderator's proposal is open (`statement_required`) — and writes it.
const overContent = ref(false)
const draft = ref<StepStatementDraft>({ ground: 'TERMS_BREACH', provision: '', facts: '' })
const SUSPEND_REFUSALS: Record<string, string> = {
  statement_required: 'moderation.steps.errors.statementRequired',
  statement_facts_required: 'moderation.steps.errors.factsRequired',
  statement_provision_required: 'moderation.steps.errors.provisionRequired',
  moderation_epoch_changed: 'moderation.steps.errors.epochChanged',
  moderation_off: 'moderation.steps.errors.moderationOff',
}

// Suspend / reactivate (uc.admin.suspend-tenant). Unlike `revoke` this never leaves the panel — the tenant is
// still there, and the Server Owner is expected to bring it back — so it reloads in place rather than routing away.
// Suspend is confirm-gated (it locks every member out at once); reactivate is not (it only ever restores access).
async function setSuspended(suspend: boolean) {
  if (suspend) {
    const accepted = await ask({
      header: t('tenant.suspendConfirmTitle'),
      message: t('tenant.suspendConfirm'),
      acceptLabel: t('tenant.suspend'),
      acceptIcon: 'pi pi-pause',
    })
    if (!accepted) return
  }
  actionError.value = ''
  try {
    if (suspend) await suspendTenant(props.tenantId, overContent.value ? await stepStatement(draft.value) : null)
    else await reactivateTenant(props.tenantId)
    toast.success(t(suspend ? 'tenant.suspended' : 'tenant.reactivated'))
    overContent.value = false
    draft.value = { ground: 'TERMS_BREACH', provision: '', facts: '' }
    await reload()
  } catch (e) {
    if (e instanceof StepStatementInvalid) {
      actionError.value = t('moderation.steps.errors.moderationOff')
      return
    }
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await decodeHttpError(e)
    if (code === 'statement_required') overContent.value = true
    actionError.value = t(code && SUSPEND_REFUSALS[code] ? SUSPEND_REFUSALS[code] : 'tenant.actionFailed')
  }
}

// Change address (uc.admin.create-tenant). Offered on PENDING and ACTIVE alike — a domain is how a tenant is
// *reached*, not part of its invite lifecycle — and confirm-gated, because the old address stops answering the
// moment this returns. What makes the new one actually resolve is the server's ingress projector (ADR 0063);
// there is nothing for the client to do about DNS or certificates beyond telling the operator it moved.
async function changeDomain() {
  const next = domain.value.trim().toLowerCase()
  if (!tenant.value || next.length === 0 || next === tenant.value.domain) return
  const accepted = await ask({
    header: t('tenant.changeDomainConfirmTitle'),
    message: t('tenant.changeDomainConfirm', { domain: next }),
    acceptLabel: t('tenant.changeDomainSubmit'),
    acceptIcon: 'pi pi-arrow-right',
  })
  if (!accepted) return
  actionError.value = ''
  try {
    await changeTenantDomain(props.tenantId, next)
    toast.success(t('tenant.domainChanged'))
    await reload()
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { status, code } = await tenantError(e)
    actionError.value =
      code === 'domain_taken' ? t('tenant.domainTaken')
        : code === 'tenant_not_pending_or_active' ? t('tenant.domainNotMovable')
          : status === 400 ? t('tenant.domainInvalid')
            : t('tenant.actionFailed')
  }
}

// The storage quota (uc.admin.tenant-storage-quota, ADR 0073 §1). Not confirm-gated: it deletes nothing — a quota
// below the usage only refuses the tenant's next upload — and it is reversed by setting it again.
const storageUsed = computed(() => (tenant.value ? formatBytes(tenant.value.storageUsedBytes, locale.value) : ''))
const storageQuota = computed(() =>
  tenant.value?.storageQuotaBytes == null ? t('tenant.quotaNone') : formatBytes(tenant.value.storageQuotaBytes, locale.value),
)
async function saveQuota(clear: boolean) {
  const unchanged = loadedQuota.value !== null && quotaGib.value.trim() === loadedQuota.value.text
  const bytes = clear ? null : unchanged ? loadedQuota.value!.bytes : parseQuotaGib(quotaGib.value)
  actionError.value = ''
  if (bytes === undefined) {
    actionError.value = t('tenant.quotaInvalid')
    return
  }
  try {
    await setTenantStorageQuota(props.tenantId, bytes)
    toast.success(t(bytes === null ? 'tenant.quotaCleared' : 'tenant.quotaSaved'))
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('tenant.actionFailed')
  }
}

async function revoke() {
  const accepted = await ask({
    header: t('tenant.revokeConfirmTitle'),
    message: t('tenant.revokeConfirm'),
    acceptLabel: t('tenant.revoke'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  actionError.value = ''
  try {
    await revokeTenant(props.tenantId)
    emit('revoked', props.tenantId) // the tenant is gone: the console reloads its list and closes this panel
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('tenant.actionFailed')
  }
}

// Delete (uc.admin.delete-tenant). The confirmation is the tenant's domain typed into the panel — the console's first
// typed confirmation, kept beside the action rather than in the shared ConfirmDialog, whose single-flight yes/no has no
// field. The typed value is what is sent: the server compares it again, so a stale panel cannot delete another tenant.
const deleteArmed = computed(() => tenant.value !== null && domainConfirmed(deleteTyped.value, tenant.value.domain))
// Over content, the deletion carries a statement of reasons to the tenant's owners, as a suspension does (ADR 0093 § 7).
const deleteOverContent = ref(false)
const deleteDraft = ref<StepStatementDraft>({ ground: 'TERMS_BREACH', provision: '', facts: '' })
async function removeTenant() {
  if (!deleteArmed.value) return
  actionError.value = ''
  try {
    const statement = deleteOverContent.value ? await stepStatement(deleteDraft.value) : null
    const { dueAt } = await deleteTenant(props.tenantId, deleteTyped.value, statement)
    toast.success(t('tenant.deletionStarted', { date: at(dueAt) }))
    deleteOverContent.value = false
    deleteDraft.value = { ground: 'TERMS_BREACH', provision: '', facts: '' }
    emit('changed')
    await reload()
  } catch (e) {
    if (e instanceof StepStatementInvalid) {
      actionError.value = t('moderation.steps.errors.moderationOff')
      return
    }
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await tenantError(e)
    if (code === 'statement_required') deleteOverContent.value = true
    actionError.value =
      code === 'tenant_domain_mismatch' ? t('tenant.deleteDomainMismatch')
        : code === 'tenant_not_deletable' ? t('tenant.notDeletable')
          : code && SUSPEND_REFUSALS[code] ? t(SUSPEND_REFUSALS[code])
            : t('tenant.actionFailed')
  }
}

// Stop a pending deletion: back to SUSPENDED, then reactivated as usual. Not confirm-gated — it only ever keeps data.
async function stopDeletion() {
  actionError.value = ''
  try {
    await stopTenantDeletion(props.tenantId)
    toast.success(t('tenant.deletionStopped'))
    emit('changed')
    await reload()
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('tenant.actionFailed')
  }
}

// The owner invite of an ownerless tenant (ADR 0101 decision 9): to one of its members, ACTIVE tenants only. Mailed
// where the instance mail is set up; otherwise the link is the Server Owner's to hand over, as setup's invite is.
const ownerInviteEmail = ref('')
const ownerInvite = ref<{ email: string; mailed: boolean } | null>(null)
async function inviteOwner() {
  const address = ownerInviteEmail.value.trim()
  if (!tenant.value || address.length === 0) return
  actionError.value = ''
  try {
    const state = await inviteTenantOwner(props.tenantId, address)
    const mailed = state.delivery === 'SENT'
    ownerInvite.value = { email: state.ownerEmail, mailed }
    inviteUrl.value = mailed ? '' : buildInviteLink(state.bootstrapSecret, tenant.value.domain)
    ownerInviteEmail.value = ''
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await tenantError(e)
    actionError.value =
      code === 'not_tenant_member' ? t('tenant.notTenantMember')
        : code === 'tenant_not_active' ? t('tenant.ownerInviteNotActive')
          : t('tenant.actionFailed')
  }
}

async function copyInviteLink() {
  copied.value = await copyText(inviteUrl.value)
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <p v-if="loading" class="settings-muted">{{ t('tenant.loading') }}</p>
    <Message v-else-if="loadError === 'notAuthorized'" severity="warn" :closable="false">{{ t('tenant.notAuthorized') }}</Message>
    <Message v-else-if="loadError === 'gone'" severity="warn" :closable="false">{{ t('tenant.empty') }}</Message>
    <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('tenant.loadFailed') }}</Message>

    <section v-else-if="tenant" class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ tenant.name }}</h2>
          <p v-if="ownerless">{{ tenant.domain }}</p>
          <p v-else>{{ tenant.domain }} · {{ tenant.ownerEmail }}</p>
        </div>
        <span class="veyq-badge" :class="statusTone">{{ statusLabel }}</span>
      </div>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
      <Message v-if="isSuspended" severity="warn" :closable="false">{{ t('tenant.suspendedHint') }}</Message>

      <!-- Pending deletion (uc.admin.delete-tenant): offline, erased at its due date unless stopped here. Stopping
           returns it SUSPENDED, so it is then brought back online with the lever as any suspended tenant. -->
      <template v-if="isPendingDeletion">
        <Message severity="error" :closable="false" class="tenant-pending-deletion">
          {{ t('tenant.pendingDeletionHint', { date: deletionDue }) }}
        </Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('tenant.stopDeletion')" icon="pi pi-undo" @click="stopDeletion" />
        </div>
      </template>

      <!-- No owner (ADR 0101 decision 9): its two ways on are the owner invite below and the deletion at the end. -->
      <Message v-if="ownerless" severity="warn" :closable="false" class="tenant-ownerless">
        {{ t('tenant.ownerlessHint') }}
        <template v-if="isSuspended"> {{ t('tenant.ownerlessSuspendedHint') }}</template>
      </Message>

      <!-- The availability lever (ADR 0066), offered only on a settled tenant: a PENDING one has nothing to take
           offline — it is withdrawn with revoke instead, which is why these two states share no controls. -->
      <template v-if="isActive">
        <label class="tenant-over-content">
          <input v-model="overContent" type="checkbox" class="tenant-over-content__toggle" />
          {{ t('tenant.suspendOverContent') }}
        </label>
        <small class="settings-muted">{{ t('tenant.suspendOverContentHint') }}</small>
        <StepStatementFields v-if="overContent" v-model="draft" id-prefix="tenant-statement" />
      </template>
      <div v-if="isActive || isSuspended" class="veyq-dialog-action-row">
        <Button
          v-if="isSuspended"
          type="button"
          :label="t('tenant.reactivate')"
          icon="pi pi-play"
          @click="setSuspended(false)"
        />
        <Button
          v-else
          type="button"
          :label="overContent ? t('tenant.suspendSend') : t('tenant.suspend')"
          icon="pi pi-pause"
          severity="danger"
          outlined
          class="tenant-suspend"
          @click="setSuspended(true)"
        />
      </div>

      <!-- Change address, on PENDING and ACTIVE alike (unlike the invite controls below, which are PENDING-only).
           Hidden while SUSPENDED: the backend refuses it there, so offering it would only produce an error. -->
      <form v-if="isPending || isActive" class="veyq-form change-domain" @submit.prevent="changeDomain">
        <label>
          {{ t('tenant.changeDomainTitle') }}
          <InputText v-model="domain" autocomplete="off" required />
        </label>
        <p class="settings-muted">{{ t('tenant.changeDomainHint') }}</p>
        <div class="veyq-dialog-action-row">
          <Button
            type="submit"
            :label="t('tenant.changeDomainSubmit')"
            icon="pi pi-arrow-right"
            severity="secondary"
            outlined
            :disabled="domain.trim().length === 0 || domain.trim().toLowerCase() === tenant.domain"
          />
        </div>
      </form>

      <!-- A PENDING tenant's owner invite can be re-pointed (reassign), resent, or revoked; ACTIVE tenants are
           settled and carry no invite controls here. -->
      <template v-if="isPending">
        <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="reassign">
          <label>
            {{ t('tenant.ownerEmail') }}
            <InputText v-model="email" type="email" autocomplete="email" required />
          </label>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('tenant.resend')" icon="pi pi-send" severity="secondary" outlined :loading="busy" @click="resend" />
            <div class="veyq-action-group">
              <Button type="button" :label="t('tenant.revoke')" icon="pi pi-trash" severity="danger" outlined :disabled="busy" @click="revoke" />
              <Button type="submit" :label="t('tenant.reassignSubmit')" :loading="busy" :disabled="email.trim().length === 0" />
            </div>
          </div>
        </form>

        <div v-else class="veyq-form">
          <Message severity="success" :closable="false">{{ t('tenant.inviteReissued') }}</Message>
          <p class="settings-muted">{{ t('members.copyLinkIntro') }}</p>
          <div class="invite-link-row">
            <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
            <Button :icon="copied ? 'pi pi-check' : 'pi pi-copy'" :aria-label="t('members.copyLink')" :title="copied ? t('members.copied') : t('members.copyLink')" @click="copyInviteLink" />
            <Button icon="pi pi-times" severity="secondary" outlined :aria-label="t('common.done')" :title="t('common.done')" @click="inviteUrl = ''" />
          </div>
        </div>
      </template>

      <!-- The owner invite of an ownerless ACTIVE tenant — to one of its members only (the server refuses anyone else
           with not_tenant_member). Mailed where instance mail is set up; else the link, to hand over as setup's. -->
      <template v-if="ownerless && isActive">
        <form v-if="!ownerInvite" class="veyq-form tenant-owner-invite" @submit.prevent="inviteOwner">
          <h3>{{ t('tenant.inviteOwnerTitle') }}</h3>
          <label>
            {{ t('tenant.inviteOwnerEmail') }}
            <InputText v-model="ownerInviteEmail" type="email" autocomplete="off" required />
          </label>
          <div class="veyq-dialog-action-row">
            <Button type="submit" :label="t('tenant.inviteOwner')" icon="pi pi-user-plus" :disabled="ownerInviteEmail.trim().length === 0" />
          </div>
        </form>
        <div v-else class="veyq-form tenant-owner-invite">
          <Message v-if="ownerInvite.mailed" severity="success" :closable="false">{{ t('tenant.ownerInviteMailed', { email: ownerInvite.email }) }}</Message>
          <template v-else>
            <p class="settings-muted">{{ t('tenant.ownerInviteCopy', { email: ownerInvite.email }) }}</p>
            <div class="invite-link-row">
              <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
              <Button :icon="copied ? 'pi pi-check' : 'pi pi-copy'" :aria-label="t('members.copyLink')" :title="copied ? t('members.copied') : t('members.copyLink')" @click="copyInviteLink" />
            </div>
          </template>
          <div class="veyq-dialog-action-row">
            <Button type="button" :label="t('common.done')" severity="secondary" outlined @click="ownerInvite = null; inviteUrl = ''" />
          </div>
        </div>
      </template>

      <!-- Storage (uc.admin.tenant-storage-quota): one total per tenant beside its quota, never a figure per member or
           space. Offered in every status — a suspended tenant keeps its quota (ADR 0073). -->
      <form class="veyq-form tenant-storage" @submit.prevent="saveQuota(false)">
        <p class="tenant-storage__figures">
          <span>{{ t('tenant.storageUsed') }} <strong>{{ storageUsed }}</strong></span>
          <span>{{ t('tenant.quota') }} <strong>{{ storageQuota }}</strong></span>
        </p>
        <label>
          {{ t('tenant.quotaInput') }}
          <InputText v-model="quotaGib" inputmode="decimal" autocomplete="off" />
        </label>
        <p class="settings-muted">{{ t('tenant.quotaHint') }}</p>
        <div class="veyq-dialog-action-row">
          <Button
            v-if="tenant.storageQuotaBytes != null"
            type="button"
            :label="t('tenant.quotaClear')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="saveQuota(true)"
          />
          <Button type="submit" :label="t('tenant.quotaSave')" icon="pi pi-save" severity="secondary" outlined />
        </div>
      </form>

      <!-- Delete (uc.admin.delete-tenant), on an ACTIVE or SUSPENDED tenant — a PENDING one is revoked instead. Armed only
           once the tenant's domain is typed: the typed domain is the confirmation, and the server checks it again. -->
      <form v-if="isActive || isSuspended" class="veyq-form tenant-delete" @submit.prevent="removeTenant">
        <h3>{{ t('tenant.deleteTitle') }}</h3>
        <p class="settings-muted">{{ t('tenant.deleteHint') }}</p>
        <label>
          {{ t('tenant.deleteConfirmLabel', { domain: tenant.domain }) }}
          <InputText v-model="deleteTyped" autocomplete="off" spellcheck="false" />
        </label>
        <label class="tenant-over-content">
          <input v-model="deleteOverContent" type="checkbox" class="tenant-delete-over-content__toggle" />
          {{ t('tenant.suspendOverContent') }}
        </label>
        <small class="settings-muted">{{ t('tenant.deleteOverContentHint') }}</small>
        <StepStatementFields v-if="deleteOverContent" v-model="deleteDraft" id-prefix="tenant-delete-statement" />
        <div class="veyq-dialog-action-row">
          <Button type="submit" :label="t('tenant.delete')" icon="pi pi-trash" severity="danger" :disabled="!deleteArmed" />
        </div>
      </form>
    </section>
  </section>
</template>

<style scoped>
.tenant-owner-invite h3,
.tenant-delete h3 {
  margin: 0;
  font-size: 1rem;
}
.tenant-storage__figures {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1.5rem;
  margin: 0;
}
</style>
