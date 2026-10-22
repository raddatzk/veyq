<script setup lang="ts">
// uc.memory.upload (web, cr-0058) — the TopBar uploads tray. Ported from legacy/frontend-noe2ee's
// UploadStatusMenu (button + custom popover), rewired onto our uploadQueue. Shows every in-flight/finished
// upload, one list per destination, with per-file progress + cancel/retry; the button badges the active-file count and spins while uploads
// run. The queue is a module singleton so it persists across navigation.
//
// The platform's, not an application's (cr-files-application B3): the core top bar mounts it directly, and it lists
// EVERY batch of the one core queue, whichever application enqueued it. It reads only the neutral item and batch
// fields (name, status, progress, error, label, finishedAt) — never an application's `extra` — so a Depot batch shows
// exactly like a Memories one, and nothing here may import an application.
//
// ONE LIST PER DESTINATION (uc.memory.upload step 4). The tray draws a list, not a batch: every open (unacknowledged)
// batch with the same `key` — the caller's name for where its files go — is one list, in `seq` order, under its first
// batch's label, with one bar over all its files and one Close for all its batches. A batch enqueued later for the same
// destination joins the open list (the bar recomputes and can go down); a closed list's batches are acknowledged, so a
// later batch starts a new list. A batch with no `key` is a list of its own. The grouping is the tray's only: the queue
// keeps its batches, each with its own hooks and `extra`, and merging those would merge what different enqueues own.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { VeyqIconName } from '../../shared/ui/icons'
import {
  useUploadQueue,
  cancelFile,
  cancelAll,
  retryFile,
  acknowledgeBatch,
  isRetryable,
  QUOTA_EXCEEDED,
  type UploadBatch,
  type UploadItem,
} from './queue'

const { t } = useI18n()
const { batches, activeFileCount } = useUploadQueue()
const open = ref(false)
const root = ref<HTMLElement | null>(null)

const hasUploads = computed(() => batches.value.length > 0)

function toggle() {
  open.value = !open.value
}
function close() {
  open.value = false
}
function onPointerDown(e: PointerEvent) {
  if (open.value && root.value && e.target instanceof Node && !root.value.contains(e.target)) close()
}
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

/** One list of the tray: the open batches of one destination, drawn as one. */
type TrayList = { id: string; label: string; batches: UploadBatch[]; files: UploadItem[] }

/** The open batches grouped by destination (`key`), each list at its first batch's place; a keyless batch stands alone. */
const lists = computed<TrayList[]>(() => {
  const out: TrayList[] = []
  const byKey = new Map<string, TrayList>()
  for (const batch of batches.value.slice().sort((a, b) => a.seq - b.seq)) {
    const joined = batch.key === undefined ? undefined : byKey.get(batch.key)
    if (joined) {
      joined.batches.push(batch)
      joined.files.push(...batch.files)
      continue
    }
    const list: TrayList = { id: batch.id, label: batch.label, batches: [batch], files: [...batch.files] }
    if (batch.key !== undefined) byKey.set(batch.key, list)
    out.push(list)
  }
  return out
})

const canCancel = (f: UploadItem) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading'
const percent = (fraction: number) => Math.round(fraction * 100)
/** The per-batch formula the tray always had, over all of a list's files: a done file counts whole, every other file
 *  (a cancelled one too) by its own progress. */
