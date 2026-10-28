<script setup lang="ts">
// One optimistic in-grid upload tile (uc.memory.upload, cr-0074 + cr-0101) — a picked file shown instantly as a
// dimmed local preview with a progress ring (or a failed state with an inline retry), at its capture-time place
// in the gallery grid while it uploads.
//
// **The tile names the stage it is actually in.** `queued`, `preparing` and `uploading` are three different
// waits — the first byte has not left the device in two of them — and the tile used to assert "Uploading" for
// all three, in the visible ring's label AND in the container's. A large batch therefore looked stuck: every
// tile claimed to be uploading while the device was still decoding. The strings already existed and the TopBar
// tray already rendered them; only the tile failed to ask. Shared by the internal gallery (MemoryDetail) and the anonymous contributor viewer
// (ShareView). Positioning (transform + width/height) is applied by the parent via inherited attrs. Not
// clickable — the lightbox opens the real media once it lands.
//
// **The stage and progress are read HERE, off `tile.upload`** (inbox #640): every chunk writes them, so read in this
// render they re-render this one tile. Never pass them in as props from the grid's slot — the slot runs in the grid's
// render, and every chunk would re-render the grid.
import { useI18n } from 'vue-i18n'
import type { OptimisticTileVM } from '../useOptimisticTiles'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { APPLICATION_OFF } from '../../../core/blob/queue'

const props = defineProps<{
  tile: OptimisticTileVM
  /** cr-0102 uploader drop-box: a settled tile carries a per-tile delete (session-only own-view). */
  deletable?: boolean
  deleting?: boolean
  /** The tile is a link visitor's upload (the drop-box page, or a guest's Memory detail). A `quota_exceeded` refusal then reads as the link being full
   *  — never which limit was reached — and offers no retry, because none can help (ADR 0073 §6). */
  link?: boolean
}>()
const emit = defineEmits<{ retry: [fileId: string]; delete: [fileId: string] }>()

const { t } = useI18n()
const RING_CIRCUMFERENCE = 2 * Math.PI * 16 // matches the SVG circle radius below
/** What this file is doing right now, in the user's language — the same string the upload tray shows. */
const statusLabel = () => t(`media.uploadStatus.${props.tile.upload.status}`)
// A settled uploader tile stays in the grid (its upload lists no media for the uploader) with a delete affordance
// instead of the progress ring — the local preview IS the session-only own-view.
const isDoneDeletable = () => props.deletable === true && props.tile.upload.status === 'done'
const isLinkFull = () => props.link === true && props.tile.upload.status === 'failed' && props.tile.upload.error === 'quota_exceeded'
// Memories is switched off here (uc.platform.switch-application-off): the server refused the start; no retry helps.
const isSwitchedOff = () => props.tile.upload.status === 'failed' && props.tile.upload.error === APPLICATION_OFF
</script>

