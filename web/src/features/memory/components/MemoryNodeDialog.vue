<script setup lang="ts">
// Create a Memory / create a chapter (cr-0030 P4, ADR 0080 Decision 13): Memories' `createDialog`, registered for both
// its kinds (cr-files-application P4c; the folder create and every rename are core's `core/node/FolderNameDialog.vue`,
// with this dialog's markup). Ported dialog shape; wired to the E2EE tree data layer (names sealed client-side under
// the parent's key, K_SC at the scope root — ADR 0080). The kind to create picks the mode. Inline slug_taken (409) /
// illegal_structure (422) errors.
//
// A Memory's create can **pre-arm a location** (cr-0069) behind the personal maps opt-in — but at creation there are
// no photos yet, so the heavy map/name belong later (on the open Memory, via the shared editor). Here it is a
// single opt-in: "Ort — folgt automatisch deinen Fotos" arms a **pending `auto`** place whose position + name are
// materialized by the first geotagged upload (the `MemoryDetail` self-heal). Sealed at birth into `wrappedMeta`.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import type { NodeParent } from '../../../core/node/tree'
import type { NodeCreateDialogProps, NodeCreateDialogEmits } from '../../../core/applications/registry'
import { createMemory, createChapter } from '../memoryNodes'
import { CHAPTER_KIND } from '../kinds'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { httpStatus, HttpStatus } from '../../../shared/httpError'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
// `kind`: MEMORY or CHAPTER (the kinds that register this dialog); a chapter is created inside the opened Memory.
const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t } = useI18n()
const { mapsAvailable } = useMapConfig()

const isChapter = computed(() => props.kind === CHAPTER_KIND)
const name = ref('')
const error = ref('')
const wantsLocation = ref(false) // opt into a pending `auto` place (create-memory, behind the maps opt-in)
const showLocationStep = computed(() => !isChapter.value && mapsAvailable.value)

const header = computed(() => (isChapter.value ? t('memory.createChapterTitle') : t('memory.createMemoryTitle')))
const confirmLabel = computed(() => t('common.create'))

/** Where a create lands: the scope root (wrapped under the write key), or under the parent with ITS key (ADR 0080
 *  Decision 1: a node inside a folder wraps under the folder's key, never `K_SC`). A parent whose key is missing
 *  creates nothing, rather than a node its parent's holders could not open. */
function createParent(): NodeParent {
  if (!props.parentId) return null
  if (!props.parentKey) throw new Error('parent_key_unavailable')
  return { id: props.parentId, key: props.parentKey }
}

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const trimmed = name.value.trim()
    if (isChapter.value) {
      if (!props.parentId || !props.parentKey) throw new Error('chapter_parent_missing')
      await createChapter(props.scopeId, props.epochs, props.parentKey, trimmed, props.parentId)
    } else {
      const location = showLocationStep.value && wantsLocation.value ? { name: '', nameSource: 'geocoded' as const, derivedMode: 'auto' as const } : null
      await createMemory(props.scopeId, props.epochs, trimmed, createParent(), location)
    }
    emit('done')
    emit('close')
  },
  {
    onError: (e) => {
      const status = httpStatus(e)
      error.value = status === HttpStatus.conflict ? t('node.slugTaken') : status === HttpStatus.unprocessable ? t('node.illegalStructure') : t('node.saveFailed')
    },
  },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}
</script>

<template>
  <Dialog :visible="true" :header="header" modal :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <InputText v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>

      <div v-if="showLocationStep" class="node-location-optin">
        <Checkbox v-model="wantsLocation" input-id="wants-location" binary :disabled="loading" />
        <label for="wants-location" class="node-location-optin-text">
          <span>{{ t('memory.place.autoOptIn') }}</span>
          <span class="node-location-optin-hint">{{ t('memory.place.autoOptInHint') }}</span>
        </label>
      </div>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="loading" @click="emit('close')" />
        <Button type="submit" :label="confirmLabel" icon="pi pi-check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.node-location-optin {
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
}
.node-location-optin-text {
  display: grid;
  gap: 0.12rem;
  font-size: 0.92rem;
  line-height: 1.35;
}
.node-location-optin-hint {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
