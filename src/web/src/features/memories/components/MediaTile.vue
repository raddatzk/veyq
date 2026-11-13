<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
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
  /** Draw the play badge over the poster — the media is a video (#105, from the original's sealed blob meta type, inbox #397). */
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
  /** A moderator blocked it (uc.moderation.take-a-measure): the placeholder "Blocked by the operator", no image. */
  blocked?: boolean
  /** Its thumbnail could not be read, after the read scheduler's retries (inbox #488): an error tile instead of a
   *  placeholder that would shimmer forever. A thumbnail already shown (a donated upload preview) stays. */
  failed?: boolean
}>()
defineEmits<{ open: []; 'toggle-mark': [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="media-tile" :class="{ 'is-selected': selected }">
    <button type="button" class="media-tile-open" :aria-label="openLabel" @click="$emit('open')">
      <span v-if="blocked" class="media-tile-blocked"><VeyqIcon name="ban" />{{ t('node.blockedByOperator') }}</span>
      <img v-else-if="src" class="media-tile-img" :src="src" alt="" />
      <VeyqIcon v-else-if="!hasDerivatives" name="file" class="media-grid-icon" />
      <span v-else-if="failed" class="media-tile-failed"><VeyqIcon name="exclamation-triangle" />{{ t('media.thumbnailFailed') }}</span>
      <span v-else class="media-grid-tile-placeholder" aria-hidden="true" />
    </button>
    <span v-if="isVideo" class="media-tile-play" aria-hidden="true"><VeyqIcon name="play" /></span>
    <VeyqButton
      v-if="markable"
      class="media-tile-mark"
      :class="{ 'is-marked': markedByMe, 'has-count': (markCount ?? 0) > 0 }"
      :icon="markedByMe ? 'heart-fill' : 'heart'"
      :label="(markCount ?? 0) > 0 ? String(markCount) : undefined"
      :aria-label="markLabel"
      :aria-pressed="markedByMe === true"
      variant="secondary"
      appearance="text"
      size="small"
      @click.stop="$emit('toggle-mark')"
    />
    <slot name="overlay" />
  </div>
</template>

<style scoped>
/* A blocked medium (uc.moderation.take-a-measure), or a thumbnail that could not be read (inbox #488): the tile's own
   surface, the text centred on it. */
.media-tile-blocked,
.media-tile-failed {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  padding: 6px;
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-size: 0.75rem;
  text-align: center;
}
/* The mark toggle: the SAME heart as a card's in the level (core's favourite, `styles/ui.css` "A tile's actions" - owner
   2026-11-12, one heart everywhere): the layer's small text button at the icon-button size, on the surface veil, in the
   top-right corner at the card's inset, the filled heart in the favourite colour; shown on hover and while focus is
   inside - by opacity alone, so it stays in the Tab order -, always where nothing hovers, and always while it says
   something: marked by the caller, or by anyone at all. The COUNT sits beside the heart rather than in a tooltip: it is
   the standing evidence that a mark is seen by the others, and a tooltip only informs whoever hovers. */
.media-tile-mark.veyq-button {
  position: absolute;
  top: 0.35rem;
  right: 0.35rem;
  min-width: 2.55rem;
  height: 2.55rem;
  background: color-mix(in srgb, var(--app-surface) 88%, transparent);
  font-variant-numeric: tabular-nums;
  opacity: 0;
  transition: opacity 0.15s;
}
.media-tile-mark.veyq-button.veyq-button--icon-only {
  width: 2.55rem;
}
.media-tile-mark.veyq-button:not(:disabled):hover {
  background: var(--app-surface);
}
.media-tile:hover .media-tile-mark.veyq-button,
.media-tile:focus-within .media-tile-mark.veyq-button,
.media-tile-mark.veyq-button.is-marked,
.media-tile-mark.veyq-button.has-count {
  opacity: 1;
}
@media (hover: none) {
  .media-tile-mark.veyq-button {
    opacity: 1;
  }
}
.media-tile-mark.veyq-button.is-marked {
  color: var(--app-favorite);
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
.media-tile-play .veyq-icon {
  font-size: 1.1rem;
  margin-left: 0.15rem; /* optically center the play triangle */
}
</style>
