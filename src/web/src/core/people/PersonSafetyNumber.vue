<script setup lang="ts">
// Another member's safety number and chain code on their profile page, and the compare that verifies them in the active
// tenant (uc.spaces.enforce-verified-identities main flow 1-3, A1, E1; ADR 0028 Decision 3; ADR 0111 decisions 12-15).
//
// What is shown is the person as THIS tenant anchors them (`personInTenant`): the key and head the directory serves,
// once their join record and key chain reach them (or, for a root owner, the pin). Two ways to compare them with what the
// other member's own device shows (`MySafetyNumber`): SCAN their QR - a code that is not a veyq safety code, an outdated
// one (`veyqid:1`, no head), or one of a different key OR head is said as such and never accepted - or COMPARE the digits
// and the chain code read aloud. A different head with the same key is a step withheld from one side (decision 14).
// A match is one user action: `verifyPerson` signs "I, at my head, confirmed you at yours" and writes it into this
// account's own records in the tenant, where every member reads it (*verified by …*). A key that moved under the compare
// (`IdentityMovedError`) or is not anchored (`IdentityUnanchoredError`) writes nothing and says so. A mismatch never
// writes.
//
// The header says what decision 15 shows: an ANOMALY (a key this tenant does not anchor - shown with its note, the
// directory's digits for an in-person compare, and no compare to accept: it returns only through its chain), *new*
// (first seen under two weeks ago), *verified by …* (whose verification of the current head counts), *not verified*,
// *new identity, signed by the previous key* (a rotation the chain reached), and the note when a join record replaced a
// pin that was not theirs. Display only: nothing here blocks anybody.
//
// Operator-blind: the server calls are the tenant's directory and key chain, the pin store's sealed write and the sealed
// verification rows; the statements are sealed under the tenant key.
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { IdentityMovedError, IdentityUnanchoredError, safetyNumber, safetyNumberOfFingerprint } from '../../features/account/identityPins'
import { personInTenant, verifyPerson } from '../../features/account/tenantTrust'
import { chainCode, compareScan } from '../../features/account/safetyCode'
import { TrustStateRefused, refusalMessageKey } from '../../features/account/trustMarks'
import TrustResetLink from '../../features/account/trustReset/TrustResetLink.vue'
import KitScanner from '../../features/account/emergencyKit/KitScanner.vue'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { activeSpace } from '../../app/activeSpace'
import { account } from '../../app/account'
import { loadServedIdentity, loadTenantPeople, type Person } from './directory'
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
      tenantId: string
      /** Not anchored in this tenant: shown, never compared to accept. */
      anomaly: boolean
      /** What a match verifies - the anchored fingerprint and head; null for an anomaly. */
      compared: { fingerprint: string; head: string } | null
      /** The digits on screen: the anchored key's, or for an anomaly the directory's (for an in-person compare); null
       *  when this device cannot render them. */
      number: string | null
      chain: string | null
      isNew: boolean
      /** Someone's verification of the current head counts in this tenant. */
      verified: boolean
      /** The names of the members whose verification of the current head counts ("you" for this account); a verifier
       *  whose name this device cannot read is left out of the sentence, never out of the badge. */
      verifiedBy: string[]
      /** When their chain's newest step - a rotation the chain reaches - was made, or null. */
      rotatedAt: Date | null
      /** A join record replaced a pin that was not theirs. */
      replaced: boolean
    }
const view = ref<View>({ kind: 'loading' })
let token = 0

function orNull<T>(f: () => T): T | null {
  try {
    return f()
  } catch {
    return null
  }
}

async function load() {
  const mine = ++token
  view.value = { kind: 'loading' }
  try {
    const tenantId = activeSpace.tenantId
    if (!tenantId) throw new Error('no active tenant')
    const served = await loadServedIdentity(props.userId)
    if (mine !== token) return
    if (!served) {
      view.value = { kind: 'absent' }
      return
    }
    const info = await personInTenant(tenantId, props.userId)
    const people: Map<string, Person> = info.verifiedBy.length ? await loadTenantPeople().catch(() => new Map()) : new Map()
    if (mine !== token) return
    const compared = info.anchor !== 'anomaly' && info.head !== null && info.fingerprint !== null
      ? { fingerprint: info.fingerprint, head: info.head }
      : null
    view.value = {
      kind: 'ready',
      tenantId,
      anomaly: !compared,
      compared,
      // the anchored key's digits render from its fingerprint alone (ADR 0104 decision 3); an anomaly's are the directory's
      number: orNull(() => (compared ? safetyNumberOfFingerprint(compared.fingerprint) : safetyNumber(served.identityPublicKey))),
      chain: orNull(() => chainCode(compared ? compared.head : served.keyChainHead)),
      isNew: !!compared && info.isNew,
      verified: !!compared && info.verifiedBy.length > 0,
      verifiedBy: info.verifiedBy.map((id) =>
        id === account.userId ? t('safety.verifiedByYou') : (people.get(id)?.displayName ?? ''),
      ).filter(Boolean),
      rotatedAt: compared ? info.rotatedAt : null,
      replaced: !!compared && !!info.pin?.replaced,
    }
  } catch (e) {
    if (mine !== token) return
    view.value = e instanceof TrustStateRefused ? { kind: 'refused', messageKey: refusalMessageKey(e) } : { kind: 'error' }
  }
}

