<script setup lang="ts">
// The one way a screen draws an icon (ADR 0107 decision 5): our name, drawn by Lucide as inline SVG (no icon font).
// Decorative (`aria-hidden`): the control that carries it owns the accessible name. The root is a `span.veyq-icon` with
// a 1em SVG inside, the box model the glyph font had: a sheet sizes the drawing with `font-size`, tints it with `color`,
// and may give the span itself a box (a tile with a background) without stretching the drawing. `data-icon` carries
// the name. `spin` turns it, for a busy state.
import { computed } from 'vue'
import {
  ArrowRightIcon,
  BanIcon,
  CheckIcon,
  ChevronDownIcon,
  CircleCheckIcon,
  CircleMinusIcon,
  CircleXIcon,
  CopyIcon,
  DatabaseIcon,
  DownloadIcon,
  EyeIcon,
  EyeOffIcon,
  GlobeIcon,
  IdCardIcon,
  InfoIcon,
  KeyRoundIcon,
  LoaderCircleIcon,
  LockIcon,
  LockOpenIcon,
  LogInIcon,
  LogOutIcon,
  MailIcon,
  PauseIcon,
  PlayIcon,
  PlusIcon,
  PowerIcon,
  RefreshCwIcon,
  SaveIcon,
  SendIcon,
  ServerIcon,
  ShieldIcon,
  SlidersHorizontalIcon,
  TagIcon,
  Trash2Icon,
  Undo2Icon,
  UserMinusIcon,
  UserPlusIcon,
  UsersIcon,
  XIcon,
  type LucideIcon,
} from '@lucide/vue'
import type { VeyqIconName } from './icons'

const DRAWN: Record<VeyqIconName, LucideIcon> = {
  'arrow-right': ArrowRightIcon,
  'ban': BanIcon,
  'check': CheckIcon,
  'check-circle': CircleCheckIcon,
  'chevron-down': ChevronDownIcon,
  'copy': CopyIcon,
  'database': DatabaseIcon,
  'download': DownloadIcon,
  'envelope': MailIcon,
  'eye': EyeIcon,
  'eye-slash': EyeOffIcon,
  'globe': GlobeIcon,
  'id-card': IdCardIcon,
  'info-circle': InfoIcon,
  'key': KeyRoundIcon,
  'lock': LockIcon,
  'lock-open': LockOpenIcon,
  'minus-circle': CircleMinusIcon,
  'pause': PauseIcon,
  'play': PlayIcon,
  'plus': PlusIcon,
  'power-off': PowerIcon,
  'refresh': RefreshCwIcon,
  'save': SaveIcon,
  'send': SendIcon,
  'server': ServerIcon,
  'shield': ShieldIcon,
  'sign-in': LogInIcon,
  'sign-out': LogOutIcon,
  'sliders-h': SlidersHorizontalIcon,
  'spinner': LoaderCircleIcon,
  'tag': TagIcon,
  'times': XIcon,
  'times-circle': CircleXIcon,
  'trash': Trash2Icon,
  'undo': Undo2Icon,
  'unlock': LockOpenIcon,
  'user-minus': UserMinusIcon,
  'user-plus': UserPlusIcon,
  'users': UsersIcon,
}

const props = defineProps<{
  name: VeyqIconName
  spin?: boolean
}>()

const icon = computed(() => DRAWN[props.name])
</script>

<template>
  <span :class="['veyq-icon', { 'veyq-icon--spin': spin }]" :data-icon="name" aria-hidden="true">
    <component :is="icon" class="veyq-icon__svg" fill="none" focusable="false" />
  </span>
</template>
