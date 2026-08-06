<script setup lang="ts">
// uc.admin.create-tenant (web, cr-0087) — the per-tenant detail page of the Server settings: one tenant's status
// and, for a PENDING one, its owner-invite management (reassign email / resend / revoke). Addressed by tenantId
// (the Tenants collection in the settings rail routes here); mirrors the old TenantAdminPanel row + edit, scoped
// to a single tenant. Operator-blind: only operational metadata (name/domain/owner email/status). The backend
// enforces Server-Owner with a 403 regardless of what the client renders.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { listTenants, reassignInvite, resendInvite, revokeTenant, type TenantSummary } from './tenants'
import { buildInviteLink, copyText } from '../../../shared/inviteLink'
import { useAsyncData, loadErrorKind } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useToast } from '../../../shared/state/useToast'
import { handle401 } from '../../../shared/handle401'
import { isUnauthorized } from '../../../shared/httpError'

// Addressed by id (cr-0087) — the settings rail renders one panel per tenant; the tenantId watch re-loads on switch.
const props = defineProps<{ tenantId: string }>()

const { t } = useI18n()
const toast = useToast()
const router = useRouter()
const on401 = handle401(router)

const tenant = ref<TenantSummary | null>(null)
const actionError = ref('')

const email = ref('')
// After a resend/reassign, show the fresh invite link to copy (cr-0080 — the owner hands it off, no mailer).
const inviteUrl = ref('')
const copied = ref(false)

const isPending = computed(() => tenant.value?.status === 'PENDING')

const { pending: loading, error: loadErr, reload } = useAsyncData(async () => {
  actionError.value = ''
  inviteUrl.value = ''
  const found = (await listTenants()).find((x) => x.tenantId === props.tenantId) ?? null
  tenant.value = found
  if (found) email.value = found.ownerEmail
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

async function revoke() {
  if (!window.confirm(t('tenant.revokeConfirm'))) return
  actionError.value = ''
  try {
    await revokeTenant(props.tenantId)
    await router.push('/settings') // the tenant is gone → drop back to the hub default
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) actionError.value = t('tenant.actionFailed')
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
          <p>{{ tenant.domain }} · {{ tenant.ownerEmail }}</p>
        </div>
        <span class="veyq-badge" :class="isPending ? 'warning' : 'success'">
          {{ isPending ? t('tenant.statusPending') : t('tenant.statusActive') }}
        </span>
      </div>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

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
    </section>
  </section>
</template>

<style scoped>
</style>
