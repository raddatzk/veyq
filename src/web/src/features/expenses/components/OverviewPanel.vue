<script setup lang="ts">
// A Pot's overview (uc.expenses.balances, docs/apps/expenses.md § What the client computes), the balances and the costs on
// one tab (owner 2026-11-04): who is owed and who owes, each row explained by what the person paid, consumed and settled
// by transfer — every erased participant summed into one "deleted people", a deactivated or deleted one kept while their
// balance is open or they have costs (`shownBalances`) —; the settle-up suggestion, `ledger.ts`'s greedy one, the same on every device, of which a writer
// records one transfer or all in one batch (ADR 0108 decision 1: one append, all or nothing); and the cost per category,
// refunds counted against their category, transfers no cost. Above them the viewer says who they are ("Who am I",
// `../whoAmI.ts`): kept on this device only, it marks their row *you* — and until they say, a member who is a member
// participant is that one. Managing the people is the Settings tab's (`PeoplePanel`).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import { categoryLabel } from '../domain/categories'
import { ownCategoryNames, type PotMeta } from '../domain/potMeta'
import type { PotLedger, Transfer } from '../domain/ledger'
import type { PotSession } from '../potSession'
import type { PotWrite } from '../domain/potApi'
import { formatMoney } from '../amounts'
import { personLabel, shownBalances, standingOf, type MemberDirectory } from '../people'
import { today } from '../entryForm'
import { ownParticipant, whoAmI } from '../whoAmI'

const props = defineProps<{
  session: PotSession; ledger: PotLedger; meta: PotMeta; canWrite: boolean; potId: string; directory?: MemberDirectory
  /** The signed-in member's user id — their own member participant is who they are until they choose; null for a guest. */
  selfId?: string | null
}>()
const { t, locale } = useI18n()

/** Reka's select takes no empty value: "not chosen" is this token. */
const NOBODY = 'nobody'
const who = computed(() => whoAmI(props.potId, ownParticipant(props.ledger, props.selfId ?? null)))
/** The viewer's participant while it is still one of this Pot's named people (an erased one is nobody's to be). */
const me = computed(() => {
  const id = who.value.me.value
  return props.ledger.participants.some((p) => p.id === id && !p.erased && p.name !== null) ? id : null
})
const dir = computed<MemberDirectory>(() => props.directory ?? null)
const meOptions = computed(() => [
  { value: NOBODY, label: t('expenses.whoAmI.nobody') },
  ...props.ledger.participants.filter((p) => !p.erased && p.name !== null).map((p) => ({ value: p.id, label: personLabel(props.ledger, p.id, t, dir.value) })),
])
const picked = computed<string>({
  get: () => me.value ?? NOBODY,
  set: (v) => who.value.choose(v === NOBODY ? null : v),
})

const money = (v: bigint) => formatMoney(v, props.meta.base.currency, props.meta.base.minorUnits, locale.value)
const rows = computed(() =>
  shownBalances(props.ledger).map((r) => {
    // What makes the balance: paid and consumed always, the transfers only where there were any.
    const parts = [t('expenses.balances.paid', { amount: money(r.paid) }), t('expenses.balances.consumed', { amount: money(r.consumed) })]
    if (r.settled > 0n) parts.push(t('expenses.balances.sent', { amount: money(r.settled) }))
    else if (r.settled < 0n) parts.push(t('expenses.balances.received', { amount: money(-r.settled) }))
    const name = r.id ? personLabel(props.ledger, r.id, t, dir.value) : t('expenses.people.deletedPeople')
    return {
      key: r.id ?? 'deleted-people',
      name,
      // a listed member's picture; the letter for a name, a former member, the deleted people and on a guest's page
      userId: r.participant && standingOf(r.participant, dir.value) === 'member' ? r.participant.member : null,
      balance: r.balance,
      explained: parts.join(' · '),
      text: r.balance > 0n ? t('expenses.balances.owed', { amount: money(r.balance) }) : r.balance < 0n ? t('expenses.balances.owes', { amount: money(-r.balance) }) : t('expenses.balances.settled'),
    }
  }),
)
const transfers = computed(() => props.ledger.settleUp)
const busy = ref(false)
const failed = ref(false)

