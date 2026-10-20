<script setup lang="ts">
// The "Erase account" section of the settings hub — uc.account.erase-account (ADR 0101). It says what goes and what
// stays (decisions 4, 5, 7) before anything else, reads which spaces the person owns last, asks for each one with
// other members whether it is deleted along or handed to a member (decision 9, owner Q18), names the spaces deleted
// along because nobody else is in them, takes the password, and confirms through the shared danger dialog. The
// request and everything after it — this browser's records, the ending — live in `eraseAccount.ts`.
//
// Markup mirrors KeyRotationPanel (veyq-surface + settings-form + settings-section-header, its facts list) and its
// rows SessionsPanel's veyq-list-row — the built panels are the design source.
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Password from 'primevue/password'
import Select from 'primevue/select'
import { loadTenantPeople, type Person } from '../../../core/people/directory'
import { useConfirm } from '../../../shared/state/useConfirm'
import {
  eraseOwnAccount,
  erasureErrorKey,
  loadErasurePreview,
  type ErasurePreview,
  type ErasureTenant,
  type LastOwnerChoice,
} from './eraseAccount'

const { t } = useI18n()
const { ask } = useConfirm()

/** The value the choice holds for "delete the space along"; a member's choice holds their account id (a uuid). */
const DELETE_ALONG = 'delete-along'

const preview = ref<ErasurePreview | null>(null)
const loadFailed = ref(false)
// Names for the members a choice offers: this space's directory, keyed by account id (an account is one person in
// every space). A member it does not list is shown by id — the client holds no other space's directory.
const people = ref<Map<string, Person>>(new Map())
const choices = reactive<Record<string, string | undefined>>({})
const password = ref('')
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    preview.value = await loadErasurePreview()
  } catch {
    loadFailed.value = true
    return
  }
  try {
    people.value = await loadTenantPeople()
  } catch {
    /* the choices fall back to account ids */
  }
})

const tenants = computed<ErasureTenant[]>(() => (preview.value?.kind === 'ready' ? preview.value.tenants : []))
/** The spaces that need the person's choice: they hold other members. */
const choiceTenants = computed(() => tenants.value.filter((tn) => tn.otherMemberIds.length > 0))
/** The spaces deleted along without a choice: the person is their only member. */
const soleTenants = computed(() => tenants.value.filter((tn) => tn.otherMemberIds.length === 0))

const nameOf = (userId: string): string => people.value.get(userId)?.displayName || userId

function optionsFor(tenant: ErasureTenant): { label: string; value: string }[] {
  return [
    { label: t('erasure.deleteAlong'), value: DELETE_ALONG },
    ...tenant.otherMemberIds.map((id) => ({ label: t('erasure.makeOwner', { name: nameOf(id) }), value: id })),
  ]
}

const everyChoiceMade = computed(() => choiceTenants.value.every((tn) => choices[tn.tenantId] !== undefined))
const canSubmit = computed(() => preview.value?.kind === 'ready' && password.value.length > 0 && everyChoiceMade.value)

function lastOwnerChoices(): LastOwnerChoice[] {
  return choiceTenants.value.map((tn) => {
    const choice = choices[tn.tenantId]
    return { tenantId: tn.tenantId, newOwnerId: choice === DELETE_ALONG ? null : (choice ?? null) }
  })
}

