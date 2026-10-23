<script setup lang="ts">
// The drop box's `createDialog` (cr-files-application F3): a name, then core's node create with Depot's kind. The markup
// is core's `FolderNameDialog` (its form classes and labels, its "Create"); a drop box takes guests once a link is made
// for it, which the dialog says before the name is asked (inbox #385). A name that collides with a sibling in the loaded
// tree is refused before sending, naming that sibling (core's `siblingCollision.ts`, inbox #389); the server's 409 is the
// race backstop, said as core's neutral `node.slugTaken`.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { NodeCreateDialogEmits, NodeCreateDialogProps } from '../../../core/applications/registry'
import { createDropBox } from '../dropBox'
import { DROPBOX_KIND } from '../kinds'
import { treeStore } from '../../../core/node/treeStore'
import { collidingSibling, nameTakenMessage } from '../../../core/node/siblingCollision'
import { httpStatus, HttpStatus } from '../../../shared/httpError'

const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t } = useI18n()

const name = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const canSubmit = computed(() => !loading.value && name.value.trim().length > 0)

async function submit() {
  if (!canSubmit.value) return
  error.value = null
  const sibling = collidingSibling(treeStore.nodes, props.parentId ?? null, DROPBOX_KIND, name.value.trim())
  if (sibling) {
    error.value = nameTakenMessage(t, sibling)
    return
  }
  loading.value = true
  try {
    const parent = props.parentId && props.parentKey ? { id: props.parentId, key: props.parentKey } : null
    await createDropBox(props.scopeId, props.epochs, parent, name.value.trim())
    emit('done')
    emit('close')
  } catch (e) {
    error.value = httpStatus(e) === HttpStatus.conflict ? t('node.slugTaken') : t('depot.dropBox.createFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('depot.dropBox.createTitle')" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('depot.dropBox.createHint') }}</p>
      <label>
        {{ t('node.nameLabel') }}
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
