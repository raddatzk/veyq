<script setup lang="ts">
// Another member's safety number on their profile page, and the compare that marks them verified
// (uc.spaces.enforce-verified-identities main flow 1-3, A1, E1; ADR 0028 Decision 3).
//
// The number shown is the key this device would TRUST: the pin's when there is one, else the directory's. Two ways to
// compare it with the number the other member's own device shows (`MySafetyNumber`): SCAN their QR - a code that is not a
// veyq safety code, or one of a different key, is said as such and never accepted - or COMPARE the digits read aloud.
// A match is one user action: `markVerified` on the compared key, which for an unpinned member pins it already verified
// in the same sealed write (`pinIfUnpinned`). A key that moved under the compare (`IdentityMovedError`) writes nothing
// and says so. A mismatch never writes; where the pin differs from the directory the changed-key review is one click.
//
// Operator-blind: the only server call is the pin store's sealed write; who verified whom never leaves the device.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { getPin, foldTrust, markVerified, safetyNumber, IdentityMovedError, type TrustState } from '../../features/account/identityPins'
import { compareScan } from '../../features/account/safetyCode'
import { TrustStateRefused, refusalMessageKey } from '../../features/account/trustMarks'
import TrustResetLink from '../../features/account/trustReset/TrustResetLink.vue'
import KitScanner from '../../features/account/emergencyKit/KitScanner.vue'
import ChangedKeyReviewDialog from '../../features/spaces/ChangedKeyReviewDialog.vue'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { loadIdentityKey } from './directory'
import SafetyDigits from './SafetyDigits.vue'

const props = defineProps<{ userId: string; name: string; username: string }>()
const { t } = useI18n()

type View =
  | { kind: 'loading' }
  | { kind: 'absent' } // the directory lists no key for them (a former member): nothing to compare
  | { kind: 'refused'; messageKey: string }
  | { kind: 'error' }
  | {
      kind: 'ready'
      directoryKey: string
      /** The key the compare is about: the pin's, else the directory's. */
      compareKey: string
      /** Whether a pin existed when this was read - a match on an unpinned member pins it (ADR 0028 Decision 3). */
      pinned: boolean
      state: TrustState
      /** The pin is `verified` - on `changed`, a verified contact's key changed (ADR 0028 Decision 3). */
      pinVerified: boolean
      /** The compared key's digits, or null when this device cannot decode it (nothing to compare then). */
      number: string | null
    }
const view = ref<View>({ kind: 'loading' })
let token = 0

async function load() {
  const mine = ++token
  view.value = { kind: 'loading' }
  try {
    const directoryKey = await loadIdentityKey(props.userId)
    if (mine !== token) return
    if (!directoryKey) {
      view.value = { kind: 'absent' }
      return
    }
    const pin = await getPin(props.userId)
    if (mine !== token) return
    const compareKey = pin?.key ?? directoryKey
    view.value = { kind: 'ready', directoryKey, compareKey, pinned: Boolean(pin), state: foldTrust(pin, directoryKey), pinVerified: pin?.state === 'verified', number: numberOf(compareKey) }
  } catch (e) {
    if (mine !== token) return
    view.value = e instanceof TrustStateRefused ? { kind: 'refused', messageKey: refusalMessageKey(e) } : { kind: 'error' }
  }
}

function numberOf(keyB64: string): string | null {
  try {
    return safetyNumber(keyB64)
  } catch {
    return null
  }
}

/** What the last compare came to. Cleared by every new compare and by a move to another person. */
type Outcome = '' | 'verified' | 'mismatch' | 'foreign' | 'moved' | 'failed' | 'refused'
const outcome = ref<Outcome>('')
const outcomeRefusal = ref('')
const comparing = ref(false)
const scanOpen = ref(false)
const reviewOpen = ref(false)

const ready = computed(() => (view.value.kind === 'ready' ? view.value : null))

const { run: runVerify, pending: verifying } = useAsyncAction(
  async () => {
    const v = ready.value
    if (!v) return
    await markVerified(props.userId, v.compareKey, { pinIfUnpinned: !v.pinned })
    outcome.value = 'verified'
    await load()
  },
  {
    onError: async (e) => {
      if (e instanceof TrustStateRefused) {
        outcome.value = 'refused'
        outcomeRefusal.value = refusalMessageKey(e)
        return
      }
      outcome.value = e instanceof IdentityMovedError ? 'moved' : 'failed'
      if (e instanceof IdentityMovedError) await load() // show the key the store holds now; the compare starts over
    },
  },
)

function startCompare() {
  outcome.value = ''
  comparing.value = true
}
function startScan() {
  outcome.value = ''
  comparing.value = false
  scanOpen.value = true
}
async function theyMatch() {
  comparing.value = false
  await runVerify()
}
function theyDiffer() {
  comparing.value = false
  outcome.value = 'mismatch'
}
async function onScanned(text: string) {
  scanOpen.value = false
  const v = ready.value
  if (!v) return
  const verdict = compareScan(text, v.compareKey)
  if (verdict === 'match') await runVerify()
  else outcome.value = verdict
}

const reviewMember = computed(() => (ready.value ? { userId: props.userId, username: props.username, identityPublicKey: ready.value.directoryKey } : null))

