<script setup lang="ts">
// Create a folder / create a Memory / rename a node (cr-0030 P4). Ported dialog shape; wired to the E2EE
// tree data layer (names sealed under K_SC client-side). One dialog, three modes. Inline slug_taken (409) /
// illegal_structure (422) errors.
//
// create-memory can **pre-arm a location** (cr-0069) behind the personal maps opt-in — but at creation there are
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
import { createFolder, renameNode, type TreeNode } from '../../../core/node/tree'
import { createMemory, createChapter } from '../memoryNodes'
import type { EpochKeys } from '../../spaces/epochs'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { httpStatus, HttpStatus } from '../../../shared/httpError'

type Mode = 'create-folder' | 'create-memory' | 'create-chapter' | 'rename'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
const props = defineProps<{
  mode: Mode
  scopeId: string
  epochs: EpochKeys // the scope's epoch key set (ADR 0024): create seals under the write epoch, rename under the node's own
  parentId?: string | null // create: the parent (null = root)
  parentKey?: Uint8Array | null // create-chapter: the parent node's K_mem (wraps the new chapter's K_mem)
  node?: TreeNode | null // rename: the node being renamed
}>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t } = useI18n()
const { mapsAvailable } = useMapConfig()

const name = ref(props.mode === 'rename' ? (props.node?.name ?? '') : '')
const error = ref('')
const wantsLocation = ref(false) // opt into a pending `auto` place (create-memory, behind the maps opt-in)
const showLocationStep = computed(() => props.mode === 'create-memory' && mapsAvailable.value)

const header = computed(() =>
  props.mode === 'rename'
    ? t('memory.renameTitle')
    : props.mode === 'create-memory'
      ? t('memory.createMemoryTitle')
      : props.mode === 'create-chapter'
        ? t('memory.createChapterTitle')
        : t('memory.createFolderTitle'),
)
const confirmLabel = computed(() => (props.mode === 'rename' ? t('memory.save') : t('common.create')))

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const trimmed = name.value.trim()
    if (props.mode === 'create-folder') {
      await createFolder(props.scopeId, props.epochs, trimmed, props.parentId ?? null)
    } else if (props.mode === 'create-memory') {
      const location = showLocationStep.value && wantsLocation.value ? { name: '', nameSource: 'geocoded' as const, derivedMode: 'auto' as const } : null
      await createMemory(props.scopeId, props.epochs, trimmed, props.parentId ?? null, location)
    } else if (props.mode === 'create-chapter') {
      if (!props.parentId || !props.parentKey) throw new Error('chapter_parent_missing')
      await createChapter(props.scopeId, props.epochs, props.parentKey, trimmed, props.parentId)
    } else if (props.node) {
      await renameNode(props.scopeId, props.epochs, { id: props.node.id, parentId: props.node.parentId, epoch: props.node.epoch }, trimmed)
    }
    emit('done')
    emit('close')
  },
  {
    onError: (e) => {
      const status = httpStatus(e)
      error.value = status === HttpStatus.conflict ? t('memory.slugTaken') : status === HttpStatus.unprocessable ? t('memory.illegalStructure') : t('memory.saveFailed')
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
        {{ t('memory.nameLabel') }}
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
