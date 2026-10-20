<script setup lang="ts">
// Create a tenant shell + owner invite (uc.admin.create-tenant, Slice A). Ported from the legacy dialog;
// the data layer is swapped to the generated tenants client. Creating the shell never makes the current
// Server Owner a tenant member or key-holder — ownership is claimed only when the invite is opened (Slice B).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createTenant, tenantError } from './tenants'
import { buildInviteLink, copyText } from '../../shared/inviteLink'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

const visible = defineModel<boolean>('visible', { required: true })
// `storeBlocked`: the console knows no object store has passed its check (ADR 0089 decision 6). A courtesy over the
// server, which refuses with 409 `store_not_ready` on its own — so a stale flag costs a refusal, never a tenant.
const props = defineProps<{ storeBlocked?: boolean }>()
const emit = defineEmits<{ created: []; openStore: [] }>()
const { t } = useI18n()

const name = ref('')
const domain = ref('')
const ownerEmail = ref('')
const error = ref('')
const inviteUrl = ref('') // set on success → switches to the copy-link view (the owner hands the link off)
const copied = ref(false)
const canSubmit = computed(
  () => !props.storeBlocked && name.value.trim().length > 0 && domain.value.trim().length > 0 && ownerEmail.value.trim().length > 0,
)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    name.value = ''
    domain.value = ''
    ownerEmail.value = ''
    inviteUrl.value = ''
    copied.value = false
  }
})

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const normalizedDomain = domain.value.trim().toLowerCase()
    const created = await createTenant(name.value.trim(), normalizedDomain, ownerEmail.value.trim())
    inviteUrl.value = buildInviteLink(created.bootstrapSecret, normalizedDomain) // the owner lives on another host
    emit('created')
  },
  {
    onError: async (e) => {
      const { code } = await tenantError(e)
      error.value =
        code === 'domain_taken' ? t('tenant.domainTaken') : code === 'store_not_ready' ? t('tenant.storeNotReady') : t('tenant.createFailed')
      if (code === 'store_not_ready') emit('openStore')
    },
  },
)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}

async function copyLink() {
  copied.value = await copyText(inviteUrl.value)
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('tenant.create')" modal class="tenant-dialog" :closable="!loading" :close-on-escape="!loading">
    <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('tenant.name') }}
        <InputText v-model="name" autocomplete="off" required :disabled="loading" />
      </label>
      <label>
        {{ t('tenant.domain') }}
        <InputText v-model="domain" autocomplete="off" required :disabled="loading" />
        <small class="field-hint">{{ t('tenant.domainHint') }}</small>
      </label>
      <label>
        {{ t('tenant.ownerEmailNew') }}
        <InputText v-model="ownerEmail" type="email" autocomplete="email" required :disabled="loading" />
      </label>
      <Message v-if="storeBlocked" severity="warn" :closable="false" class="tenant-store-blocked">
        {{ t('tenant.storeNotReady') }}
        <Button
          type="button"
          text
          icon="pi pi-database"
          :label="t('tenant.openStore')"
          @click="emit('openStore')"
        />
      </Message>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="loading"
          @click="visible = false"
        />
        <Button type="submit" :label="t('common.create')" icon="pi pi-plus" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>

    <div v-else class="veyq-form">
      <Message severity="success" :closable="false">{{ t('tenant.created') }}</Message>
      <p class="field-hint">{{ t('members.copyLinkIntro') }}</p>
      <div class="invite-link-row">
        <InputText :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
        <Button
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          :aria-label="t('members.copyLink')"
          :title="copied ? t('members.copied') : t('members.copyLink')"
          @click="copyLink"
        />
      </div>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.done')" icon="pi pi-check" @click="visible = false" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.field-hint {
  display: block;
  margin: 0;
  opacity: 0.7;
  font-size: 0.85em;
}

</style>
