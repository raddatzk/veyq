<script setup lang="ts">
// The one small name dialog of a public share page (cr-node-key-hierarchy 5b.4b): a guest names a new folder with it,
// and names itself with it before its first write on a READ_WRITE link. The shape of the member's `FolderNameDialog`
// (PrimeVue Dialog + InputText + Message, `.veyq-form`, `.veyq-dialog-action-row`, `useAsyncAction`), without anything
// Memories adds there. What the name is FOR is the caller's: it hands the action and how a failure reads.
//
// Mounted via v-if while open; closing = the parent unmounting it on `close`.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

const props = defineProps<{
  title: string
  label: string
  submitLabel: string
  /** A line under the field (who reads a guest's name). */
  hint?: string
  /** Runs with the trimmed name; the dialog closes when it resolves. */
  action: (name: string) => Promise<void>
  /** The message a failed `action` shows. */
  errorText: (e: unknown) => string
}>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const name = ref('')
const error = ref('')
const { run, pending: saving } = useAsyncAction(
  async () => {
    await props.action(name.value.trim())
    emit('close')
  },
  { onError: (e) => void (error.value = props.errorText(e)) },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !saving.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await run()
}
</script>

<template>
  <Dialog :visible="true" :header="title" modal :closable="!saving" :close-on-escape="!saving" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ label }}
        <InputText v-model="name" autocomplete="off" required autofocus :disabled="saving" />
      </label>
      <p v-if="hint" class="veyq-form-copy">{{ hint }}</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="saving" @click="emit('close')" />
        <Button type="submit" :label="submitLabel" icon="pi pi-check" :loading="saving" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>
