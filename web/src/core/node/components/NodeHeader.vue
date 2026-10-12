<script setup lang="ts">
// The header of every node page and level (owner design 2026-10-12, inbox #405): ONE core component, so a folder, a file,
// a drop box and a Memory open the same way. Left a LEADING VISUAL (the `leading` slot — an application's cover image —
// or, by default, the kind's `icon`), then the NAME (`title`, optionally a `subtitle` and a `meta` slot below it), and
// the ACTIONS on the right (`NodeHeaderAction`, `../nodeHeader.ts`). The application feeds data and its capability
// answers; core owns the look, the spacing, the wrap on a narrow screen, the keyboard (every action is a real, labelled
// `<button>`) and the dark mode (`styles/ui.css` tokens). It decides nothing about what is offered: an action not
// `shown` is not rendered (fail closed is the caller's capability function), and a guest sees this very header with
// fewer actions (inbox #401).
//
// `level` is the heading level of the title: a node's own page is the page's `h1`, a folder's header inside the level view `h2`.
import { computed, useSlots } from 'vue'
import Button from 'primevue/button'
import { mergeHeaderActions, type CoreHeaderAction, type NodeHeaderAction } from '../nodeHeader'

const props = withDefaults(
  defineProps<{
    title: string
    /** The PrimeIcons class of the leading visual when no `leading` slot is given. */
    icon?: string
    subtitle?: string
    actions?: readonly NodeHeaderAction[]
    /** Core's own actions next to the application's (a future favorite heart), each gated by a core capability function. */
    coreActions?: readonly CoreHeaderAction[]
    level?: 1 | 2
  }>(),
  { icon: undefined, subtitle: undefined, actions: () => [], coreActions: () => [], level: 2 },
)
const slots = useSlots()
const shown = computed(() => mergeHeaderActions(props.coreActions, props.actions).filter((a) => a.shown !== false))
const hasLeading = computed(() => !!slots.leading || !!props.icon)
</script>

<template>
  <header class="veyq-node-header" :class="{ 'has-visual': !!slots.leading }">
    <div v-if="hasLeading" class="veyq-node-header-leading">
      <slot name="leading"><span :class="[icon, 'veyq-node-header-icon']" aria-hidden="true" /></slot>
    </div>
    <div class="veyq-node-header-body">
      <div class="veyq-node-header-titlerow">
        <component :is="`h${level}`" class="veyq-node-header-title">{{ title }}</component>
        <div v-if="shown.length" class="veyq-node-header-actions">
          <Button
            v-for="a in shown"
            :key="a.id"
            v-tooltip.bottom="a.label"
            type="button"
            class="veyq-icon-button veyq-prime-icon-button veyq-node-header-action"
            :class="[a.class, { 'veyq-danger-action': a.severity === 'danger', 'is-pressed': a.pressed === true }]"
            :data-action="a.id"
            :icon="a.icon"
            :aria-label="a.label"
            :aria-pressed="a.pressed === undefined ? undefined : a.pressed"
            :severity="a.severity === 'danger' ? 'danger' : a.severity === 'primary' ? undefined : 'secondary'"
            :outlined="a.severity !== 'primary'"
            size="small"
            :disabled="a.disabled"
            @click="a.onClick()"
          />
        </div>
      </div>
      <p v-if="subtitle" class="veyq-node-header-subtitle">{{ subtitle }}</p>
      <slot name="meta" />
    </div>
  </header>
</template>
