<script setup lang="ts">
// Another member's safety number on their profile page, and the compare that marks them verified
// (uc.spaces.enforce-verified-identities main flow 1-3, A1, E1; ADR 0028 Decision 3).
//
// The number shown is the key this device would TRUST: the pin's when there is one, else the directory's - as a
// fingerprint, which is what a pin holds (ADR 0104 decision 3), so the digits render from it alone. Two ways to
// compare it with the number the other member's own device shows (`MySafetyNumber`): SCAN their QR - a code that is not a
// veyq safety code, or one of a different key, is said as such and never accepted - or COMPARE the digits read aloud.
// A match is one user action: `markVerified` on the compared fingerprint, which for an unpinned member pins it verified
// at the head the directory served, in the same sealed write (`pinAtHead`). A key that moved under the compare
// (`IdentityMovedError`) writes nothing and says so. A mismatch never writes.
//
// Their key chain (cr-secure-the-account decision 9) is read here: a key it reaches from the pinned head moves the pin
// (and drops a `verified`), and the date of its newest step, when that is a rotation, is said ("New identity, signed by
// their previous key"); a key it does not reach is an ANOMALY, shown with its note and no accept — it returns only
// through the chain.
//
// Operator-blind: the server calls are the chain read and the pin store's sealed write; who verified whom never leaves
// the device.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { personTrust, markVerified, safetyNumberOfFingerprint, IdentityMovedError, type TrustState } from '../../features/account/identityPins'
import { compareScan, fingerprintDigestB64 } from '../../features/account/safetyCode'
import { TrustStateRefused, refusalMessageKey } from '../../features/account/trustMarks'
import TrustResetLink from '../../features/account/trustReset/TrustResetLink.vue'
import KitScanner from '../../features/account/emergencyKit/KitScanner.vue'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { loadServedIdentity } from './directory'
import SafetyDigits from './SafetyDigits.vue'

const props = defineProps<{ userId: string; name: string; username: string }>()
const { t, locale } = useI18n()

type View =
  | { kind: 'loading' }
  | { kind: 'absent' } // the directory lists no key for them (a former member): nothing to compare
  | { kind: 'refused'; messageKey: string }
  | { kind: 'error' }
  | {
      kind: 'ready'
      /** The key-chain head the directory served beside the key - an unpinned member is pinned at it on a match. */
      servedHead: string
      /** The fingerprint the compare is about: the pin's (after the read moved it along the chain), else the directory
       *  key's; null when this device cannot decode the directory key (nothing to compare then). */
      compareFingerprint: string | null
      /** Whether a pin existed when this was read - a match on an unpinned member pins it (ADR 0028 Decision 3). */
      pinned: boolean
      state: TrustState
      /** When their chain's newest step - a rotation the chain reaches - was made, or null. */
      rotatedAt: Date | null
      /** The compared fingerprint's digits, or null when there is none to compare. */
      number: string | null
    }
const view = ref<View>({ kind: 'loading' })
let token = 0

async function load() {
  const mine = ++token
  view.value = { kind: 'loading' }
  try {
    const served = await loadServedIdentity(props.userId)
    if (mine !== token) return
    if (!served) {
      view.value = { kind: 'absent' }
      return
    }
    const { state, pin, rotatedAt } = await personTrust(props.userId, served)
    if (mine !== token) return
    const compareFingerprint = pin?.fingerprint ?? fingerprintOf(served.identityPublicKey)
    view.value = {
      kind: 'ready', servedHead: served.keyChainHead, compareFingerprint, pinned: Boolean(pin), state, rotatedAt,
      number: compareFingerprint === null ? null : numberOf(compareFingerprint),
    }
  } catch (e) {
    if (mine !== token) return
    view.value = e instanceof TrustStateRefused ? { kind: 'refused', messageKey: refusalMessageKey(e) } : { kind: 'error' }
  }
}

function fingerprintOf(keyB64: string): string | null {
  try {
    return fingerprintDigestB64(keyB64)
  } catch {
    return null
  }
}

