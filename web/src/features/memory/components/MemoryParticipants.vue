<script setup lang="ts">
// The Memory header's "who was there" row (uc.memory.participants, ADR 0083): the participants as overlapping avatars - up
// to three; past three the third slot is a "+N" - and, where the meta may be written, an add / edit control that opens the
// picker. The whole list (avatar + name, each a link to the member's profile `#people/<userId>`) is in a popover that
// opens on hover, on keyboard focus and on click / tap (touch has no hover). A former member is greyed with no link. The
// interface shows a guest NOTHING of this (the `participants` capability is a member's alone): the host passes `show`
// false and this renders no element at all - not even an empty row.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import Avatar from 'primevue/avatar'
import AvatarGroup from 'primevue/avatargroup'
import Popover from 'primevue/popover'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import PersonAvatar from './PersonAvatar.vue'
import { personHash } from '../../../core/node/location'
import { useMemoryParticipants } from '../useMemoryParticipants'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import type { TreeNode } from '../../../core/node/tree'
import type { NodeMetaDoor } from '../source'

const props = defineProps<{
  scopeId: string
  node: TreeNode
  memoryKey: Uint8Array | null
  /** The `participants` capability, and not a personal scope. */
  show: boolean
  /** The node-meta door when the meta may be written, else null. */
  write: NodeMetaDoor | null
}>()
const emit = defineEmits<{ metaUpdated: [NodeMetaUpdate] }>()
const { t } = useI18n()

/** How many avatars the header shows before the last slot turns into "+N". */
const SLOTS = 3
const OPEN_DELAY_MS = 150
const CLOSE_DELAY_MS = 250

const p = useMemoryParticipants({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  show: () => props.show,
  write: () => props.write,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})
const visible = computed(() => props.show && (p.ids.value.length > 0 || p.canEdit.value))
const chips = p.chips
const shown = computed(() => (chips.value.length > SLOTS ? chips.value.slice(0, SLOTS - 1) : chips.value))
const overflow = computed(() => (chips.value.length > SLOTS ? chips.value.length - (SLOTS - 1) : 0))
const summary = computed(() => t('memory.participants.summary', { count: chips.value.length, names: chips.value.map((c) => c.name).join(', ') }))

// The popover opens on hover / focus after a short intent delay and closes after a grace period, so crossing the gap
// between the avatars and the list does not flicker it shut. It renders in place (not teleported) to keep the tab order.
const pop = ref<InstanceType<typeof Popover> | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const open = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let openedAt = 0
function clear() {
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
}
function openNow() {
  clear()
  if (open.value || !trigger.value) return
  open.value = true
  openedAt = Date.now()
  // the popover reads `currentTarget` to tell a click on its trigger from an outside click (which dismisses it)
  pop.value?.show({ currentTarget: trigger.value } as unknown as Event, trigger.value)
}
function closeNow(refocus = false) {
  clear()
  if (!open.value) return
  open.value = false
  pop.value?.hide()
  if (refocus) trigger.value?.focus()
}
function openSoon() {
  clear()
  if (!open.value) timer = setTimeout(openNow, OPEN_DELAY_MS)
}
function closeSoon() {
  clear()
  if (open.value) timer = setTimeout(() => closeNow(), CLOSE_DELAY_MS)
}
/** A tap / click toggles - but not the click that follows the focus / hover that just opened it. */
function onClick() {
  if (open.value && Date.now() - openedAt > 400) closeNow()
  else openNow()
}
function onFocusOut(e: FocusEvent) {
  const next = e.relatedTarget as Node | null
  if (!next || !(e.currentTarget as HTMLElement).contains(next)) closeSoon()
}
onBeforeUnmount(clear)
</script>

