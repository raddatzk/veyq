<script setup lang="ts">
// Import a veyq download under the folder on screen (ADR 0082, uc.platform.export-import): a member flow. The input - a ZIP,
// or the download unpacked into a directory - is read by content (`openExport`), and the DRY RUN (`planImport`) says, before
// anything is written, what would be created, how big it is and what this build leaves out, per application (decision 9: an
// application this instance does not have, or newer content). A damaged or tampered download is refused here with its
// reason (decision 10). Recognised from an upload, a ZIP also offers "Upload as a file" (decision 7). "Import" - "Import the
// rest" when something is left out - creates the rest; Cancel writes nothing; an archive with nothing importable offers only
// Cancel. A failure trashes what was created and says how far it got; a success reloads the tree and names what was left out.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqProgress from '../../shared/ui/VeyqProgress.vue'
import type { Access } from '../access'
import type { NodeParent } from '../node/tree'
import { loadTree, treeStore } from '../node/treeStore'
import { formatBytes } from '../../shared/formatBytes'
import { useToast } from '../../shared/state/useToast'
import { activeSpace } from '../../app/activeSpace'
import { tenantStorageFits } from '../../features/tenant/tenantStorage'
import { ExportError } from './manifest'
import type { ImportInput } from './importSource'
import { ImportFailed, importSubtree, openExport, planImport, type ImportPlan, type OpenedExport } from './importSubtree'

const props = defineProps<{
  input: ImportInput
  access: Access
  parent: NodeParent
  targetName: string
  /** Present when the input came from an upload (a recognised ZIP): "Upload as a file" hands it back to the upload. */
  uploadAsFile?: () => void
}>()
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

// Whether the import would fit the tenant's quota (ADR 0082): the server's yes or no, asked once the dry run is known. `null`:
// not asked, or not answerable - the upload then decides, so an unanswerable question never blocks an import.
const fits = ref<boolean | null>(null)

const size = computed(() => formatBytes(plan.value?.bytes ?? 0, locale.value))
const rootName = computed(() => opened.value?.manifest.root.name ?? '')
const leavesOut = computed(() => (plan.value?.skipped.length ?? 0) > 0)
const canImport = computed(() => (plan.value?.nodes ?? 0) > 0)

/** Why a download is refused, in the member's words; `detail` (a path, a node's name) is shown beside it. */
function reasonText(e: unknown): string {
  if (!(e instanceof ExportError)) return t('export.invalid')
  const text = t(`export.refused.${e.code}`)
  return e.detail ? `${text} (${e.detail})` : text
}

onMounted(async () => {
  try {
    opened.value = await openExport(props.input)
    plan.value = planImport(opened.value.manifest)
    step.value = 'ready'
    if (activeSpace.tenantId && plan.value.bytes > 0) fits.value = await tenantStorageFits(activeSpace.tenantId, plan.value.bytes)
  } catch (e) {
    error.value = reasonText(e)
    step.value = 'invalid'
  }
})

async function run() {
  if (props.access.mode !== 'member' || !props.access.epochs || !opened.value || !canImport.value) return
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
    toast.success(
      result.skipped.length > 0
        ? t('export.importedLeftOut', { name: rootName.value, names: result.skipped.map((s) => s.name).join(', ') })
        : t('export.imported', { name: rootName.value }),
    )
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
  return reasonText(e)
}

function uploadInstead() {
  control.abort()
  props.uploadAsFile?.()
  emit('close')
}

function close() {
  control.abort()
  emit('close')
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('export.importTitle')" class="import-dialog" :closable="step !== 'running'" keep-on-outside @update:visible="close">
    <form class="veyq-form" data-testid="import-dialog" @submit.prevent="run">
      <p v-if="step === 'reading'" class="veyq-form-copy" role="status">{{ t('export.reading') }}</p>
      <template v-else-if="plan && step !== 'invalid'">
        <p class="veyq-form-copy" data-testid="import-summary">
          <strong>{{ rootName }}</strong><br />
          <template v-if="canImport">{{ t('export.importSummary', { nodes: plan.nodes, size, target: targetName }) }}</template>
          <template v-else>{{ t('export.importNothing') }}</template>
        </p>
        <div v-if="leavesOut" data-testid="import-skipped">
          <p class="veyq-form-copy">{{ t('export.importSkipped') }}</p>
          <ul class="import-skipped">
            <li v-for="g in plan.groups" :key="`${g.application}:${g.reason}`" :data-reason="g.reason">
              {{ t(`export.skipReason.${g.reason}`, { application: g.application, n: g.nodes }, g.nodes) }}
            </li>
          </ul>
        </div>
        <VeyqMessage v-if="fits === false" tone="warn" role="alert" data-testid="import-no-room">{{ t('export.importNoRoom', { size }) }}</VeyqMessage>
        <p v-if="canImport" class="veyq-form-copy import-note">{{ t('export.importNotice') }}</p>
        <template v-if="step === 'running'">
          <p class="veyq-form-copy" role="status">{{ t('export.importing', { done: progress.nodesDone, total: progress.nodesTotal }) }}</p>
          <VeyqProgress :value="Math.round((progress.nodesDone / Math.max(1, progress.nodesTotal)) * 100)" />
        </template>
      </template>
      <VeyqMessage v-if="error" tone="error" role="alert" data-testid="import-error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="step === 'running'" data-testid="import-cancel" @click="close" />
        <VeyqButton
          v-if="uploadAsFile"
          :label="t('export.uploadAsFile')"
          icon="file"
          variant="secondary"
          :disabled="step === 'running'"
          data-testid="import-upload-as-file"
          @click="uploadInstead"
        />
        <VeyqButton
          v-if="step !== 'invalid' && (step === 'reading' || canImport)"
          type="submit"
          :label="leavesOut ? t('export.importRest') : t('export.importSubmit')"
          icon="upload"
          :disabled="step === 'reading' || fits === false"
          :loading="step === 'running'"
          data-testid="import-submit"
        />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.import-dialog {
  max-width: min(30rem, 92vw);
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
