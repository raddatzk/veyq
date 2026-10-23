<script setup lang="ts">
// Create a named group (en.spaces.group-scope, cr-0032). Ported dialog shape (the core/node/FolderNameDialog form);
// wired to the E2EE spaces data layer — the name is sealed under a fresh K_SC client-side, only ciphertext
// reaches the server. On success it emits the new scope id so the shell can switch into `#@<id>`.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { createGroup } from './scopes'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (no two-way `visible`).
const emit = defineEmits<{ done: [scopeId: string]; close: [] }>()
const { t } = useI18n()

const name = ref('')
const error = ref('')

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const scopeId = await createGroup(name.value.trim())
    emit('done', scopeId)
    emit('close')
  },
  { onError: () => { error.value = t('groups.createFailed') } },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('groups.createTitle')" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('groups.nameLabel') }}
        <VeyqInput v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>

      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.create')" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>
