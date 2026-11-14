<script setup lang="ts">
// A member's profile page, `#people/<username>` (ADR 0083 decisions 5-6, uc.memory.participants). Members only: it is a route of
// the unlocked shell (`SpaceView`), the share page has no such route and links to none. Core draws the page - the
// name by the display rule (real name, else username), the avatar (the member's picture, else the letter; ADR 0084) -
// and each application adds what it knows of the person as a SECTION (`app.personSection`, its registrar's): Memories' "Memories
// with this person". A user id the tenant directory does not list is a former member: greyed, no sections' data
// is withheld from it (the sections still run - a former member can have been on a Memory), but nothing links to anything.
// The page is handed the token of its address (`token`): a username, resolved against the tenant roster here
// (`findPerson`), or a user id - a former member's link, or one written before usernames. A username no current member
// holds (renamed, or never one) names nobody: the page says so and draws no section, as there is no id to ask them about.
// On a member's OWN page (`userId` is the signed-in account) an "Edit profile" control opens the shared name + picture editor
// (`NamePictureFields`, the same one Settings > Your profile uses); on anyone else's page there is no edit control at all.
// The safety number (ADR 0028 Decision 3, ADR 0111 decisions 14-15): my own page shows MY number, my chain code and their QR
// (`MySafetyNumber`); a current member's page shows theirs in the active tenant, with the compare that verifies them there
// (`PersonSafetyNumber`). A former member's page shows neither.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { registry } from '../applications/registry'
import { findPerson, loadTenantPeople, personOfTokenNow, refreshPeopleDirectory, type Person } from './directory'
import { isUserIdToken } from '../node/location'
import PersonAvatar from './PersonAvatar.vue'
import MySafetyNumber from './MySafetyNumber.vue'
import PersonSafetyNumber from './PersonSafetyNumber.vue'
import NamePictureFields from '../../features/account/profile/NamePictureFields.vue'
import { useNameAndPicture } from '../../features/account/profile/useNameAndPicture'
import { updateMyProfile } from '../../features/account/profile/profile'
import { account } from '../../app/account'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'

/** The token of the address, `#people/<token>`: a username or a user id ({@link isUserIdToken}). */
const props = defineProps<{ token: string }>()
const { t } = useI18n()

type State = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; person: Person | null }
/** Who the page is about: the member the token names, else - a token shaped like an id - that id (a former member); null
 *  for a username nobody holds. */
const userIdOf = (person: Person | null): string | null => person?.userId ?? (isUserIdToken(props.token) ? props.token : null)
const state = ref<State>({ kind: 'loading' })
let generation = 0

async function load() {
  const mine = ++generation
  // Whoever the device already knows is shown at once; the read below only confirms (or finds a former member).
  const known = personOfTokenNow(props.token)
  state.value = known ? { kind: 'ready', person: known } : { kind: 'loading' }
  try {
    const people = await loadTenantPeople()
    if (mine === generation) state.value = { kind: 'ready', person: findPerson(people, props.token) ?? null }
  } catch {
    if (mine === generation) state.value = { kind: 'error' }
  }
}
onMounted(load)
watch(() => props.token, load)

const sections = registry.personSections()
const person = computed(() => (state.value.kind === 'ready' ? state.value.person : null))
const userId = computed(() => userIdOf(person.value))
const title = computed(() => person.value?.displayName ?? (userId.value ? t('people.formerMember') : t('people.unknownMember')))

// Editing one's own name and picture: only when this page is the signed-in member's.
const toast = useToast()
const isMe = computed(() => Boolean(person.value) && Boolean(account.userId) && account.userId === userId.value)
const identity = useNameAndPicture()
const editing = ref(false)
const editError = ref('')
const { run: runLoadOwn, pending: loadingOwn } = useAsyncAction(async () => {
  await identity.load()
  editing.value = true
}, { onError: () => { editError.value = t('profilePicture.loadFailed') } })
const { run: runSaveOwn, pending: savingOwn } = useAsyncAction(async () => {
  await identity.saveName((name) => updateMyProfile({ ...name }))
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
watch(userId, () => { editing.value = false; identity.discard() })
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
          <p v-else-if="!person" class="person-username">{{ userId ? t('people.formerMemberHint') : t('people.unknownMemberHint', { username: props.token }) }}</p>
        </div>
        <VeyqButton
          v-if="isMe && !editing"
          class="person-edit"
          :label="t('profilePicture.editProfile')"
          icon="pencil"
          variant="secondary"
          appearance="outlined"
          size="small"
          :loading="loadingOwn"
          data-testid="person-edit"
          @click="startEdit"
        />
      </header>
      <VeyqMessage v-if="isMe && !editing && editError" tone="error" role="alert">{{ editError }}</VeyqMessage>
      <form v-if="isMe && editing" class="veyq-form person-edit-form" data-testid="person-edit-form" @submit.prevent="runSaveOwn()">
        <NamePictureFields :model="identity" :display-name="title" />
        <VeyqMessage v-if="editError" tone="error" role="alert">{{ editError }}</VeyqMessage>
        <div class="veyq-dialog-action-row">
          <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="savingOwn" @click="cancelEdit" />
          <VeyqButton type="submit" :label="t('profile.save')" icon="check" :loading="savingOwn" />
        </div>
      </form>
      <MySafetyNumber v-if="isMe" />
      <PersonSafetyNumber v-else-if="person && userId" :user-id="userId" :name="person.displayName" :username="person.username" />
      <template v-if="userId">
        <component :is="section" v-for="(section, i) in sections" :key="i" :user-id="userId" :name="person?.displayName ?? ''" />
      </template>
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
