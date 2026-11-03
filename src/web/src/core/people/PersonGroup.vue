<script setup lang="ts">
// People as a node header draws them (`NodeHeaderPeopleFact`, `core/node/nodeHeader.ts`; owner 2026-11-03): who was there
// (Memories' participants, ADR 0083), who uploaded a file (Depot, ADR 0083 decision 8) - any application's people, one look.
// One person is their avatar and their name; several are overlapping avatars - up to three, past three the third slot a
// "+N" - whose list (avatar + name) opens in a hover card on hover, on keyboard focus and on click / tap (touch has no hover).
// A former member is greyed, unlinked and pictureless; a person with no member identity (a guest) is their name alone.
//
// **Links and pictures only where `link` says so** (src/web/CLAUDE.md, "A member's profile picture"): a member's page passes
// it, and each member then links to their profile `#people/<userId>` and shows their picture. A share page never does - its
// header defaults to `false` (fail closed) - so no profile link and no member picture fetch reach a guest's page, whatever
// people an application hands it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import VeyqAvatar from '../../shared/ui/VeyqAvatar.vue'
import VeyqAvatarGroup from '../../shared/ui/VeyqAvatarGroup.vue'
import VeyqHoverCard from '../../shared/ui/VeyqHoverCard.vue'
import PersonAvatar from './PersonAvatar.vue'
import { personHash } from '../node/location'
import type { NodeHeaderPerson } from '../node/nodeHeader'

const props = withDefaults(
  defineProps<{
    /** The group's accessible name ("Participants", "Uploaded by"). */
    label: string
    people: readonly NodeHeaderPerson[]
    caption?: string
    status?: string
    /** A member's page: link each member to their profile and show their picture. Default false (a share page). */
    link?: boolean
  }>(),
  { caption: undefined, status: undefined, link: false },
)
const { t } = useI18n()

/** How many avatars are shown before the last slot turns into "+N". */
const SLOTS = 3
const OPEN_DELAY_MS = 150
const CLOSE_DELAY_MS = 250

/** Whether `p` links to a profile and shows a picture: a member's page, a current member. */
const linked = (p: NodeHeaderPerson) => props.link && !p.former && p.userId !== null
const pictureOf = (p: NodeHeaderPerson) => (linked(p) ? p.userId : null)
const single = computed(() => (props.people.length === 1 ? props.people[0]! : null))
const shown = computed(() => (props.people.length > SLOTS ? props.people.slice(0, SLOTS - 1) : props.people))
const overflow = computed(() => (props.people.length > SLOTS ? props.people.length - (SLOTS - 1) : 0))
const summary = computed(() => t('people.groupSummary', { label: props.label, count: props.people.length, names: props.people.map((p) => p.name).join(', ') }))

// The list is a hover card (the layer's `VeyqHoverCard`): it opens on hover or keyboard focus after a short intent delay
// and closes after a grace period, so crossing the gap between the avatars and the list does not flicker it shut; a
// tap or click toggles it. It renders in place (not teleported) to keep the tab order, and stays open while focus is
// anywhere in this group, so a keyboard user can Tab from the avatars into the list.
const trigger = ref<HTMLButtonElement | null>(null)
const root = ref<HTMLElement | null>(null)
const open = ref(false)
let openedAt = 0
const cardOpen = computed({
  get: () => open.value,
  set: (next: boolean) => {
    if (!next && root.value?.contains(document.activeElement)) return
    if (next && !open.value) openedAt = Date.now()
    open.value = next
  },
})
function closeNow(refocus = false) {
  if (!open.value) return
  open.value = false
  if (refocus) trigger.value?.focus()
}
/** A tap / click toggles - but not the click that follows the focus / hover that just opened it. */
function onClick() {
  if (open.value && Date.now() - openedAt > 400) closeNow()
  else {
    openedAt = Date.now()
    open.value = true
  }
}
function onFocusOut(e: FocusEvent) {
  const next = e.relatedTarget as Node | null
  if (!next || !(e.currentTarget as HTMLElement).contains(next)) closeNow()
}
</script>