onMounted(load)
watch(() => props.userId, () => {
  outcome.value = ''
  comparing.value = false
  scanOpen.value = false
  void load()
})
</script>

<template>
  <section class="veyq-section person-safety" aria-labelledby="person-safety-title" data-testid="person-safety">
    <template v-if="view.kind !== 'absent'">
      <div class="veyq-section-header">
        <h2 id="person-safety-title">{{ t('safety.theirsTitle') }}</h2>
        <template v-if="ready">
          <span v-if="ready.state === 'verified'" class="veyq-badge success" data-testid="person-safety-state">
            <i class="pi pi-verified" aria-hidden="true"></i>{{ t('safety.stateVerified') }}
          </span>
          <span v-else-if="ready.state === 'changed'" class="veyq-badge changed" data-testid="person-safety-state">
            <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>{{ ready.pinVerified ? t('safety.badgeVerifiedChanged') : t('verify.badgeChanged') }}
          </span>
          <span v-else class="veyq-badge muted" data-testid="person-safety-state">{{ t('safety.stateUnverified') }}</span>
        </template>
      </div>

      <p v-if="view.kind === 'loading'" class="veyq-form-copy" role="status">{{ t('people.loading') }}</p>
      <Message v-else-if="view.kind === 'refused'" severity="error" :closable="false">
        {{ t(view.messageKey) }}
        <TrustResetLink />
      </Message>
      <div v-else-if="view.kind === 'error'" role="alert">
        <p class="veyq-form-copy">{{ t('safety.loadError') }}</p>
        <Button type="button" :label="t('people.retry')" severity="secondary" text size="small" @click="load" />
      </div>

      <template v-else-if="ready">
        <p class="veyq-form-copy">{{ t('safety.theirsIntro', { name }) }}</p>
        <Message v-if="ready.number === null" severity="error" :closable="false">{{ t('safety.unreadable') }}</Message>
        <template v-else>
          <SafetyDigits v-if="!comparing" :number="ready.number" />

          <Message v-if="ready.state === 'changed'" severity="warn" :closable="false" data-testid="person-safety-changed">
            {{ ready.pinVerified ? t('safety.changedVerifiedNote', { name }) : t('safety.changedNote', { name }) }}
            <div class="person-safety-inline">
              <Button type="button" :label="t('verify.review')" icon="pi pi-exclamation-triangle" severity="warn" text size="small" data-testid="person-safety-review" @click="reviewOpen = true" />
            </div>
          </Message>

          <div v-if="comparing" class="person-safety-compare" data-testid="person-safety-compare">
            <p class="veyq-form-copy">{{ t('safety.compareHint', { name }) }}</p>
            <SafetyDigits :number="ready.number" large />
            <div class="veyq-dialog-action-row">
              <Button type="button" :label="t('safety.theyDiffer')" icon="pi pi-times" severity="secondary" outlined data-testid="person-safety-differ" @click="theyDiffer" />
              <Button type="button" :label="t('safety.theyMatch')" icon="pi pi-check" :loading="verifying" data-testid="person-safety-match" @click="theyMatch" />
            </div>
          </div>
          <div v-else class="person-safety-actions">
            <Button type="button" :label="t('safety.scan')" icon="pi pi-camera" severity="secondary" outlined size="small" :disabled="verifying" data-testid="person-safety-scan" @click="startScan" />
            <Button type="button" :label="t('safety.compare')" icon="pi pi-list" severity="secondary" outlined size="small" :disabled="verifying" data-testid="person-safety-compare-start" @click="startCompare" />
          </div>

          <Message v-if="outcome === 'verified'" severity="success" :closable="false" data-testid="person-safety-outcome">{{ t('safety.verified', { name }) }}</Message>
          <Message v-else-if="outcome === 'mismatch'" severity="error" :closable="false" data-testid="person-safety-outcome">{{ t('safety.mismatch', { name }) }}</Message>
          <Message v-else-if="outcome === 'foreign'" severity="warn" :closable="false" data-testid="person-safety-outcome">{{ t('safety.foreign') }}</Message>
          <Message v-else-if="outcome === 'moved'" severity="warn" :closable="false" data-testid="person-safety-outcome">{{ t('safety.moved', { name }) }}</Message>
          <Message v-else-if="outcome === 'refused'" severity="error" :closable="false" data-testid="person-safety-outcome">
            {{ t(outcomeRefusal) }}
            <TrustResetLink />
          </Message>
          <Message v-else-if="outcome === 'failed'" severity="error" :closable="false" data-testid="person-safety-outcome">{{ t('safety.failed') }}</Message>
        </template>
      </template>
    </template>

    <KitScanner v-if="scanOpen" :hint="t('safety.scanHint', { name })" :error-text="t('safety.scanError')" @found="onScanned" @close="scanOpen = false" />
    <ChangedKeyReviewDialog v-if="reviewMember" v-model:visible="reviewOpen" :member="reviewMember" @accepted="load" />
  </section>
</template>

<style scoped>
.person-safety {
  max-width: 32rem;
}
.person-safety-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.person-safety-compare {
  display: grid;
  gap: 0.75rem;
}
.person-safety-inline {
  margin-top: 0.35rem;
}
</style>
