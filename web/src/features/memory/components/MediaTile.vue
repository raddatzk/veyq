<script setup lang="ts">
// The one media tile shared by the Galerie, Papierkorb and Beiträge grids (cr-0125). A positioned wrapper with a
// single clickable image button (emits `open`) + an optional `#overlay` slot (a selection check + a 3-dot menu).
// Renders the decrypted thumbnail, a file icon (non-derivable), or the shimmer placeholder — with the image FIT
// (`object-fit: cover`, full-bleed) living HERE, not scoped per tab, so every grid renders identically (the
// scoped-only fit was the cr-0122 bucket crop/oversize bug). The tile geometry (transform/size) + `data-media-id`
// fall through from the parent onto the root via attribute inheritance.
defineProps<{
  /** The decrypted thumbnail object URL, or undefined (→ icon / placeholder). */
  src: string | undefined
  /** Whether the media has derivatives at all (false ⇒ a plain file icon rather than the loading placeholder). */
  hasDerivatives: boolean
  /** aria-label for the tile's action button (open / select). */
  openLabel: string
  /** Draw the selection outline. */
  selected?: boolean
  /** Draw the play badge over the poster — the media is a video (#105, from the sealed mediaType). */
  isVideo?: boolean
  /**
   * Show the item-favorite heart (ADR 0068). Gallery only — the trash and contributions grids pass nothing, as
   * they do for `isVideo`.
   *
   * Unlike the heart on a Memory, an item mark is **shared with the scope**, and [markCount] is what says so:
   * it is the number of members who marked this item, and the UI never shows more than that number — no names,
   * no faces. The count is also the honest signal that someone else can see the mark, which is why it is drawn
   * on the heart itself and not tucked into a tooltip.
   */
  markable?: boolean
  /** How many members marked this item (0 = nobody). */
  markCount?: number
  /** Whether the caller is one of them — fills the heart. */
  markedByMe?: boolean
  /** aria-label for the mark toggle. */
  markLabel?: string
}>()
defineEmits<{ open: []; 'toggle-mark': [] }>()
</script>

<template>
  <div class="media-tile" :class="{ 'is-selected': selected }">
    <button type="button" class="media-tile-open" :aria-label="openLabel" @click="$emit('open')">
      <img v-if="src" class="media-tile-img" :src="src" alt="" />
      <span v-else-if="!hasDerivatives" class="media-grid-icon pi pi-file" aria-hidden="true" />
      <span v-else class="media-grid-tile-placeholder" aria-hidden="true" />
    </button>
    <span v-if="isVideo" class="media-tile-play" aria-hidden="true"><span class="pi pi-play" /></span>
    <button
      v-if="markable"
      type="button"
      class="media-tile-mark"
      :class="{ 'is-marked': markedByMe, 'has-others': (markCount ?? 0) > (markedByMe ? 1 : 0) }"
      :aria-label="markLabel"
      :aria-pressed="markedByMe === true"
      @click.stop="$emit('toggle-mark')"
    >
      <span :class="markedByMe ? 'pi pi-heart-fill' : 'pi pi-heart'" aria-hidden="true" />
      <span v-if="(markCount ?? 0) > 0" class="media-tile-mark-count">{{ markCount }}</span>
    </button>
    <slot name="overlay" />
  </div>
</template>

<style scoped>
/* The mark toggle. Always legible over a photo (the scrim), and the COUNT sits beside the heart rather than in a
   tooltip: it is the standing evidence that a mark is seen by the others, and a tooltip only informs whoever
   hovers. `.has-others` is the case where the number is not just the caller's own. */
.media-tile-mark {
  position: absolute;
  right: 4px;
  bottom: 4px;
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 6px;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: color-mix(in srgb, var(--app-surface) 55%, transparent);
  color: var(--app-text);
  font-size: 0.75rem;
  line-height: 1;
  cursor: pointer;
  backdrop-filter: blur(2px);
}
.media-tile-mark:hover {
  background: color-mix(in srgb, var(--app-surface) 80%, transparent);
}
.media-tile-mark.is-marked {
  color: var(--app-favorite);
}
.media-tile-mark-count {
  font-variant-numeric: tabular-nums;
}

.media-tile {
  position: absolute;
  top: 0;
  left: 0;
  overflow: hidden;
  border-radius: var(--app-radius-sm);
}
.media-tile.is-selected {
  outline: 2px solid var(--app-accent);
  outline-offset: -2px;
}
.media-tile-open {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: var(--app-control-bg);
  color: var(--app-muted);
  cursor: pointer;
}
/* The fit that used to live on the gallery's SCOPED `.gallery-tile img` — now shared so every grid matches. */
.media-tile-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
/* Play badge over a video poster (#105): centered, non-interactive (clicks fall through to the open button). A
   soft scrim disc + a play glyph — the persisted counterpart to the optimistic tile's badge, so both agree. */
.media-tile-play {
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
  pointer-events: none; /* the tile's open button owns the click */
}
.media-tile-play .pi {
  font-size: 1.1rem;
  margin-left: 0.15rem; /* optically center the play triangle */
}
</style>