const own = computed(() => ownCategoryNames(props.meta))
const categories = computed(() =>
  [...props.ledger.overview.byCategory].map(([id, total]) => {
    const l = categoryLabel(id, own.value)
    return { id, name: 'key' in l ? t(l.key) : 'name' in l ? l.name : t('expenses.unknownCategory'), total }
  }).sort((a, b) => (b.total > a.total ? 1 : b.total < a.total ? -1 : 0)),
)
const total = computed(() => [...props.ledger.overview.byCategory.values()].reduce((a, b) => a + b, 0n))

function transferWrite(tr: Transfer): PotWrite {
  const base = props.meta.base
  return {
    entryId: crypto.randomUUID(),
    content: { kind: 'transfer', date: today(), currency: base.currency, minorUnits: base.minorUnits, rate: '1', amount: Number(tr.amount), receipts: [], from: tr.from, to: tr.to },
  }
}
async function record(list: readonly Transfer[]) {
  busy.value = true
  failed.value = false
  try {
    await props.session.write(list.map(transferWrite))
  } catch (e) {
    if (!(e instanceof Error && e.name === 'GuestDeclinedError')) failed.value = true
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="expenses-overview">
    <h2>{{ t('expenses.tabs.overview') }}</h2>
    <div class="veyq-form-field expenses-who-am-i">
      <label for="expenses-who-am-i">{{ t('expenses.whoAmI.label') }}</label>
      <VeyqSelect v-model="picked" input-id="expenses-who-am-i" :options="meOptions" />
      <span class="expenses-who-am-i-hint">{{ t('expenses.whoAmI.hint') }}</span>
    </div>

    <h3 class="expenses-subhead">{{ t('expenses.balances.title') }}</h3>
    <ul class="veyq-list expenses-balances">
      <li v-for="row in rows" :key="row.key" :class="['veyq-list-row', 'has-leading', 'expenses-static-row', { 'is-you': row.key === me }]" :data-person="row.key">
        <PersonAvatar :name="row.name" :user-id="row.userId" />
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ row.name }}</span>
          <span class="veyq-row-subtitle"><template v-if="row.key === me"><span class="expenses-you">{{ t('expenses.whoAmI.you') }}</span> · </template>{{ row.explained }}</span>
        </span>
        <span :class="['expenses-amount', { 'is-positive': row.balance > 0n, 'is-negative': row.balance < 0n }]">{{ row.text }}</span>
      </li>
    </ul>

    <div class="veyq-section-header expenses-subhead-row">
      <h3 class="expenses-subhead">{{ t('expenses.balances.settleUp') }}</h3>
      <VeyqButton v-if="canWrite && transfers.length > 1" :label="t('expenses.balances.recordAll')" icon="check" size="small" :loading="busy" @click="record(transfers)" />
    </div>
    <p v-if="!transfers.length" class="veyq-empty veyq-empty-inline">{{ t('expenses.balances.allSettled') }}</p>
    <ul v-else class="veyq-list expenses-settle-up">
      <li v-for="(tr, i) in transfers" :key="i" class="veyq-list-row expenses-static-row">
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ t('expenses.balances.pays', { from: personLabel(ledger, tr.from, t, dir), to: personLabel(ledger, tr.to, t, dir) }) }}</span>
        </span>
        <span class="expenses-amount">{{ money(tr.amount) }}</span>
        <VeyqButton v-if="canWrite" :label="t('expenses.balances.record')" variant="secondary" appearance="outlined" size="small" :disabled="busy" @click="record([tr])" />
      </li>
    </ul>
    <VeyqMessage v-if="failed" tone="error">{{ t('expenses.entry.saveFailed') }}</VeyqMessage>

    <h3 class="expenses-subhead">{{ t('expenses.overview.byCategory') }}</h3>
    <p v-if="!categories.length" class="veyq-empty veyq-empty-inline">{{ t('expenses.overview.empty') }}</p>
    <table v-else class="expenses-table expenses-categories">
      <tbody>
        <tr v-for="c in categories" :key="c.id">
          <th scope="row">{{ c.name }}</th>
          <td class="expenses-amount">{{ money(c.total) }}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <th scope="row">{{ t('expenses.overview.total') }}</th>
          <td class="expenses-amount">{{ money(total) }}</td>
        </tr>
      </tfoot>
    </table>
  </section>
</template>
