<script setup lang="ts">
// Core's copy dialog (uc.platform.copy-node): "Copy to…" for one node or a level's selection,
// a member flow. It is the move's folder browser (`MoveBrowser`, the copy intent: the nodes' own place is allowed - the copy is
// named `Name (2)` there - the nodes themselves and anything inside them are not) above the import's summary: opened, it
// walks what is copied at once (`prepareCopy`: listings only, no payload read) and says, before anything is written, what
// the copy creates and how many bytes, what it leaves out (a kind this build cannot copy, a file whose upload never
// finished) and what does not come along (the losses: core's own, and each copied kind's `copyLosses`); it asks the server
// whether those bytes fit the tenant's quota, and a "no" disables "Copy here". "Copy here" re-creates everything under the
// place on screen ("N of M"); a stop part-way trashes what it created and says how far it got. Closing the dialog while it
// walks or copies stops it.
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqProgress from '../../shared/ui/VeyqProgress.vue'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from '../node/tree'
import { registry } from '../applications/registry'
import { moveHereBlockOf, moveStart } from '../node/move'
import { joinList } from '../node/selection'
import MoveBrowser from '../node/MoveBrowser.vue'
import { loadTree, treeStore } from '../node/treeStore'
import { formatBytes } from '../../shared/formatBytes'
import { useToast } from '../../shared/state/useToast'
import { activeSpace } from '../../app/activeSpace'
import { tenantStorageFits } from '../../features/tenant/tenantStorage'
import { prepareCopy, type PreparedCopy } from './copySubtree'
import { ImportFailed } from './importSubtree'
import { ExportError, MANIFEST_APP_VERSION } from './manifest'

const props = defineProps<{
  /** What is copied: one node, or the copyable part of a level's selection (every one of them on that level). */
  nodes: readonly TreeNode[]
  /** Who copies: a member (the host mounts the dialog for nobody else). */
  access: Access
  /** The member's tree. */
  tree: TreeSource<TreeNode>
  /** The member's scope root as the trail names it. */
  rootLabel?: string
}>()
const emit = defineEmits<{ copied: [copy: { nodeIds: string[]; to: string | null }]; close: [] }>()
const { t, locale } = useI18n()
const toast = useToast()

/** Each node's name as its siblings tell it apart, read now. */
const names = new Map(props.nodes.map((n) => [n.id, props.tree.nameOf(n.id) || n.name]))
const nameOf = (n: TreeNode) => names.get(n.id) ?? n.name
const single = computed(() => props.nodes.length === 1)
const title = computed(() => (single.value ? t('node.copy.title', { name: nameOf(props.nodes[0]) }) : t('node.copy.many.title', { count: props.nodes.length })))

type Step = 'preparing' | 'ready' | 'running' | 'failed'
const step = ref<Step>('preparing')
const prepared = shallowRef<PreparedCopy | null>(null)
const error = ref('')
const progress = ref({ nodesDone: 0, nodesTotal: 1 })
// Whether the copy fits the tenant's quota: the server's yes or no, asked once the walk is known. `null`: not asked, or not
// answerable - the upload then decides, so an unanswerable question never blocks a copy (as the import).
const fits = ref<boolean | null>(null)
const control = new AbortController()
onBeforeUnmount(() => control.abort())

const place = ref<string | null>(moveStart(props.tree, props.nodes))
const here = computed(() => moveHereBlockOf(props.tree, props.nodes, props.access.mode, place.value, 'copy'))
const hereText = computed(() => {
  const h = here.value
  if (!h) return ''
  return single.value || !h.node ? t(`node.copy.here.${h.block}`) : t(`node.copy.many.here.${h.block}`, { name: nameOf(h.node) })
})
watch(place, () => (error.value = ''))

const size = computed(() => formatBytes(prepared.value?.bytes ?? 0, locale.value))
const canCopy = computed(() => (prepared.value?.nodes ?? 0) > 0)
const running = computed(() => step.value === 'running')
const leftOutLines = computed(() =>
  (prepared.value?.leftOut ?? []).map((x) => t('node.copy.leftOutLine', { name: x.name, reason: t(`node.copy.reason.${x.reason}`) })),
)
/** What does not come along: core's own, then each copied kind's (from the use case's list, only what applies). */
const losses = computed(() => {
  const keys = new Set<string>()
  for (const kind of prepared.value?.kinds ?? []) {
    const d = registry.kind(kind)
    if (d.supported) d.copyLosses?.forEach((k) => keys.add(k))
  }
  return [t('node.copy.lossAlways'), ...[...keys].map((k) => t(k))]
})

function destinationName(to: string | null): string {
  return to === null ? (props.rootLabel ?? '') : props.tree.nameOf(to)
}

onMounted(async () => {
  if (props.access.mode !== 'member') return
  try {
    prepared.value = await prepareCopy({
      access: props.access,
      tree: props.tree,
      rootIds: props.nodes.map((n) => n.id),
      appVersion: MANIFEST_APP_VERSION,
      signal: control.signal,
    })
    step.value = 'ready'
    if (activeSpace.tenantId && prepared.value.bytes > 0) fits.value = await tenantStorageFits(activeSpace.tenantId, prepared.value.bytes)
  } catch {
    if (control.signal.aborted) return
    error.value = t('node.copy.walkFailed')
    step.value = 'failed'
  }
})