function listProgress(list: TrayList): number {
  if (list.files.length === 0) return 0
  return percent(list.files.reduce((n, f) => n + (f.status === 'done' ? 1 : f.progress), 0) / list.files.length)
}
const listFinished = (list: TrayList) => list.batches.every((b) => b.finishedAt !== null)
function closeList(list: TrayList) {
  for (const b of list.batches) acknowledgeBatch(b.id)
}
const isActive = (f: UploadItem) => f.status === 'preparing' || f.status === 'uploading' // both are active work
function statusIcon(f: UploadItem): VeyqIconName {
  if (f.status === 'done') return 'check'
  if (f.status === 'failed') return 'times'
  if (f.status === 'cancelled') return 'ban'
  if (isActive(f)) return 'spinner'
  return 'clock'
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="root" class="uploads-tray">
    <!-- The tray button appears only when there is something to show — no uploads, no button (not an empty popover). -->
    <button
      v-if="hasUploads || activeFileCount"
      type="button"
      class="uploads-trigger"
      :aria-label="t('upload.title')"
      :title="t('upload.title')"
      :aria-expanded="open"
      aria-controls="uploads-panel"
      @click="toggle"
    >
      <VeyqIcon :name="activeFileCount > 0 ? 'spinner' : 'list'" :spin="activeFileCount > 0" />
      <span v-if="activeFileCount" class="uploads-badge">{{ activeFileCount }}</span>
    </button>

    <section v-if="open" id="uploads-panel" class="uploads-panel">
      <header class="uploads-header">
        <div>
          <h3>{{ t('upload.title') }}</h3>
          <!-- Only the active summary here; the "no uploads" line lives in the empty row below (no double). -->
          <p v-if="activeFileCount">{{ t('upload.active', { count: activeFileCount }) }}</p>
        </div>
        <button v-if="activeFileCount" type="button" class="uploads-cancel-all" @click="cancelAll">
          {{ t('upload.cancelAll') }}
        </button>
      </header>

      <p v-if="!hasUploads" class="uploads-empty">{{ t('upload.none') }}</p>

      <article v-for="list in lists" :key="list.id" class="uploads-list">
        <div class="uploads-list-head">
          <strong>{{ list.label }}</strong>
          <span>{{ listProgress(list) }}%</span>
        </div>
        <div class="uploads-bar" role="progressbar" :aria-valuenow="listProgress(list)" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${listProgress(list)}%` }" />
        </div>
        <ul class="uploads-files">
          <li v-for="file in list.files" :key="file.id" class="uploads-file" :class="`is-${file.status}`">
            <VeyqIcon class="uploads-file-icon" :name="statusIcon(file)" :spin="isActive(file)" />
            <span class="uploads-file-name">{{ file.name }}</span>
            <span class="uploads-file-status">{{ t(`upload.status.${file.status}`) }}</span>
            <button
              v-if="canCancel(file)"
              type="button"
              class="uploads-file-action"
              :aria-label="t('upload.cancel')"
              :title="t('upload.cancel')"
              @click="cancelFile(file.id)"
            >
              <VeyqIcon name="times" />
            </button>
            <button
              v-else-if="file.status === 'failed' && isRetryable(file)"
              type="button"
              class="uploads-file-action"
              :aria-label="t('upload.retry')"
              :title="t('upload.retry')"
              @click="retryFile(file.id)"
            >
              <VeyqIcon name="refresh" />
            </button>
            <!-- A refusal for lack of room says why, and offers no retry: none can help (ADR 0073 §6). -->
            <span v-if="file.status === 'failed' && file.error === QUOTA_EXCEEDED" class="uploads-file-reason">
              {{ t('upload.noSpace') }}
            </span>
          </li>
        </ul>
        <button v-if="listFinished(list)" type="button" class="uploads-dismiss" @click="closeList(list)">
          {{ t('upload.close') }}
        </button>
      </article>
    </section>
  </div>
</template>

<style scoped>
.uploads-tray {
  position: relative;
}
/* Match the one sibling topbar icon button, .invitations-trigger: 2.25rem (36px) square, 0.5rem radius,
   transparent + borderless, hover tint only — not the boxed border+surface look it carried before (cr-0058). */
.uploads-trigger {
  position: relative;
  display: grid;
  place-items: center;
  width: 2.25rem;
  height: 2.25rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.uploads-trigger:hover {
  background: var(--app-hover);
}
.uploads-badge {
  position: absolute;
  top: -0.4rem;
  right: -0.4rem;
  min-width: 1.1rem;
  height: 1.1rem;
  padding: 0 0.25rem;
  border-radius: 999px;
  background: var(--app-accent);
  color: var(--app-on-fill);
  font-size: 0.7rem;
  line-height: 1.1rem;
  text-align: center;
}
.uploads-panel {
  position: absolute;
  top: calc(100% + 0.5rem);
  right: 0;
  width: min(24rem, 90vw);
  max-height: 70vh;
  overflow-y: auto;
  padding: 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: 0.75rem;
  background: var(--app-surface);
  box-shadow: var(--app-shadow-md);
  z-index: 50;
}
.uploads-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.uploads-header h3 {
  margin: 0;
  font-size: 0.95rem;
}
.uploads-header p {
  margin: 0;
  font-size: 0.8rem;
  color: var(--app-muted);
}
.uploads-cancel-all {
  border: none;
  background: none;
  color: var(--app-muted);
  cursor: pointer;
  font-size: 0.8rem;
}
.uploads-empty {
  margin: 0.25rem 0;
  font-size: 0.85rem;
  color: var(--app-muted);
}
.uploads-list {
  padding: 0.5rem 0;
  border-top: 1px solid var(--app-line);
}
.uploads-list-head {
  display: flex;
  justify-content: space-between;
  font-size: 0.8rem;
  color: var(--app-muted);
  margin-bottom: 0.25rem;
}
.uploads-bar {
  height: 0.35rem;
  border-radius: 999px;
  background: var(--app-line);
  overflow: hidden;
}
.uploads-bar > span {
  display: block;
  height: 100%;
  background: var(--app-accent);
  transition: width 0.2s ease;
}
.uploads-files {
  list-style: none;
  margin: 0.4rem 0 0;
  padding: 0;
  display: grid;
  gap: 0.3rem;
}
.uploads-file {
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.8rem;
}
.uploads-file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.uploads-file-status {
  color: var(--app-muted);
}
.uploads-file-reason {
  grid-column: 2 / -1;
  color: var(--app-muted);
}
.uploads-file.is-failed .uploads-file-status {
  color: var(--app-danger);
}
.uploads-file.is-done .uploads-file-icon {
  color: var(--app-accent);
}
.uploads-file.is-failed .uploads-file-icon,
.uploads-file.is-cancelled .uploads-file-icon {
  color: var(--app-danger);
}
.uploads-file-action {
  border: none;
  background: none;
  color: var(--app-muted);
  cursor: pointer;
}
.uploads-dismiss {
  margin-top: 0.4rem;
  border: none;
  background: none;
  color: var(--app-muted);
  cursor: pointer;
  font-size: 0.8rem;
}
</style>