<template>
  <div
    ref="root"
    class="veyq-person-group"
    role="group"
    :aria-label="label"
    data-testid="person-group"
    @keydown.esc="closeNow(true)"
    @focusout="onFocusOut"
  >
    <!-- The space is real text, so the line reads "Uploaded by Anna" to assistive technology too (the avatar is decorative). -->
    <template v-if="caption"><span class="veyq-person-group-caption">{{ caption }}</span>{{ ' ' }}</template>
    <!-- One person: their avatar and name, a link to their profile on a member's page. -->
    <template v-if="single">
      <RouterLink
        v-if="linked(single)"
        class="veyq-person-row"
        :to="{ hash: personHash(single.userId!) }"
        :aria-label="t('people.open', { name: single.name })"
        data-testid="person-link"
      >
        <PersonAvatar :name="single.name" :user-id="pictureOf(single)" data-testid="person-avatar" />
        <span class="veyq-person-name">{{ single.name }}</span>
      </RouterLink>
      <span v-else-if="single.former" class="veyq-person-row is-former" :title="t('people.formerMemberHint')" data-testid="person-former">
        <PersonAvatar :name="single.name" data-testid="person-avatar" />
        <span class="veyq-person-name">{{ single.name }}</span>
      </span>
      <span v-else-if="single.userId === null" class="veyq-person-row" data-testid="person-name">
        <span class="veyq-person-name">{{ single.name }}</span>
      </span>
      <span v-else class="veyq-person-row" data-testid="person-name">
        <PersonAvatar :name="single.name" data-testid="person-avatar" />
        <span class="veyq-person-name">{{ single.name }}</span>
      </span>
    </template>
    <!-- Several: the avatars, and everyone in a hover card. -->
    <div v-else-if="people.length" class="veyq-person-group-anchor">
      <VeyqHoverCard v-model:open="cardOpen" :open-delay="OPEN_DELAY_MS" :close-delay="CLOSE_DELAY_MS" in-place class="veyq-person-group-popover">
        <template #trigger>
          <button
            ref="trigger"
            type="button"
            class="veyq-person-group-trigger"
            aria-haspopup="dialog"
            :aria-expanded="open"
            :aria-label="summary"
            data-testid="person-group-trigger"
            @click="onClick"
          >
            <VeyqAvatarGroup>
              <PersonAvatar v-for="(p, i) in shown" :key="p.userId ?? `n${i}`" :name="p.name" :user-id="pictureOf(p)" :class="{ 'is-former': p.former }" data-testid="person-avatar" />
              <VeyqAvatar v-if="overflow" :label="`+${overflow}`" data-testid="person-overflow" />
            </VeyqAvatarGroup>
          </button>
        </template>
        <ul class="veyq-person-list" data-testid="person-list">
          <li v-for="(p, i) in people" :key="p.userId ?? `n${i}`">
            <RouterLink
              v-if="linked(p)"
              class="veyq-person-row"
              :to="{ hash: personHash(p.userId!) }"
              :aria-label="t('people.open', { name: p.name })"
              data-testid="person-link"
              @click="closeNow()"
            >
              <PersonAvatar :name="p.name" :user-id="pictureOf(p)" />
              <span class="veyq-person-name">{{ p.name }}</span>
            </RouterLink>
            <span v-else class="veyq-person-row" :class="{ 'is-former': p.former }" :title="p.former ? t('people.formerMemberHint') : undefined" :data-testid="p.former ? 'person-former' : 'person-name'">
              <PersonAvatar :name="p.name" />
              <span class="veyq-person-name">{{ p.name }}</span>
            </span>
          </li>
        </ul>
      </VeyqHoverCard>
    </div>
    <span v-if="status" class="veyq-person-group-status" role="status">{{ status }}</span>
  </div>
</template>

<style scoped>
.veyq-person-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.3rem 0.4rem;
  color: var(--app-muted);
  font-size: 0.92rem;
}
/* Deliberately NOT `position: relative`: the popover renders in place (tab order) but is placed in
 * DOCUMENT coordinates, so a positioned anchor would offset it a second time. */
.veyq-person-group-anchor {
  display: inline-block;
}
/* The header pulls its fact rows left by a chip's padding (`.veyq-node-header-facts`); the avatars' trigger and a person's row
 * carry that padding, the caption and the status get it here, so every first glyph starts flush with the name. */
.veyq-person-group-caption,
.veyq-person-group-status {
  padding-inline-start: 0.45rem;
}
.veyq-person-group-trigger {
  display: inline-flex;
  padding: 0.15rem 0.45rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
}
.veyq-person-group-trigger:hover {
  background: var(--app-hover);
}
.veyq-person-group-trigger:focus-visible,
a.veyq-person-row:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.veyq-person-group-trigger :deep(.veyq-avatar) {
  border: 2px solid var(--app-surface);
}
/* The group overlaps by 1rem, which hides half of a letter; half of that still reads as a stack. */
.veyq-person-group-trigger :deep(.veyq-avatar-group .veyq-avatar + .veyq-avatar) {
  margin-inline-start: -0.5rem;
}
.veyq-person-group-trigger :deep(.is-former) {
  opacity: 0.6;
}
.veyq-person-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.1rem;
  min-width: 11rem;
  max-height: 50vh;
  overflow-y: auto;
}
.veyq-person-row {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.2rem 0.45rem;
  border-radius: var(--app-radius-sm);
  color: var(--app-text);
  font-size: 0.92rem;
  font-weight: 600;
  text-decoration: none;
}
.veyq-person-list .veyq-person-row {
  display: flex;
}
a.veyq-person-row:hover {
  background: var(--app-hover);
}
.veyq-person-row.is-former {
  color: var(--app-muted);
  font-weight: 500;
  font-style: italic;
}
.veyq-person-row.is-former :deep(.veyq-avatar) {
  opacity: 0.6;
}
.veyq-person-group-status {
  color: var(--app-muted);
  font-size: 0.9rem;
}
</style>
