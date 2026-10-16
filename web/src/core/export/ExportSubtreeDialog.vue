<script setup lang="ts">
// Download a node and everything under it as a ZIP (ADR 0082 decision 4, uc.platform.export-import): what "Download" on a
// folder, a Memory or a chapter opens, for a member and a READ / READ_WRITE share guest alike. It asks nothing: opened, it
// walks the subtree at once and says what is in it (items, files, bytes, what a hook left out), and "Save as ZIP…" picks
// where to save - the save picker wants the click's user activation, so it is the first thing that click awaits, and
// everything slow (the walk) happens before it. The bytes then stream to the chosen file; closing the dialog while it walks
// or writes cancels it. (A file downloads as itself through its own action; it never reaches this dialog.)
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import ProgressBar from 'primevue/progressbar'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from '../node/tree'
import { formatBytes } from '../../shared/formatBytes'
import { SaveTooLarge, isSaveCancelled, openSaveTarget } from '../../shared/saveStream'
import { useToast } from '../../shared/state/useToast'
import { prepareExport, type PreparedExport } from './exportSubtree'
import { archiveName } from './archiveName'
import { ExportError, MANIFEST_APP_VERSION } from './manifest'

const props = defineProps<{ node: TreeNode; access: Access; tree: TreeSource<TreeNode>; displayName: string }>()
const emit = defineEmits<{ close: [] }>()
const { t, locale } = useI18n()
const toast = useToast()

type Step = 'preparing' | 'ready' | 'writing' | 'failed'
const step = ref<Step>('preparing')
const prepared = ref<PreparedExport | null>(null)
const error = ref('')
const fraction = ref(0)
const control = new AbortController()
onBeforeUnmount(() => control.abort())

const busy = computed(() => step.value === 'preparing' || step.value === 'writing')
const size = computed(() => formatBytes(prepared.value?.bytes ?? 0, locale.value))
// The saved file's name: the node's name made safe, as the archive's own top directory is.
const fileName = computed(() => `${archiveName(props.displayName, new Set())}.zip`)

onMounted(async () => {
  try {
    prepared.value = await prepareExport({
      access: props.access,
      tree: props.tree,
      rootId: props.node.id,
      appVersion: MANIFEST_APP_VERSION,
      signal: control.signal,
    })
    step.value = 'ready'
  } catch (e) {
    if (control.signal.aborted) return
    error.value = e instanceof ExportError && e.code === 'nothing_to_import' ? t('export.nothing') : t('export.failed')
    step.value = 'failed'
  }
})

async function save() {
  const ready = prepared.value
  if (!ready || step.value !== 'ready') return
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
    <form class="veyq-form" data-testid="export-dialog" @submit.prevent="save">
      <p v-if="step === 'preparing'" class="veyq-form-copy" role="status">{{ t('export.preparing') }}</p>
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
        <Button
          v-if="step !== 'failed'"
          type="submit"
          :label="t('export.save')"
          icon="pi pi-download"
          :disabled="step === 'preparing'"
          :loading="busy"
          data-testid="export-save"
        />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.export-dialog {
  max-width: min(28rem, 92vw);
}
.export-skipped {
  margin: 0;
  padding-inline-start: 1.1rem;
  color: var(--app-muted);
}
</style>