/** What the last compare came to. Cleared by every new compare and by a move to another person. */
type Outcome = '' | 'verified' | 'mismatch' | 'foreign' | 'outdated' | 'moved' | 'unanchored' | 'failed' | 'refused'
const outcome = ref<Outcome>('')
const outcomeRefusal = ref('')
const comparing = ref(false)
const scanOpen = ref(false)

const ready = computed(() => (view.value.kind === 'ready' ? view.value : null))

const { run: runVerify, pending: verifying } = useAsyncAction(
  async () => {
    const v = ready.value
    if (!v?.compared) return
    await verifyPerson(v.tenantId, props.userId, v.compared)
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
      if (e instanceof IdentityMovedError || e instanceof IdentityUnanchoredError) {
        outcome.value = e instanceof IdentityMovedError ? 'moved' : 'unanchored'
        await load() // show what the tenant anchors now; the compare starts over
        return
      }
      outcome.value = 'failed'
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
  if (!v?.compared) return
  const verdict = compareScan(text, v.compared)
  if (verdict === 'match') await runVerify()
  else outcome.value = verdict
}

const rotatedOn = computed(() =>
  ready.value?.rotatedAt
    ? ready.value.rotatedAt.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })
    : null,
)
const canCompare = computed(() => !!ready.value?.compared && ready.value.number !== null && ready.value.chain !== null)

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
          <span v-if="ready.anomaly" class="veyq-badge changed" data-testid="person-safety-state">
            <VeyqIcon name="exclamation-triangle" />{{ t('trust.anomaly') }}
          </span>
          <span v-else-if="ready.verified" class="veyq-badge success" data-testid="person-safety-state">
            <VeyqIcon name="verified" />{{ t('safety.stateVerified') }}
          </span>
          <span v-else class="veyq-badge muted" data-testid="person-safety-state">{{ t('safety.stateUnverified') }}</span>
          <span v-if="ready.isNew" class="veyq-badge" data-testid="person-safety-new">{{ t('safety.stateNew') }}</span>
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
          <template v-if="!comparing">
            <SafetyDigits :number="ready.number" />
            <div v-if="ready.chain" class="person-safety-chain" data-testid="person-chain-code">
              <div class="veyq-section-header"><h3>{{ t('safety.chainCode') }}</h3></div>
              <SafetyDigits :number="ready.chain" />
              <p class="veyq-form-copy">{{ t('safety.chainCodeHint', { name }) }}</p>
            </div>
          </template>

          <VeyqMessage v-if="ready.anomaly" tone="warn" data-testid="person-safety-anomaly">
            {{ t('trust.anomalyNote') }}
          </VeyqMessage>
          <template v-else>
            <p v-if="ready.verifiedBy.length" class="veyq-form-copy" data-testid="person-safety-verified-by">
              {{ t('safety.verifiedBy', { names: ready.verifiedBy.join(', ') }) }}
            </p>
            <p v-if="rotatedOn" class="veyq-form-copy" data-testid="person-safety-rotated">
              {{ t('trust.rotated', { date: rotatedOn }) }}
            </p>
            <p v-if="ready.replaced" class="veyq-form-copy" data-testid="person-safety-replaced">{{ t('safety.replaced') }}</p>
          </template>

          <template v-if="canCompare">
            <div v-if="comparing" class="person-safety-compare" data-testid="person-safety-compare">
              <p class="veyq-form-copy">{{ t('safety.compareHint', { name }) }}</p>
              <SafetyDigits :number="ready.number" large />
              <div class="veyq-section-header"><h3>{{ t('safety.chainCode') }}</h3></div>
              <SafetyDigits :number="ready.chain!" large />
              <div class="veyq-dialog-action-row">
                <VeyqButton :label="t('safety.theyDiffer')" icon="times" variant="secondary" appearance="outlined" data-testid="person-safety-differ" @click="theyDiffer" />
                <VeyqButton :label="t('safety.theyMatch')" icon="check" :loading="verifying" data-testid="person-safety-match" @click="theyMatch" />
              </div>
            </div>
            <div v-else class="person-safety-actions">
              <VeyqButton :label="t('safety.scan')" icon="camera" variant="secondary" appearance="outlined" size="small" :disabled="verifying" data-testid="person-safety-scan" @click="startScan" />
              <VeyqButton :label="t('safety.compare')" icon="list" variant="secondary" appearance="outlined" size="small" :disabled="verifying" data-testid="person-safety-compare-start" @click="startCompare" />
            </div>
          </template>
        </template>

        <VeyqMessage v-if="outcome === 'verified'" tone="success" data-testid="person-safety-outcome">{{ t('safety.verified', { name }) }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'mismatch'" tone="error" data-testid="person-safety-outcome">{{ t('safety.mismatch', { name }) }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'foreign'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.foreign') }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'outdated'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.outdated', { name }) }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'moved'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.moved', { name }) }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'unanchored'" tone="warn" data-testid="person-safety-outcome">{{ t('safety.unanchored', { name }) }}</VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'refused'" tone="error" data-testid="person-safety-outcome">
          {{ t(outcomeRefusal) }}
          <TrustResetLink />
        </VeyqMessage>
        <VeyqMessage v-else-if="outcome === 'failed'" tone="error" data-testid="person-safety-outcome">{{ t('safety.failed') }}</VeyqMessage>
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
.person-safety-compare,
.person-safety-chain {
  display: grid;
  gap: 0.75rem;
}
.person-safety-chain {
  gap: 0.25rem;
}
</style>
