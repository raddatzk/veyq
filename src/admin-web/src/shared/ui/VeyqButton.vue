<script setup lang="ts">
// The app's button (ADR 0107). Its props are ours: `variant` says what the action is (the positive primary, a neutral
// secondary, a destructive danger, a warning), `appearance` how loud it is (filled, outlined, text, or link: a text
// button that underlines on hover instead of filling). With `href` it is a link that looks like a button. While
// `loading` it is disabled and shows a spinner in place of its icon. Every other attribute (aria-*, data-*, class)
// lands on the element itself, and a sheet reaches it and its parts by the layer's classes: `veyq-button`,
// `veyq-button--<variant>`, `veyq-button--<appearance>`, `veyq-button__icon`, `veyq-button__label` (styled in
// `layer.css`).
import { computed } from 'vue'
import VeyqIcon from './VeyqIcon.vue'
import type { VeyqIconName } from './icons'

export type VeyqButtonVariant = 'primary' | 'secondary' | 'danger' | 'warn'
export type VeyqButtonAppearance = 'filled' | 'outlined' | 'text' | 'link'

const props = withDefaults(
  defineProps<{
    label?: string
    icon?: VeyqIconName
    iconPos?: 'left' | 'right'
    variant?: VeyqButtonVariant
    appearance?: VeyqButtonAppearance
    size?: 'small' | 'normal'
    loading?: boolean
    disabled?: boolean
    type?: 'button' | 'submit' | 'reset'
    href?: string
    target?: string
    rel?: string
  }>(),
  { variant: 'primary', appearance: 'filled', size: 'normal', type: 'button', iconPos: 'left' },
)

// Spelled out, so `validate_stylesheet_anchors.py` sees each class the sheet styles as one this markup applies.
const VARIANT_CLASS: Record<VeyqButtonVariant, string> = {
  primary: 'veyq-button--primary',
  secondary: 'veyq-button--secondary',
  danger: 'veyq-button--danger',
  warn: 'veyq-button--warn',
}
const APPEARANCE_CLASS: Record<VeyqButtonAppearance, string> = {
  filled: 'veyq-button--filled',
  outlined: 'veyq-button--outlined',
  text: 'veyq-button--text',
  link: 'veyq-button--link',
}

const classes = computed(() => [
  'veyq-button',
  VARIANT_CLASS[props.variant],
  APPEARANCE_CLASS[props.appearance],
  { 'veyq-button--small': props.size === 'small', 'veyq-button--icon-only': !props.label, 'veyq-button--loading': props.loading },
])
const inert = computed(() => props.disabled || props.loading)
</script>

<template>
  <a
    v-if="href"
    :class="classes"
    :href="inert ? undefined : href"
    :target="target"
    :rel="rel"
    :aria-disabled="inert || undefined"
  >
    <VeyqIcon v-if="loading" name="spinner" spin class="veyq-button__icon" />
    <VeyqIcon v-else-if="icon" :name="icon" :class="['veyq-button__icon', { 'veyq-button__icon--right': iconPos === 'right' }]" />
    <span v-if="label" class="veyq-button__label">{{ label }}</span>
  </a>
  <button v-else :type="type" :class="classes" :disabled="inert">
    <VeyqIcon v-if="loading" name="spinner" spin class="veyq-button__icon" />
    <VeyqIcon v-else-if="icon" :name="icon" :class="['veyq-button__icon', { 'veyq-button__icon--right': iconPos === 'right' }]" />
    <span v-if="label" class="veyq-button__label">{{ label }}</span>
  </button>
</template>
