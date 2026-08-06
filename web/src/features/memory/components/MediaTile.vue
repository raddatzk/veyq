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
}>()
defineEmits<{ open: [] }>()
</script>

<template>
  <div class="media-tile" :class="{ 'is-selected': selected }">
    <button type="button" class="media-tile-open" :aria-label="openLabel" @click="$emit('open')">
      <img v-if="src" class="media-tile-img" :src="src" alt="" />
      <span v-else-if="!hasDerivatives" class="media-grid-icon pi pi-file" aria-hidden="true" />
      <span v-else class="media-grid-tile-placeholder" aria-hidden="true" />
    </button>
    <span v-if="isVideo" class="media-tile-play" aria-hidden="true"><span class="pi pi-play" /></span>
    <slot name="overlay" />
  </div>
</template>

<style scoped>
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
