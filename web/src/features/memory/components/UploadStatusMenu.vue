<script setup lang="ts">
// uc.memory.upload (web, cr-0058) — the TopBar uploads tray. Ported from legacy/frontend-noe2ee's
// UploadStatusMenu (button + custom popover), rewired onto our uploadQueue. Shows every in-flight/finished
// batch with per-file progress + cancel/retry; the button badges the active-file count and spins while uploads
// run. Mounted in AppTopbar's actions slot; the queue is a module singleton so it persists across navigation.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  useUploadQueue,
  cancelFile,
  cancelAll,
  retryFile,
  acknowledgeBatch,
  type UploadBatch,
  type UploadFile,
} from '../uploadQueue'

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

const canCancel = (f: UploadFile) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading'
const percent = (fraction: number) => Math.round(fraction * 100)
function batchProgress(batch: UploadBatch): number {
  if (batch.files.length === 0) return 0
  return percent(batch.files.reduce((n, f) => n + (f.status === 'done' ? 1 : f.progress), 0) / batch.files.length)
}
function statusIcon(f: UploadFile): string {
  if (f.status === 'done') return 'pi pi-check'
  if (f.status === 'failed') return 'pi pi-times'
  if (f.status === 'cancelled') return 'pi pi-ban'
  if (f.status === 'preparing' || f.status === 'uploading') return 'pi pi-spin pi-spinner' // both are active work
  return 'pi pi-clock'
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
      :aria-label="t('media.uploads')"
      :title="t('media.uploads')"
      @click="toggle"
    >
      <span class="pi" :class="activeFileCount > 0 ? 'pi-spin pi-spinner' : 'pi-list'" />
      <span v-if="activeFileCount" class="uploads-badge">{{ activeFileCount }}</span>
    </button>

    <section v-if="open" class="uploads-panel">
      <header class="uploads-header">
        <div>
          <h3>{{ t('media.uploads') }}</h3>
          <!-- Only the active summary here; the "no uploads" line lives in the empty row below (no double). -->
          <p v-if="activeFileCount">{{ t('media.uploadActive', { count: activeFileCount }) }}</p>
        </div>
        <button v-if="activeFileCount" type="button" class="uploads-cancel-all" @click="cancelAll">
          {{ t('media.uploadCancelAll') }}
        </button>
      </header>

      <p v-if="!hasUploads" class="uploads-empty">{{ t('media.uploadNone') }}</p>

      <article v-for="batch in batches" :key="batch.id" class="uploads-batch">
        <div class="uploads-batch-head">
          <strong>{{ batch.label }}</strong>
          <span>{{ batchProgress(batch) }}%</span>
        </div>
        <div class="uploads-bar" role="progressbar" :aria-valuenow="batchProgress(batch)" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${batchProgress(batch)}%` }" />
        </div>
        <ul class="uploads-files">
          <li v-for="file in batch.files" :key="file.id" class="uploads-file" :class="`is-${file.status}`">
            <span class="uploads-file-icon" :class="statusIcon(file)" aria-hidden="true" />
            <span class="uploads-file-name">{{ file.name }}</span>
            <span class="uploads-file-status">{{ t(`media.uploadStatus.${file.status}`) }}</span>
            <button
              v-if="canCancel(file)"
              type="button"
              class="uploads-file-action"
              :aria-label="t('media.uploadCancel')"
              :title="t('media.uploadCancel')"
              @click="cancelFile(file.id)"
            >
              <span class="pi pi-times" aria-hidden="true" />
            </button>
            <button
              v-else-if="file.status === 'failed'"
              type="button"
              class="uploads-file-action"
              :aria-label="t('media.uploadRetry')"
              :title="t('media.uploadRetry')"
              @click="retryFile(file.id)"
            >
              <span class="pi pi-refresh" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <button v-if="batch.finishedAt !== null" type="button" class="uploads-dismiss" @click="acknowledgeBatch(batch.id)">
          {{ t('media.close') }}
        </button>
      </article>
    </section>
  </div>
</template>

<style scoped>
.uploads-tray {
  position: relative;
}
/* Match the sibling topbar icon buttons (theme, settings): transparent + borderless, hover tint only — not the
   boxed border+surface look it carried before (cr-0058). Same 2.25rem (36px) square + radius as .topbar-icon-button. */
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
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
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
  color: var(--app-on-strong);
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
.uploads-batch {
  padding: 0.5rem 0;
  border-top: 1px solid var(--app-line);
}
.uploads-batch-head {
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
.uploads-file.is-failed .uploads-file-status {
  color: var(--app-danger);
}
.uploads-file-icon.pi-check {
  color: var(--app-accent);
}
.uploads-file-icon.pi-times,
.uploads-file-icon.pi-ban {
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
