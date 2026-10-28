<script setup lang="ts">
// Who's who in the active tenant (inbox #562, uc.spaces.enforce-verified-identities main flow 5; ADR 0111 decision 15):
// every other member with the one state their key has in this space - "Can't confirm this key" (the anomaly, with its
// note), "Verified" or "Not verified" - "New" beside it while this account first saw them under two weeks ago, the
// members whose verification of their current head counts ("you" for this account), "New identity, signed by their
// previous key" while that is under two weeks old, and the note when their invitation replaced a key this account saw
// before. The compare lives on the person's profile: every row this account has not verified links there.
//
// The same page for an owner and a member (nothing here depends on a role), and no guest ever reaches it: a link guest
// has no tenant. A member whose check failed for a request shows no state, never a false anomaly. Display only.
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import PersonAvatar from '../../core/people/PersonAvatar.vue'
import { loadTenantPeople, type Person } from '../../core/people/directory'
import { personHash } from '../../core/node/location'
import { loadPeopleOverview, orderPeople, type OverviewPerson } from '../account/peopleOverview'
import { TrustStateRefused, refusalMessageKey } from '../account/trustMarks'
import TrustResetLink from '../account/trustReset/TrustResetLink.vue'
import { activeSpace } from '../../app/activeSpace'
import { account } from '../../app/account'

const { t, locale } = useI18n()

type View =
  | { kind: 'loading' }
  | { kind: 'refused'; messageKey: string }
  | { kind: 'unavailable' }
  | { kind: 'error' }
  | { kind: 'ready'; people: OverviewPerson[] }
const view = ref<View>({ kind: 'loading' })
const names = ref<Map<string, Person>>(new Map())
let token = 0

const nameOf = (p: OverviewPerson): string => names.value.get(p.userId)?.displayName ?? p.username
const verifiedByLine = (p: OverviewPerson): string =>
  p.verifiedBy
    .map((id) => (id === account.userId ? t('safety.verifiedByYou') : (names.value.get(id)?.displayName ?? '')))
    .filter(Boolean)
    .join(', ')
const rotatedOn = (d: Date): string => d.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })
const verifiedByMe = (p: OverviewPerson): boolean => p.verifiedBy.includes(account.userId)
const profileOf = (userId: string) => ({ path: '/', hash: personHash(userId) })

async function load() {
  const mine = ++token
  view.value = { kind: 'loading' }
  try {
    const tenantId = activeSpace.tenantId
    if (!tenantId) throw new Error('no active tenant')
    const [overview, people] = await Promise.all([
      loadPeopleOverview(tenantId, account.userId),
      loadTenantPeople().catch(() => new Map<string, Person>()),
    ])
    if (mine !== token) return
    names.value = people
    view.value = overview.available
      ? { kind: 'ready', people: orderPeople(overview.people, nameOf, locale.value) }
      : { kind: 'unavailable' }
  } catch (e) {
    if (mine !== token) return
    view.value = e instanceof TrustStateRefused ? { kind: 'refused', messageKey: refusalMessageKey(e) } : { kind: 'error' }
  }
}

onMounted(load)
watch(() => activeSpace.tenantId, () => void load())
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('whoIsWho.title') }}</h2>
          <p>{{ t('whoIsWho.sub') }}</p>
        </div>
      </div>

      <p v-if="view.kind === 'loading'" class="settings-muted" role="status">{{ t('whoIsWho.loading') }}</p>
      <VeyqMessage v-else-if="view.kind === 'refused'" tone="warn">
        {{ t(view.messageKey) }}
        <TrustResetLink />
      </VeyqMessage>
      <VeyqMessage v-else-if="view.kind === 'unavailable'" tone="warn">{{ t('whoIsWho.unavailable') }}</VeyqMessage>
      <div v-else-if="view.kind === 'error'" role="alert">
        <p class="settings-muted">{{ t('whoIsWho.loadFailed') }}</p>
        <VeyqButton :label="t('people.retry')" variant="secondary" appearance="text" size="small" @click="load" />
      </div>
      <p v-else-if="view.people.length === 0" class="settings-muted">{{ t('whoIsWho.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items" data-testid="who-is-who">
          <div
            v-for="p in view.people"
            :key="p.userId"
            class="veyq-list-row member-row"
            :data-testid="`who-${p.userId}`"
          >
            <PersonAvatar :name="nameOf(p)" :user-id="p.userId" class="member-avatar" />
            <div class="veyq-row-main">
              <RouterLink
                :to="profileOf(p.userId)"
                class="veyq-row-title who-name"
                :aria-label="t('whoIsWho.openProfile', { name: nameOf(p) })"
              >{{ nameOf(p) }}</RouterLink>
              <span class="veyq-row-subtitle">@{{ p.username }}</span>
              <div class="veyq-row-meta">
                <span v-if="p.state === 'anomaly'" class="veyq-badge changed" data-testid="who-state">
                  <VeyqIcon name="exclamation-triangle" />{{ t('trust.anomaly') }}
                </span>
                <span v-else-if="p.state === 'verified'" class="veyq-badge success" data-testid="who-state">
                  <VeyqIcon name="verified" />{{ t('safety.stateVerified') }}
                </span>
                <span v-else-if="p.state === 'unverified'" class="veyq-badge muted" data-testid="who-state">
                  {{ t('safety.stateUnverified') }}
                </span>
                <span v-if="p.isNew" class="veyq-badge" data-testid="who-new">{{ t('safety.stateNew') }}</span>
              </div>
              <span v-if="p.state === 'anomaly'" class="settings-muted" data-testid="who-anomaly-note">{{ t('trust.anomalyNote') }}</span>
              <span v-if="p.state === 'verified' && verifiedByLine(p)" class="settings-muted" data-testid="who-verified-by">
                {{ t('safety.verifiedBy', { names: verifiedByLine(p) }) }}
              </span>
              <span v-if="p.rotatedAt" class="settings-muted" data-testid="who-rotated">
                {{ t('trust.rotated', { date: rotatedOn(p.rotatedAt) }) }}
              </span>
              <span v-if="p.replaced" class="settings-muted" data-testid="who-replaced">{{ t('safety.replaced') }}</span>
            </div>
            <div v-if="!verifiedByMe(p)" class="management-actions">
              <RouterLink v-slot="{ href, navigate }" :to="profileOf(p.userId)" custom>
                <VeyqButton
                  :href="href"
                  :label="t('whoIsWho.compare')"
                  icon="list"
                  variant="secondary"
                  appearance="outlined"
                  size="small"
                  data-testid="who-compare"
                  @click="navigate"
                />
              </RouterLink>
            </div>
          </div>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
/* A person's row leads with their avatar, as in the members list: one more grid column in front of the shared three. */
.member-row {
  grid-template-columns: auto minmax(0, 1fr) auto;
}
@media (max-width: 820px) {
  .member-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .member-row .management-actions {
    grid-column: 1 / -1;
  }
}
.who-name {
  color: inherit;
  text-decoration: none;
}
.who-name:hover,
.who-name:focus-visible {
  text-decoration: underline;
}
</style>