<template>
  <div
    class="gallery-tile gallery-tile-optimistic"
    :class="{ 'is-failed': tile.upload.status === 'failed', 'is-done': isDoneDeletable() }"
    :data-upload-id="tile.fileId"
    :aria-label="`${statusLabel()} – ${tile.name}`"
  >
    <!-- The poster (image OR a derived video first-frame) shows once ready; until then the dimmed background + the
         progress ring below carry the state — no separate placeholder glyph (the ring is the signal, #105). The
         play badge marks a settled video. -->
    <img v-if="tile.url" class="media-grid-tile-media" :src="tile.url" :alt="tile.name" />
    <span v-if="tile.isVideo && tile.url && tile.upload.status === 'done'" class="optimistic-tile-play" aria-hidden="true">
      <VeyqIcon name="play" />
    </span>
    <!-- cr-0102 uploader: a settled tile is the session-only own-view — no ring, just a delete affordance -->
    <div v-if="isDoneDeletable()" class="upload-tile-done">
      <button
        type="button"
        class="upload-tile-delete"
        :disabled="deleting"
        :aria-label="t('share.deletePhoto')"
        @click.stop="emit('delete', tile.fileId)"
      >
        <VeyqIcon :name="deleting ? 'spinner' : 'trash'" :spin="deleting" />
      </button>
    </div>
    <div v-else-if="isLinkFull()" class="upload-tile-state upload-tile-failed">
      <VeyqIcon name="exclamation-triangle" class="upload-tile-failed-icon" />
      <span class="upload-tile-failed-label">{{ t('media.uploadTileLinkFull') }}</span>
    </div>
    <div v-else-if="isSwitchedOff()" class="upload-tile-state upload-tile-failed">
      <VeyqIcon name="exclamation-triangle" class="upload-tile-failed-icon" />
      <span class="upload-tile-failed-label">{{ t('media.uploadTileSwitchedOff') }}</span>
    </div>
    <div v-else-if="tile.upload.status === 'failed'" class="upload-tile-state upload-tile-failed">
      <VeyqIcon name="exclamation-triangle" class="upload-tile-failed-icon" />
      <span class="upload-tile-failed-label">{{ t('media.uploadTileFailed') }}</span>
      <button type="button" class="upload-tile-retry" @click.stop="emit('retry', tile.fileId)">
        <VeyqIcon name="refresh" />
        <span>{{ t('media.uploadRetry') }}</span>
      </button>
    </div>
    <div v-else class="upload-tile-state upload-tile-progress" :aria-label="statusLabel()">
      <svg class="upload-ring" viewBox="0 0 36 36" role="progressbar">
        <circle class="upload-ring-track" cx="18" cy="18" r="16" />
        <circle
          class="upload-ring-fill"
          :class="{ 'is-indeterminate': tile.upload.status === 'queued' || tile.upload.status === 'preparing' }"
          cx="18"
          cy="18"
          r="16"
          :stroke-dasharray="`${tile.upload.progress * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`"
        />
      </svg>
      <!-- Same slot the failed state uses for its label, so this adds no control and no new geometry. -->
      <span class="upload-tile-status-label">{{ statusLabel() }}</span>
    </div>
  </div>
</template>

<style scoped>
/* The tile container geometry (position/size/skin) is the design-source gallery tile; the parent positions it
   via inherited style. The inner classes (.media-grid-tile-media / -icon) are global (Memories' `memories.css`). */
.gallery-tile {
  position: absolute;
  top: 0;
  left: 0;
  display: block;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
  color: var(--app-muted);
}
.gallery-tile-optimistic {
  cursor: default;
}
.gallery-tile-optimistic .media-grid-tile-media {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.55; /* dim the preview so the progress overlay reads clearly */
}
.gallery-tile-optimistic.is-failed .media-grid-tile-media {
  opacity: 0.3;
}
/* A settled uploader tile is a normal-looking photo (the session-only own-view), not a dimmed in-flight one. */
.gallery-tile-optimistic.is-done .media-grid-tile-media {
  opacity: 1;
}
.upload-tile-done {
  position: absolute;
  top: 0.35rem;
  right: 0.35rem;
}
.upload-tile-delete {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-backdrop-strong) 60%, transparent);
  color: #fff;
  font-size: 0.85rem;
  cursor: pointer;
}
.upload-tile-delete:hover {
  background: var(--app-danger);
  color: var(--app-on-fill);
}
.upload-tile-delete:disabled {
  opacity: 0.6;
  cursor: default;
}
.upload-tile-delete:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
/* Play badge over a settled video's poster — matches the persisted MediaTile badge so the two states agree (#105). */
.optimistic-tile-play {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-backdrop-strong) 55%, transparent);
  color: #fff;
  pointer-events: none;
}
.optimistic-tile-play .veyq-icon {
  font-size: 1.1rem;
  margin-left: 0.15rem;
}
.upload-tile-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.5rem;
  text-align: center;
}
.upload-ring {
  width: 42px;
  height: 42px;
  transform: rotate(-90deg); /* start the fill at 12 o'clock */
}
.upload-ring-track {
  fill: none;
  stroke: color-mix(in srgb, #fff 55%, transparent);
  stroke-width: 3;
}
.upload-ring-fill {
  fill: none;
  stroke: #fff;
  stroke-width: 3;
  stroke-linecap: round;
  transition: stroke-dasharray 0.2s ease;
}
.upload-ring-fill.is-indeterminate {
  stroke-dasharray: 25 100;
  transform-origin: center;
  animation: upload-ring-spin 0.9s linear infinite;
}
@keyframes upload-ring-spin {
  to {
    transform: rotate(360deg);
  }
}
.upload-tile-failed {
  color: #fff;
  background: color-mix(in srgb, var(--app-backdrop-strong) 55%, transparent);
}
.upload-tile-failed .upload-tile-failed-icon {
  font-size: 1.25rem;
}
.upload-tile-failed-label {
  font-size: 0.78rem;
  font-weight: 600;
}
/* The stage label under the ring — the failed state's label type, so the two states read as one family. */
.upload-tile-status-label {
  color: #fff;
  font-size: 0.78rem;
  font-weight: 600;
  text-shadow: 0 1px 2px var(--app-backdrop-strong);
}
.upload-tile-retry {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.25rem 0.6rem;
  border: 1px solid color-mix(in srgb, #fff 70%, transparent);
  border-radius: 999px;
  background: transparent;
  color: #fff;
  font-size: 0.78rem;
  cursor: pointer;
}
.upload-tile-retry:hover {
  background: color-mix(in srgb, #fff 18%, transparent);
}
.upload-tile-retry:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
</style>
