<script setup lang="ts">
// A member's profile page, `#people/<userId>` (ADR 0083 decision 6, uc.memory.participants). Members only: it is a route of
// the unlocked shell (`MemoryHomeView`), the share page has no such route and links to none. Core draws the page - the
// name by the display rule (real name, else username), a letter avatar (a picture is its own later slice, inbox #414) -
// and each application adds what it knows of the person as a SECTION (`registry.claimPersonSection`): Memories' "Memories
// with this person". A user id the tenant directory does not list is a former member: greyed, no sections' data
// is withheld from it (the sections still run - a former member can have been on a Memory), but nothing links to anything.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Avatar from 'primevue/avatar'
import { registry } from '../applications/registry'
import { loadTenantPeople, type Person } from './directory'

const props = defineProps<{ userId: string }>()
const { t } = useI18n()

type State = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; person: Person | null }
const state = ref<State>({ kind: 'loading' })
let token = 0

async function load() {
  const mine = ++token
  state.value = { kind: 'loading' }
  try {
    const people = await loadTenantPeople()
    if (mine === token) state.value = { kind: 'ready', person: people.get(props.userId) ?? null }
  } catch {
    if (mine === token) state.value = { kind: 'error' }
  }
}
onMounted(load)
watch(() => props.userId, load)

const sections = registry.personSections()
const person = computed(() => (state.value.kind === 'ready' ? state.value.person : null))
const title = computed(() => person.value?.displayName ?? t('people.formerMember'))
const initial = computed(() => (person.value?.displayName ?? '?').trim().charAt(0).toUpperCase() || '?')
</script>

<template>
  <section class="person-profile" aria-labelledby="person-title" data-testid="person-profile">
    <p v-if="state.kind === 'loading'" class="person-state" role="status">{{ t('people.loading') }}</p>
    <div v-else-if="state.kind === 'error'" class="person-state" role="alert">
      <p>{{ t('people.loadError') }}</p>
      <button type="button" class="person-retry" @click="load">{{ t('people.retry') }}</button>
    </div>
    <template v-else>
      <header class="person-head" :class="{ 'is-former': !person }">
        <Avatar :label="initial" shape="circle" size="xlarge" class="person-avatar" aria-hidden="true" />
        <div>
          <h1 id="person-title" class="person-title">{{ title }}</h1>
          <p v-if="person && person.realName" class="person-username">@{{ person.username }}</p>
          <p v-else-if="!person" class="person-username">{{ t('people.formerMemberHint') }}</p>
        </div>
      </header>
      <component :is="section" v-for="(section, i) in sections" :key="i" :user-id="userId" :name="person?.displayName ?? ''" />
    </template>
  </section>
</template>

<style scoped>
.person-profile {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.person-head {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.person-head.is-former {
  color: var(--app-muted);
}
.person-avatar {
  flex: none;
}
.person-title {
  margin: 0;
  font-size: 1.5rem;
}
.person-username {
  margin: 0.15rem 0 0;
  color: var(--app-muted);
}
.person-state {
  color: var(--app-muted);
}
.person-retry {
  margin-top: 0.5rem;
  padding: 0.35rem 0.8rem;
  border: 1px solid var(--app-control-line);
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  cursor: pointer;
}
</style>