async function submit() {
  error.value = ''
  // Checked here as well as on the button: Enter submits the form whatever the button's state.
  if (preview.value?.kind !== 'ready' || password.value.length === 0) return
  if (!everyChoiceMade.value) {
    error.value = t('erasure.errorChoiceRequired')
    return
  }
  const accepted = await ask({
    header: t('erasure.confirmTitle'),
    message: t('erasure.confirmBody'),
    acceptLabel: t('erasure.action'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  busy.value = true
  try {
    // On success the ending loads the sign-in page; this component does not outlive it.
    await eraseOwnAccount(password.value, lastOwnerChoices())
  } catch (e) {
    error.value = t(await erasureErrorKey(e))
    busy.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('erasure.title') }}</h2>
          <p>{{ t('erasure.sub') }}</p>
        </div>
      </div>

      <Message v-if="loadFailed" severity="error" :closable="false">{{ t('erasure.loadFailed') }}</Message>
      <Message v-else-if="preview?.kind === 'serverOwner'" severity="info" :closable="false" class="erasure-server-owner">
        {{ t('erasure.serverOwner') }}
      </Message>

      <template v-else-if="preview?.kind === 'ready'">
        <!-- What goes and what stays (ADR 0101 decisions 4, 5, 7) — before any field, as the rotation's facts are. -->
        <ul class="erasure-facts">
          <li><i class="pi pi-trash" aria-hidden="true" /><span>{{ t('erasure.factGoes') }}</span></li>
          <li><i class="pi pi-users" aria-hidden="true" /><span>{{ t('erasure.factSharedStays') }}</span></li>
          <li><i class="pi pi-shield" aria-hidden="true" /><span>{{ t('erasure.factKept') }}</span></li>
          <li><i class="pi pi-database" aria-hidden="true" /><span>{{ t('erasure.factBackups') }}</span></li>
          <li><i class="pi pi-clock" aria-hidden="true" /><span>{{ t('erasure.factWindow') }}</span></li>
        </ul>

        <form class="veyq-form" @submit.prevent="submit">
          <section v-if="choiceTenants.length" class="erasure-tenants">
            <h3>{{ t('erasure.choiceTitle') }}</h3>
            <p class="settings-muted">{{ t('erasure.choiceSub') }}</p>
            <ul class="erasure-list">
              <li v-for="tn in choiceTenants" :key="tn.tenantId" class="veyq-list-row" :data-choice-tenant="tn.tenantId">
                <label :for="`erasure-choice-${tn.tenantId}`">{{ tn.name }}</label>
                <Select
                  v-model="choices[tn.tenantId]"
                  :input-id="`erasure-choice-${tn.tenantId}`"
                  :options="optionsFor(tn)"
                  option-label="label"
                  option-value="value"
                  :placeholder="t('erasure.choicePlaceholder')"
                  :disabled="busy"
                />
              </li>
            </ul>
          </section>

          <section v-if="soleTenants.length" class="erasure-tenants">
            <h3>{{ t('erasure.soleTitle') }}</h3>
            <p class="settings-muted">{{ t('erasure.soleSub') }}</p>
            <ul class="erasure-list">
              <li v-for="tn in soleTenants" :key="tn.tenantId" class="veyq-list-row" :data-sole-tenant="tn.tenantId">
                {{ tn.name }}
              </li>
            </ul>
          </section>

          <div class="settings-field">
            <label for="erasure-password">{{ t('erasure.passwordLabel') }}</label>
            <Password
              v-model="password"
              input-id="erasure-password"
              :feedback="false"
              toggle-mask
              autocomplete="current-password"
              :disabled="busy"
            />
          </div>

          <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

          <div class="erasure-action">
            <Button
              type="submit"
              :label="t('erasure.action')"
              icon="pi pi-trash"
              severity="danger"
              :disabled="!canSubmit || busy"
              :loading="busy"
            />
          </div>
        </form>
      </template>
    </section>
  </section>
</template>

<style scoped>
.erasure-facts {
  list-style: none;
  margin: 0.5rem 0 1rem;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.erasure-facts li {
  display: flex;
  align-items: start;
  gap: 0.5rem;
}
.erasure-facts .pi {
  color: var(--app-muted);
  margin-top: 0.15rem;
}
.erasure-tenants h3 {
  margin: 0 0 0.25rem;
  font-size: 1rem;
}
.erasure-list {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
}
.erasure-action {
  display: flex;
  margin-top: 1rem;
}
</style>