function numberOf(fingerprintB64: string): string | null {
  try {
    return safetyNumberOfFingerprint(fingerprintB64)
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

const ready = computed(() => (view.value.kind === 'ready' ? view.value : null))

const { run: runVerify, pending: verifying } = useAsyncAction(
  async () => {
    const v = ready.value
    if (!v?.compareFingerprint) return
    await markVerified(props.userId, v.compareFingerprint, v.pinned ? {} : { pinAtHead: v.servedHead })
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
  if (!v?.compareFingerprint) return
  const verdict = compareScan(text, v.compareFingerprint)
  if (verdict === 'match') await runVerify()
  else outcome.value = verdict
}

const rotatedOn = computed(() =>
  ready.value?.rotatedAt
    ? ready.value.rotatedAt.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })
    : null,
)

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
            <VeyqIcon name="verified" />{{ t('safety.stateVerified') }}
          </span>
          <span v-else-if="ready.state === 'anomaly'" class="veyq-badge changed" data-testid="person-safety-state">
            <VeyqIcon name="exclamation-triangle" />{{ t('trust.anomaly') }}
          </span>
          <span v-else class="veyq-badge muted" data-testid="person-safety-state">{{ t('safety.stateUnverified') }}</span>
        </template>
      </div>

      <p v-if="view.kind === 'loading'" class="veyq-form-copy" role="status">{{ t('people.loading') }}</p>
      <VeyqMessage v-else-if="view.kind === 'refused'" tone="error">
        {{ t(view.messageKey) }}
        <TrustResetLink />
      </VeyqMessage>
      <div v-else-if="view.kind === 'error'" role="alert">
        <p class="veyq-form-copy">{{ t('safety.loadError') }}</p>
        <VeyqButton :label="t('people.retry')" variant="secondary" appearance="text" size="small" @click="load" />
      </div>

      <template v-else-if="ready">
        <p class="veyq-form-copy">{{ t('safety.theirsIntro', { name }) }}</p>
        <VeyqMessage v-if="ready.number === null" tone="error">{{ t('safety.unreadable') }}</VeyqMessage>
        <template v-else>
          <SafetyDigits v-if="!comparing" :number="ready.number" />

          <VeyqMessage v-if="ready.state === 'anomaly'" tone="warn" data-testid="person-safety-anomaly">
            {{ t('trust.anomalyNote') }}
          </VeyqMessage>
          <p v-else-if="rotatedOn" class="veyq-form-copy" data-testid="person-safety-rotated">
            {{ t('trust.rotated', { date: rotatedOn }) }}
          </p>

          <div v-if="comparing" class="person-safety-compare" data-testid="person-safety-compare">
            <p class="veyq-form-copy">{{ t('safety.compareHint', { name }) }}</p>
            <SafetyDigits :number="ready.number" large />
            <div class="veyq-dialog-action-row">
              <VeyqButton :label="t('safety.theyDiffer')" icon="times" variant="secondary" appearance="outlined" data-testid="person-safety-differ" @click="theyDiffer" />
              <VeyqButton :label="t('safety.theyMatch')" icon="check" :loading="verifying" data-testid="person-safety-match" @click="theyMatch" />
            </div>
          </div>
          <div v-else class="person-safety-actions">
            <VeyqButton :label="t('safety.scan')" icon="camera" variant="secondary" appearance="outlined" size="small" :disabled="verifying" data-testid="person-safety-scan" @click="startScan" />
            <VeyqButton :label="t('safety.compare')" icon="list" variant="secondary" appearance="outlined" size="small" :disabled="verifying" data-testid="person-safety-compare-start" @click="startCompare" />
          </div>

          <VeyqMessage v-if="outcome === 'verified'" tone="success" data-testid="person-safety-outcome">{{ t('safety.verified', { name }) }}</VeyqMessage>
          <VeyqMessage v-else-if="outcome === 'mismatch'" tone="error" data-testid="person-safety-outcome">{{ t('safety.mismatch', { name }) }}</VeyqMessage>
          <VeyqMessage v-else-if="outcome === 'foreign'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.foreign') }}</VeyqMessage>
          <VeyqMessage v-else-if="outcome === 'moved'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.moved', { name }) }}</VeyqMessage>
          <VeyqMessage v-else-if="outcome === 'refused'" tone="error" data-testid="person-safety-outcome">
            {{ t(outcomeRefusal) }}
            <TrustResetLink />
          </VeyqMessage>
          <VeyqMessage v-else-if="outcome === 'failed'" tone="error" data-testid="person-safety-outcome">{{ t('safety.failed') }}</VeyqMessage>
        </template>
      </template>
    </template>

    <KitScanner v-if="scanOpen" :hint="t('safety.scanHint', { name })" :error-text="t('safety.scanError')" @found="onScanned" @close="scanOpen = false" />
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
</style>
