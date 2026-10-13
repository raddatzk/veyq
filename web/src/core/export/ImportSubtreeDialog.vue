<script setup lang="ts">
// Import a veyq export under the folder on screen (ADR 0082, uc.node.import): a member flow. The file is read by content
// (`openExport`), the dry run tells what would be created and how big it is, and the member confirms. A failure trashes what
// was created and says how far it got; a success reloads the tree. The archive is a `File` read by slice, never held.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import type { Access } from '../access'
import type { NodeParent } from '../node/tree'
import { loadTree, treeStore } from '../node/treeStore'
import { formatBytes } from '../../shared/formatBytes'
import { useToast } from '../../shared/state/useToast'
import { ExportError } from './manifest'
import { ImportFailed, importSubtree, openExport, planImport, type ImportPlan, type OpenedExport } from './importSubtree'

const props = defineProps<{ file: Blob; access: Access; parent: NodeParent; targetName: string }>()
const emit = defineEmits<{ close: []; done: [nodeId: string] }>()
const { t, locale } = useI18n()
const toast = useToast()

type Step = 'reading' | 'ready' | 'running' | 'invalid'
const step = ref<Step>('reading')
const opened = shallowRef<OpenedExport | null>(null)
const plan = ref<ImportPlan | null>(null)
const error = ref('')
const progress = ref({ nodesDone: 0, nodesTotal: 1 })
const control = new AbortController()
onBeforeUnmount(() => control.abort())

const size = computed(() => formatBytes(plan.value?.bytes ?? 0, locale.value))
const rootName = computed(() => opened.value?.manifest.root.name ?? '')

onMounted(async () => {
  try {
    opened.value = await openExport(props.file)
    plan.value = planImport(opened.value.manifest)
    step.value = 'ready'
  } catch (e) {
    error.value = e instanceof ExportError ? (e.code === 'not_an_export' ? t('export.notExport') : e.code === 'newer_version' ? t('export.newer') : t('export.invalid')) : t('export.invalid')
    step.value = 'invalid'
  }
})

async function run() {
  if (props.access.mode !== 'member' || !props.access.epochs || !opened.value) return
  error.value = ''
  step.value = 'running'
  try {
    const result = await importSubtree({
      scopeId: props.access.scopeId,
      epochs: props.access.epochs,
      tree: treeStore.nodes,
      parent: props.parent,
      opened: opened.value,
      signal: control.signal,
      onProgress: (p) => (progress.value = { nodesDone: p.nodesDone, nodesTotal: p.nodesTotal }),
    })
    await loadTree()
    toast.success(t('export.imported', { name: rootName.value }))
    emit('done', result.nodeId)
    emit('close')
  } catch (e) {
    await loadTree().catch(() => {})
    step.value = 'ready'
    if (control.signal.aborted) return
    error.value = failureText(e)
  }
}

function failureText(e: unknown): string {
  if (e instanceof ImportFailed) {
    const c = e.cause instanceof Error ? e.cause.message : ''
    if (c === 'quota_exceeded') return `${t('export.importQuota')} ${t('export.importFailed', { done: e.done, total: e.total })}`
    if (e.cause instanceof ExportError && e.cause.code === 'checksum') return `${t('export.importChecksum')} ${t('export.importFailed', { done: e.done, total: e.total })}`
    return e.rolledBack
      ? t('export.importFailed', { done: e.done, total: e.total })
      : t('export.importFailedLeft', { done: e.done, total: e.total, name: rootName.value })
  }
  return e instanceof ExportError && e.code === 'nothing_to_import' ? t('export.invalid') : t('export.invalid')
}

function close() {
  control.abort()
  emit('close')
}
</script>

<template>
  <Dialog :visible="true" :header="t('export.importTitle')" modal class="import-dialog" :closable="step !== 'running'" @update:visible="close">
    <form class="veyq-form" data-testid="import-dialog" @submit.prevent="run">
      <p v-if="step === 'reading'" class="veyq-form-copy" role="status">{{ t('export.reading') }}</p>
      <template v-else-if="plan && step !== 'invalid'">
        <p class="veyq-form-copy" data-testid="import-summary">
          <strong>{{ rootName }}</strong><br />{{ t('export.importSummary', { nodes: plan.nodes, size, target: targetName }) }}
        </p>
        <div v-if="plan.skipped.length > 0" data-testid="import-skipped">
          <p class="veyq-form-copy">{{ t('export.importSkipped') }}</p>
          <ul class="import-skipped"><li v-for="(s, i) in plan.skipped" :key="i">{{ s.name }} <small>({{ s.kind }})</small></li></ul>
        </div>
        <p class="veyq-form-copy import-note">{{ t('export.importNotice') }}</p>
        <template v-if="step === 'running'">
          <p class="veyq-form-copy" role="status">{{ t('export.importing', { done: progress.nodesDone, total: progress.nodesTotal }) }}</p>
          <ProgressBar :value="Math.round((progress.nodesDone / Math.max(1, progress.nodesTotal)) * 100)" />
        </template>
      </template>
      <Message v-if="error" severity="error" :closable="false" role="alert" data-testid="import-error">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="step === 'running'" @click="close" />
        <Button v-if="step !== 'invalid'" type="submit" :label="t('export.importSubmit')" icon="pi pi-upload" :disabled="step === 'reading'" :loading="step === 'running'" data-testid="import-submit" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.import-dialog {
  max-width: min(28rem, 92vw);
}
.import-skipped {
  margin: 0;
  padding-inline-start: 1.1rem;
  color: var(--app-muted);
}
.import-note {
  color: var(--app-muted);
}
</style>