function failureText(e: unknown, to: string | null): string {
  if (e instanceof ImportFailed) {
    const c = e.cause instanceof Error ? e.cause.message : ''
    const stopped = e.rolledBack
      ? t('node.copy.failed', { done: e.done, total: e.total })
      : t('node.copy.failedLeft', { done: e.done, total: e.total, to: destinationName(to) })
    return c === 'quota_exceeded' ? `${t('node.copy.quota')} ${stopped}` : stopped
  }
  if (e instanceof ExportError && e.code === 'nothing_to_import') return t('node.copy.nothing')
  return t('node.copy.failed', { done: 0, total: prepared.value?.nodes ?? 0 })
}

async function submit() {
  const ready = prepared.value
  const a = props.access
  const to = place.value
  if (!ready || step.value !== 'ready' || here.value !== null || !canCopy.value || fits.value === false) return
  if (a.mode !== 'member' || !a.epochs) return
  const parentKey = to === null ? null : props.tree.keyOf(to)
  const parentNode = to === null ? null : props.tree.node(to)
  if (to !== null && (!parentKey || !parentNode)) return
  error.value = ''
  step.value = 'running'
  try {
    const result = await ready.run({
      scopeId: a.scopeId,
      epochs: a.epochs,
      tree: treeStore.nodes,
      parent: to === null ? null : { id: to, key: parentKey!, epoch: parentNode!.epoch, scopeEpoch: parentNode!.scopeEpoch },
      signal: control.signal,
      onProgress: (p) => (progress.value = { nodesDone: p.nodesDone, nodesTotal: p.nodesTotal }),
    })
    await loadTree()
    // The copies by the names they were given (`Name (2)` beside an original of the name).
    const copied = result.nodeIds.map((id) => treeStore.nodes.find((n) => n.id === id)?.name).filter((n): n is string => !!n)
    const done = copied.length === 1
      ? t('node.copy.copied', { name: copied[0], to: destinationName(to) })
      : t('node.copy.many.copied', { count: result.nodeIds.length, to: destinationName(to) })
    const left = ready.leftOut.map((x) => x.name)
    toast.success(left.length > 0 ? `${done} ${t('node.copy.copiedLeftOut', { names: joinList(locale.value, left) })}` : done)
    emit('copied', { nodeIds: result.nodeIds, to })
    emit('close')
  } catch (e) {
    await loadTree().catch(() => {})
    step.value = 'ready'
    if (control.signal.aborted) return
    error.value = failureText(e, to)
  }
}

function close() {
  control.abort()
  emit('close')
}
</script>

<template>
  <VeyqDialog :visible="true" :header="title" :closable="!running" :close-on-escape="!running" class="copy-dialog" @update:visible="close">
    <form class="veyq-form" data-testid="copy-dialog" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ single ? t('node.copy.hint') : t('node.copy.many.hint') }}</p>
      <MoveBrowser v-model:place="place" :nodes="nodes" :access="access" :tree="tree" :root-label="rootLabel" :disabled="running" />
      <p v-if="hereText" id="copy-here-why" class="move-here-why">{{ hereText }}</p>
      <p v-if="step === 'preparing'" class="veyq-form-copy" role="status">{{ t('node.copy.preparing') }}</p>
      <template v-else-if="prepared">
        <p class="veyq-form-copy" data-testid="copy-summary">
          <template v-if="canCopy">{{ t('node.copy.summary', { nodes: prepared.nodes, size, target: destinationName(place) }, prepared.nodes) }}</template>
          <template v-else>{{ t('node.copy.nothing') }}</template>
        </p>
        <div v-if="leftOutLines.length" data-testid="copy-left-out">
          <p class="veyq-form-copy">{{ t('node.copy.leftOut') }}</p>
          <ul class="copy-list">
            <li v-for="(line, i) in leftOutLines" :key="i">{{ line }}</li>
          </ul>
        </div>
        <div v-if="canCopy" data-testid="copy-losses">
          <p class="veyq-form-copy">{{ t('node.copy.losses') }}</p>
          <ul class="copy-list">
            <li v-for="(line, i) in losses" :key="i">{{ line }}</li>
          </ul>
        </div>
        <VeyqMessage v-if="fits === false" tone="warn" role="alert" data-testid="copy-no-room">{{ t('node.copy.noRoom', { size }) }}</VeyqMessage>
        <p v-if="canCopy" class="veyq-form-copy copy-note">{{ t('node.copy.notice') }}</p>
        <template v-if="running">
          <p class="veyq-form-copy" role="status">{{ t('node.copy.copying', { done: progress.nodesDone, total: progress.nodesTotal }) }}</p>
          <VeyqProgress :value="Math.round((progress.nodesDone / Math.max(1, progress.nodesTotal)) * 100)" />
        </template>
      </template>
      <VeyqMessage v-if="error" tone="error" role="alert" data-testid="copy-error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="running" data-testid="copy-cancel" @click="close" />
        <VeyqButton
          type="submit"
          :label="t('node.copy.submit')"
          icon="copy"
          :loading="running"
          :disabled="step !== 'ready' || here !== null || !canCopy || fits === false"
          :aria-describedby="hereText ? 'copy-here-why' : undefined"
          data-testid="copy-submit"
        />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.copy-dialog {
  max-width: min(34rem, 92vw);
}
.move-here-why,
.copy-note {
  margin: 0;
  color: var(--app-muted);
}
.copy-list {
  margin: 0;
  padding-inline-start: 1.1rem;
  color: var(--app-muted);
}
</style>