<template>
  <div
    v-if="visible"
    class="memory-participants"
    role="group"
    :aria-label="t('memory.participants.title')"
    data-testid="memory-participants"
    @keydown.esc="closeNow(true)"
    @focusout="onFocusOut"
  >
    <div v-if="chips.length" class="memory-participants-anchor" @mouseenter="openSoon" @mouseleave="closeSoon">
      <button
        ref="trigger"
        type="button"
        class="memory-participants-group"
        aria-haspopup="dialog"
        :aria-expanded="open"
        :aria-label="summary"
        data-testid="participants-group"
        @click="onClick"
        @focus="openSoon"
      >
        <AvatarGroup>
          <PersonAvatar v-for="c in shown" :key="c.userId" :name="c.name" :class="{ 'is-former': c.former }" data-testid="participant-avatar" />
          <Avatar v-if="overflow" :label="`+${overflow}`" shape="circle" aria-hidden="true" data-testid="participant-overflow" />
        </AvatarGroup>
      </button>
      <Popover ref="pop" append-to="self" class="memory-participants-popover" @hide="open = false" @mouseenter="clear" @mouseleave="closeSoon">
        <ul class="memory-participants-list" data-testid="participants-list">
          <li v-for="c in chips" :key="c.userId">
            <span v-if="c.former" class="memory-participant-row is-former" data-testid="participant-former">
              <PersonAvatar :name="c.name" />
              <span class="memory-participant-name">{{ c.name }}</span>
            </span>
            <RouterLink
              v-else
              class="memory-participant-row"
              :to="{ hash: personHash(c.userId) }"
              :aria-label="t('memory.participants.open', { name: c.name })"
              data-testid="participant-link"
              @click="closeNow()"
            >
              <PersonAvatar :name="c.name" />
              <span class="memory-participant-name">{{ c.name }}</span>
            </RouterLink>
          </li>
        </ul>
      </Popover>
    </div>
    <span v-if="p.namesPending.value" class="memory-participants-muted" role="status">{{ t('people.loading') }}</span>
    <span v-else-if="p.loadFailed.value && p.ids.value.length > 0" class="memory-participants-muted">{{ t('memory.participants.loadError') }}</span>
    <span v-else-if="p.ids.value.length === 0" class="memory-participants-muted">{{ t('memory.participants.none') }}</span>
    <button
      v-if="p.canEdit.value"
      type="button"
      class="memory-participants-edit"
      :aria-label="p.ids.value.length ? t('memory.participants.edit') : t('memory.participants.add')"
      :title="p.ids.value.length ? t('memory.participants.edit') : t('memory.participants.add')"
      data-testid="participants-edit"
      @click="p.openPicker()"
    >
      <span class="pi" :class="p.ids.value.length ? 'pi-pencil' : 'pi-user-plus'" />
      <span v-if="!p.ids.value.length">{{ t('memory.participants.add') }}</span>
    </button>
    <MemoryParticipantsDialog
      v-if="p.pickerOpen.value && p.canEdit.value"
      :people="p.people.value"
      :load-failed="p.loadFailed.value"
      :selected="p.ids.value"
      :max="p.max"
      @save="p.onPickerSave"
      @retry="p.ensurePeople(true)"
      @close="p.pickerOpen.value = false"
    />
  </div>
</template>

<style scoped>
.memory-participants {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.3rem 0.4rem;
  /* the same optical pull as the date and place chips, so the first avatar starts flush with the title */
  margin-left: -0.45rem;
}
/* Deliberately NOT `position: relative`: the Popover is appended to this element (tab order) but PrimeVue places it in
 * DOCUMENT coordinates, so a positioned anchor would offset it a second time. */
.memory-participants-anchor {
  display: inline-block;
}
.memory-participants-group {
  display: inline-flex;
  padding: 0.15rem 0.45rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
}
.memory-participants-group:hover {
  background: var(--app-hover);
}
.memory-participants-group:focus-visible,
.memory-participants-edit:focus-visible,
a.memory-participant-row:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.memory-participants-group :deep(.p-avatar) {
  border: 2px solid var(--app-surface);
}
/* PrimeVue overlaps by 1rem, which hides half of a letter; half of that still reads as a stack. */
.memory-participants-group :deep(.p-avatar-group .p-avatar + .p-avatar) {
  margin-inline-start: -0.5rem;
}
.memory-participants-group :deep(.is-former) {
  opacity: 0.6;
}
.memory-participants-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.1rem;
  min-width: 11rem;
  max-height: 50vh;
  overflow-y: auto;
}
.memory-participant-row {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  padding: 0.3rem 0.4rem;
  border-radius: var(--app-radius-sm);
  color: var(--app-text);
  font-size: 0.92rem;
  font-weight: 600;
  text-decoration: none;
}
a.memory-participant-row:hover {
  background: var(--app-hover);
}
.memory-participant-row.is-former {
  color: var(--app-muted);
  font-weight: 500;
  font-style: italic;
  opacity: 0.85;
}
.memory-participants-muted {
  color: var(--app-muted);
  font-size: 0.9rem;
  padding: 0 0.45rem;
}
.memory-participants-edit {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.45rem;
  border: none;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  font-size: 0.9rem;
  cursor: pointer;
}
.memory-participants-edit:hover {
  background: var(--app-hover);
}
.memory-participants-edit .pi {
  color: var(--app-accent);
}
</style>
