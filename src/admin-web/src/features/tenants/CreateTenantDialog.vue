<script setup lang="ts">
// Create a tenant shell + owner invite (uc.admin.create-tenant, Slice A). Ported from the legacy dialog;
// the data layer is swapped to the generated tenants client. Creating the shell never makes the current
// Server Owner a tenant member or key-holder — ownership is claimed only when the invite is opened (Slice B).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
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
  <VeyqDialog v-model:visible="visible" :header="t('tenant.create')" class="tenant-dialog" :closable="!loading" :close-on-escape="!loading">
    <form v-if="!inviteUrl" class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('tenant.name') }}
        <VeyqInput v-model="name" autocomplete="off" required :disabled="loading" />
      </label>
      <label>
        {{ t('tenant.domain') }}
        <VeyqInput v-model="domain" autocomplete="off" required :disabled="loading" />
        <small class="field-hint">{{ t('tenant.domainHint') }}</small>
      </label>
      <label>
        {{ t('tenant.ownerEmailNew') }}
        <VeyqInput v-model="ownerEmail" type="email" autocomplete="email" required :disabled="loading" />
      </label>
      <VeyqMessage v-if="storeBlocked" tone="warn" class="tenant-store-blocked">
        {{ t('tenant.storeNotReady') }}
        <VeyqButton
          type="button"
          appearance="text"
          icon="database"
          :label="t('tenant.openStore')"
          @click="emit('openStore')"
        />
      </VeyqMessage>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton
          type="button"
          :label="t('common.cancel')"
          icon="times"
          variant="secondary"
          appearance="outlined"
          :disabled="loading"
          @click="visible = false"
        />
        <VeyqButton type="submit" :label="t('common.create')" icon="plus" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>

    <div v-else class="veyq-form">
      <VeyqMessage tone="success">{{ t('tenant.created') }}</VeyqMessage>
      <p class="field-hint">{{ t('members.copyLinkIntro') }}</p>
      <div class="invite-link-row">
        <VeyqInput :model-value="inviteUrl" readonly class="invite-link-input" @focus="($event.target as HTMLInputElement).select()" />
        <VeyqButton
          :icon="copied ? 'check' : 'copy'"
          :aria-label="t('members.copyLink')"
          :title="copied ? t('members.copied') : t('members.copyLink')"
          @click="copyLink"
        />
      </div>
      <div class="veyq-dialog-action-row">
        <VeyqButton type="button" :label="t('common.done')" icon="check" @click="visible = false" />
      </div>
    </div>
  </VeyqDialog>
</template>

<style scoped>
.field-hint {
  display: block;
  margin: 0;
  opacity: 0.7;
  font-size: 0.85em;
}

</style>
