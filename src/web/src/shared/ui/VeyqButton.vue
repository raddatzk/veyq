<script setup lang="ts">
// The app's button (ADR 0107). Its props are ours: `variant` says what the action is (the positive primary, a neutral
// secondary, a destructive danger, a warning), `appearance` how loud it is (filled, outlined, text). With `href` it is
// a link that looks like a button. Every other attribute (aria-*, data-*, class) lands on the element itself, and a
// sheet reaches it and its parts by the layer's classes: `veyq-button`, `veyq-button--<variant>`,
// `veyq-button--<appearance>`, `veyq-button__icon`, `veyq-button__label`.
import { computed } from 'vue'
import Button from 'primevue/button'
import type { VeyqIconName } from './icons'

export type VeyqButtonVariant = 'primary' | 'secondary' | 'danger' | 'warn'
export type VeyqButtonAppearance = 'filled' | 'outlined' | 'text'

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

const PT = {
  icon: { class: 'veyq-button__icon' },
  loadingIcon: { class: 'veyq-button__icon' },
  label: { class: 'veyq-button__label' },
}

const classes = computed(() => [
  'veyq-button',
  `veyq-button--${props.variant}`,
  `veyq-button--${props.appearance}`,
  { 'veyq-button--small': props.size === 'small', 'veyq-button--icon-only': !props.label },
])
</script>

<template>
  <Button
    :as="href ? 'a' : undefined"
    :href="href"
    :target="target"
    :rel="rel"
    :type="href ? undefined : type"
    :label="label"
    :icon="icon ? `pi pi-${icon}` : undefined"
    :icon-pos="iconPos"
    :severity="variant === 'primary' ? undefined : variant"
    :outlined="appearance === 'outlined'"
    :text="appearance === 'text'"
    :size="size === 'small' ? 'small' : undefined"
    :loading="loading"
    :disabled="disabled"
    :class="classes"
    :pt="PT"
  />
</template>
