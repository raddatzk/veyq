<script setup lang="ts">
// A member's profile page, `#people/<userId>` (ADR 0083 decision 6, uc.memory.participants). Members only: it is a route of
// the unlocked shell (`MemoryHomeView`), the share page has no such route and links to none. Core draws the page - the
// name by the display rule (real name, else username), the avatar (the member's picture, else the letter; ADR 0084) -
// and each application adds what it knows of the person as a SECTION (`registry.claimPersonSection`): Memories' "Memories
// with this person". A user id the tenant directory does not list is a former member: greyed, no sections' data
// is withheld from it (the sections still run - a former member can have been on a Memory), but nothing links to anything.
// On a member's OWN page (`userId` is the signed-in account) an "Edit profile" control opens the shared name + picture editor
// (`NamePictureFields`, the same one Settings > Your profile uses); on anyone else's page there is no edit control at all.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { registry } from '../applications/registry'
import { loadTenantPeople, personNow, refreshPeopleDirectory, type Person } from './directory'
import PersonAvatar from './PersonAvatar.vue'
import NamePictureFields from '../../features/account/profile/NamePictureFields.vue'
import { useNameAndPicture } from '../../features/account/profile/useNameAndPicture'
import { updateMyProfile } from '../../features/account/profile/profile'
import { account } from '../../app/account'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'

const props = defineProps<{ userId: string }>()
const { t } = useI18n()

type State = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; person: Person | null }
const state = ref<State>({ kind: 'loading' })
let token = 0

async function load() {
  const mine = ++token
  // Whoever the device already knows is shown at once; the read below only confirms (or finds a former member).
  const known = personNow(props.userId)
  state.value = known ? { kind: 'ready', person: known } : { kind: 'loading' }
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

// Editing one's own name and picture: only when this page is the signed-in member's.
const toast = useToast()
const isMe = computed(() => Boolean(person.value) && Boolean(account.userId) && account.userId === props.userId)
const identity = useNameAndPicture()
const editing = ref(false)
const editError = ref('')
const { run: runLoadOwn, pending: loadingOwn } = useAsyncAction(async () => {
  await identity.load()
  editing.value = true
}, { onError: () => { editError.value = t('profilePicture.loadFailed') } })
const { run: runSaveOwn, pending: savingOwn } = useAsyncAction(async () => {
  await updateMyProfile({ sealedName: identity.sealedName() })
  await identity.savePicture()
  await refreshPeopleDirectory()
  editing.value = false
  toast.success(t('profile.saved'))
  await load()
}, { onError: () => { editError.value = t('profilePicture.saveFailed') } })
function startEdit() {
  editError.value = ''
  void runLoadOwn()
}
function cancelEdit() {
  identity.discard()
  editing.value = false
}
watch(() => props.userId, () => { editing.value = false; identity.discard() })
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
        <PersonAvatar :name="title" :user-id="person ? userId : null" size="xxl" class="person-avatar" />
        <div class="person-head-text">
          <h1 id="person-title" class="person-title">{{ title }}</h1>
          <p v-if="person && person.realName" class="person-username">~{{ person.username }}</p>
          <p v-else-if="!person" class="person-username">{{ t('people.formerMemberHint') }}</p>
        </div>
        <Button
          v-if="isMe && !editing"
          type="button"
          class="person-edit"
          :label="t('profilePicture.editProfile')"
          icon="pi pi-pencil"
          severity="secondary"
          outlined
          size="small"
          :loading="loadingOwn"
          data-testid="person-edit"
          @click="startEdit"
        />
      </header>
      <Message v-if="isMe && !editing && editError" severity="error" :closable="false" role="alert">{{ editError }}</Message>
      <form v-if="isMe && editing" class="veyq-form person-edit-form" data-testid="person-edit-form" @submit.prevent="runSaveOwn()">
        <NamePictureFields :model="identity" :display-name="title" />
        <Message v-if="editError" severity="error" :closable="false" role="alert">{{ editError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="savingOwn" @click="cancelEdit" />
          <Button type="submit" :label="t('profile.save')" icon="pi pi-check" :loading="savingOwn" />
        </div>
      </form>
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
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem 1rem;
}
.person-head.is-former {
  color: var(--app-muted);
}
.person-avatar {
  flex: none;
}
.person-head-text {
  /* beside the avatar while 10rem fit; on a phone the edit control drops to its own line instead of crushing the name */
  flex: 1 1 10rem;
  min-width: 0;
}
.person-edit {
  margin-inline-start: auto;
}
.person-edit-form {
  max-width: 32rem;
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
