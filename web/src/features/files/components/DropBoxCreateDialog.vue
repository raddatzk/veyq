<script setup lang="ts">
// The drop box's `createDialog` (cr-files-application F3): a name, then core's node create with Files' kind. The markup
// is core's `FolderNameDialog` (its form classes and labels, its "Create"); a drop box takes guests once a link is made
// for it, which the dialog says before the name is asked (inbox #385).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import type { NodeCreateDialogEmits, NodeCreateDialogProps } from '../../../core/applications/registry'
import { createDropBox } from '../dropBox'

const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t } = useI18n()

const name = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const canSubmit = computed(() => !loading.value && name.value.trim().length > 0)

async function submit() {
  if (!canSubmit.value) return
  loading.value = true
  error.value = null
  try {
    const parent = props.parentId && props.parentKey ? { id: props.parentId, key: props.parentKey } : null
    await createDropBox(props.scopeId, props.epochs, parent, name.value.trim())
    emit('done')
    emit('close')
  } catch {
    error.value = t('files.dropBox.createFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog :visible="true" :header="t('files.dropBox.createTitle')" modal :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('files.dropBox.createHint') }}</p>
      <label>
        {{ t('node.nameLabel') }}
        <InputText v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="loading" @click="emit('close')" />
        <Button type="submit" :label="t('common.create')" icon="pi pi-check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>
