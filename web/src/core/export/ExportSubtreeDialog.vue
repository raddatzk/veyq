<script setup lang="ts">
// Export a node and everything under it (ADR 0082, uc.node.export). Three beats: the member chooses (and is warned that the
// file is plain), the client walks the subtree and says what is in it, and the member picks where to save it - the save
// picker wants the click's user activation, so it is the first thing the save click awaits, and everything slow
// (the walk) happens before it. The bytes then stream to the chosen file; closing the dialog while it writes cancels it.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import type { Access } from '../access'
import type { TreeNode } from '../node/tree'
import { treeStore } from '../node/treeStore'
import { formatBytes } from '../../shared/formatBytes'
import { SaveTooLarge, isSaveCancelled, openSaveTarget } from '../../shared/saveStream'
import { useToast } from '../../shared/state/useToast'
import { prepareExport, safeFileName, type PreparedExport } from './exportSubtree'
import { ExportError } from './manifest'

const props = defineProps<{ node: TreeNode; access: Access; displayName: string }>()
const emit = defineEmits<{ close: [] }>()
const { t, locale } = useI18n()
const toast = useToast()

type Step = 'options' | 'preparing' | 'ready' | 'writing'
const step = ref<Step>('options')
const includeLocation = ref(true)
const prepared = ref<PreparedExport | null>(null)
const error = ref('')
const fraction = ref(0)
const control = new AbortController()
onBeforeUnmount(() => control.abort())

const busy = computed(() => step.value === 'preparing' || step.value === 'writing')
const size = computed(() => formatBytes(prepared.value?.bytes ?? 0, locale.value))
const fileName = computed(() => `${safeFileName(props.displayName)}.zip`)

async function prepare() {
  if (props.access.mode !== 'member' || !props.access.epochs) return
  error.value = ''
  step.value = 'preparing'
  try {
    prepared.value = await prepareExport({
      scopeId: props.access.scopeId,
      epochs: props.access.epochs,
      tree: treeStore.nodes,
      rootId: props.node.id,
      options: { includeLocation: includeLocation.value },
      appVersion: __APP_VERSION__,
      signal: control.signal,
    })
    step.value = 'ready'
  } catch (e) {
    if (control.signal.aborted) return
    error.value = e instanceof ExportError && e.code === 'invalid_manifest' ? t('export.nothing') : t('export.failed')
    step.value = 'options'
  }
}

async function save() {
  const ready = prepared.value
  if (!ready) return
  error.value = ''
  try {
    // FIRST await of the click: the save picker needs its user activation
    const sink = await openSaveTarget(fileName.value, ready.bytes)
    step.value = 'writing'
    await ready.write(sink, (p) => {
      fraction.value = p.bytesTotal > 0 ? p.bytesDone / p.bytesTotal : 1
    })
    toast.success(t('export.done'))
    emit('close')
  } catch (e) {
    step.value = 'ready'
    if (isSaveCancelled(e) || control.signal.aborted) return
    error.value = e instanceof SaveTooLarge ? t('export.tooLarge') : t('export.failed')
  }
}

function close() {
  if (busy.value) control.abort()
  emit('close')
}
</script>

<template>
  <Dialog :visible="true" :header="t('export.title', { name: displayName })" modal class="export-dialog" @update:visible="close">
    <form class="veyq-form" data-testid="export-dialog" @submit.prevent="step === 'options' ? prepare() : save()">
      <template v-if="step === 'options'">
        <Message severity="warn" :closable="false" data-testid="export-warning">{{ t('export.warning') }}</Message>
        <label class="export-option">
          <Checkbox v-model="includeLocation" binary input-id="export-location" data-testid="export-location" />
          <span>
            {{ t('export.includeLocation') }}
            <small class="export-hint">{{ t('export.includeLocationHint') }}</small>
          </span>
        </label>
      </template>
      <p v-else-if="step === 'preparing'" class="veyq-form-copy" role="status">{{ t('export.preparing') }}</p>
      <template v-else-if="prepared">
        <p class="veyq-form-copy" data-testid="export-summary">{{ t('export.summary', { nodes: prepared.nodes, files: prepared.payloads, size }) }}</p>
        <div v-if="prepared.skipped.length > 0" data-testid="export-skipped">
          <p class="veyq-form-copy">{{ t('export.skipped') }}</p>
          <ul class="export-skipped">
            <li v-for="(s, i) in prepared.skipped" :key="i">{{ s.name }} <small>({{ t(`export.reason.${s.reason}`) }})</small></li>
          </ul>
        </div>
        <template v-if="step === 'writing'">
          <p class="veyq-form-copy" role="status">{{ t('export.writing') }}</p>
          <ProgressBar :value="Math.round(fraction * 100)" />
        </template>
      </template>
      <Message v-if="error" severity="error" :closable="false" role="alert">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="close" />
        <Button v-if="step === 'options' || step === 'preparing'" type="submit" :label="t('export.prepare')" icon="pi pi-arrow-right" :loading="step === 'preparing'" data-testid="export-prepare" />
        <Button v-else type="submit" :label="t('export.save')" icon="pi pi-download" :loading="step === 'writing'" data-testid="export-save" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.export-dialog {
  max-width: min(28rem, 92vw);
}
.export-option {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  cursor: pointer;
}
.export-option :deep(.p-checkbox) {
  flex: none;
  margin-top: 0.15rem;
}
.export-hint {
  display: block;
  color: var(--app-muted);
}
.export-skipped {
  margin: 0;
  padding-inline-start: 1.1rem;
  color: var(--app-muted);
}
</style>
