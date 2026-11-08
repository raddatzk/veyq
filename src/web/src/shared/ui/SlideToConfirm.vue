<script setup lang="ts">
// A confirmation you have to *carry out*, not tap (ADR 0059 §7). It exists because the reflex is the attack:
// the grant this gates hands another device everything, permanently, and a button sits exactly where the user's
// thumb already is.
//
// Built from pointer events and a progress track rather than wrapped around something existing — measured, the
// client has no slide/swipe control anywhere and the component layer carries none. The ordinary destructive
// confirm was `window.confirm` when this was written and is the app's own dialog today (`useConfirm`,
// cr-web-own-confirm-dialog) — either way one tap, which is the thing this control exists to refuse.
//
// **Keyboard is a first-class path, and it is deliberately not one keypress.** The control carries the slider
// role by hand and steps on the arrow keys; `End`/`Home` are NOT bound, because a key that jumps straight to
// the end is the tap this whole component exists to refuse. Ten steps is the same order of deliberateness as
// the drag.
import { computed, ref } from 'vue'
import VeyqIcon from './VeyqIcon.vue'

const props = defineProps<{ label: string; doneLabel: string; disabled?: boolean }>()
const emit = defineEmits<{ confirmed: [] }>()

const KEYBOARD_STEPS = 10

const track = ref<HTMLElement | null>(null)
/** 0 → 1. The visual state AND the gate: only a full 1 confirms. */
const progress = ref(0)
const done = ref(false)
let dragging: number | null = null
/** While a pointer drags, the knob and the trail follow it with no transition; released, they glide (back). */
const sliding = ref(false)

const percent = computed(() => Math.round(progress.value * 100))
const busy = computed(() => props.disabled || done.value)

function positionOf(clientX: number): number {
  const rect = track.value?.getBoundingClientRect()
  // Width 0 means the track is not laid out (hidden, or a non-rendering environment). Refusing to advance is
  // the safe direction: a divide-by-zero here would resolve to a confirmation nobody performed.
  if (!rect || rect.width <= 0) return 0
  return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
}

function complete() {
  progress.value = 1
  done.value = true
  emit('confirmed')
}

/** Released short of the end → snap back to the start. Not "keep what you dragged": a partial slide is a
 *  hesitation, and resuming it later would turn two half-gestures into one confirmation. */
function settle() {
  dragging = null
  sliding.value = false
  if (progress.value >= 1) complete()
  else progress.value = 0
}

function onPointerDown(e: PointerEvent) {
  if (busy.value) return
  dragging = e.pointerId
  sliding.value = true
  track.value?.setPointerCapture?.(e.pointerId)
  progress.value = positionOf(e.clientX)
}

function onPointerMove(e: PointerEvent) {
  if (busy.value || dragging !== e.pointerId) return
  progress.value = positionOf(e.clientX)
}

function onPointerUp(e: PointerEvent) {
  if (busy.value || dragging !== e.pointerId) return
  track.value?.releasePointerCapture?.(e.pointerId)
  settle()
}

function onKeydown(e: KeyboardEvent) {
  if (busy.value) return
  const step = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? 1
    : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -1
      : 0
  if (!step) return
  e.preventDefault()
  // Snap to whole steps. Accumulating `+ 1/10` ten times lands on 0.9999999999999999, so a keyboard run that
  // visibly reaches the end would never confirm — the gesture would simply stop working at 100%.
  const steps = Math.min(KEYBOARD_STEPS, Math.max(0, Math.round(progress.value * KEYBOARD_STEPS) + step))
  if (steps >= KEYBOARD_STEPS) complete()
  else progress.value = steps / KEYBOARD_STEPS
}
</script>

<template>
  <div
    ref="track"
    class="slide-confirm"
    :class="{ 'slide-confirm-done': done, 'slide-confirm-disabled': disabled, 'slide-confirm-sliding': sliding }"
    :style="{ '--slide-progress': progress }"
    role="slider"
    :tabindex="busy ? -1 : 0"
    :aria-label="label"
    :aria-valuemin="0"
    :aria-valuemax="100"
    :aria-valuenow="percent"
    :aria-disabled="busy"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="settle"
    @keydown="onKeydown"
  >
    <div class="slide-confirm-fill" aria-hidden="true" />
    <span class="slide-confirm-label">{{ done ? doneLabel : label }}</span>
    <span class="slide-confirm-knob" aria-hidden="true">
      <VeyqIcon :name="done ? 'check' : 'angle-double-right'" />
    </span>
  </div>
</template>

<style scoped>
/* Knob and trail share one geometry: the knob travels inside the track, the same gap from either end (at 0 its left
   edge is one gap in, at 1 its right edge one gap from the end) — centred on the progress point instead, half of it sat
   outside the rounded track. The trail is the confirm green (styleguide: `--app-primary` confirms) softened into the
   track, so the label stays readable on it in both modes, and it reaches one gap past the knob. */
.slide-confirm {
  --slide-knob: 2.5rem;
  --slide-gap: 0.25rem;
  --slide-travel: calc((100% - var(--slide-knob) - 2 * var(--slide-gap)) * var(--slide-progress, 0));
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 3rem;
  padding: 0 3rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-control-text);
  overflow: hidden;
  cursor: grab;
  touch-action: none;
  user-select: none;
}
.slide-confirm:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.slide-confirm-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.slide-confirm-fill {
  position: absolute;
  inset: 0 auto 0 0;
  width: calc(2 * var(--slide-gap) + var(--slide-knob) + var(--slide-travel));
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-primary) 28%, var(--app-control-bg));
  /* Fades in with the first few pixels of the slide: at rest the track shows no green. */
  opacity: min(1, calc(var(--slide-progress, 0) * 12));
}
.slide-confirm-done .slide-confirm-fill {
  background: color-mix(in srgb, var(--app-primary) 40%, var(--app-control-bg));
}
/* Released short of the end, knob and trail glide back; while dragging they follow the pointer at once. */
.slide-confirm:not(.slide-confirm-sliding) :is(.slide-confirm-fill, .slide-confirm-knob) {
  transition: width 0.25s ease, left 0.25s ease, opacity 0.25s ease, background-color 0.25s ease;
}
@media (prefers-reduced-motion: reduce) {
  .slide-confirm :is(.slide-confirm-fill, .slide-confirm-knob) {
    transition: none !important;
  }
}
.slide-confirm-label {
  position: relative;
  text-align: center;
  font-size: 0.9rem;
}
.slide-confirm-knob {
  position: absolute;
  top: 50%;
  left: calc(var(--slide-gap) + var(--slide-travel));
  translate: 0 -50%;
  display: grid;
  place-items: center;
  width: var(--slide-knob);
  height: var(--slide-knob);
  border-radius: 50%;
  background: var(--app-surface);
  border: 1px solid var(--app-line);
  color: var(--app-muted);
}
.slide-confirm-done {
  cursor: default;
}
.slide-confirm-done .slide-confirm-knob {
  color: var(--app-success);
}
</style>
