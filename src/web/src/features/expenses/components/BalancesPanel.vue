<script setup lang="ts">
// A Pot's balances and its settle-up suggestion (uc.expenses.balances, docs/apps/expenses.md § What the client computes):
// who is owed and who owes, every erased participant summed into one "deleted people", a settled deactivated one left
// out until an edit unsettles them. The suggestion is `ledger.ts`'s greedy one, the same on every device; a writer records
// one transfer of it, or all of them in one batch (ADR 0108 decision 1: one append, all or nothing).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { PotLedger, Transfer } from '../domain/ledger'
import type { PotMeta } from '../domain/potMeta'
import type { PotSession } from '../potSession'
import type { PotWrite } from '../domain/potApi'
import { formatMoney } from '../amounts'
import { personLabel, shownBalances } from '../people'
import { today } from '../entryForm'

const props = defineProps<{ session: PotSession; ledger: PotLedger; meta: PotMeta; canWrite: boolean }>()
const { t, locale } = useI18n()

const money = (v: bigint) => formatMoney(v, props.meta.base.currency, props.meta.base.minorUnits, locale.value)
const rows = computed(() =>
  shownBalances(props.ledger).map((r) => ({
    key: r.id ?? 'deleted-people',
    name: r.id ? personLabel(props.ledger, r.id, t) : t('expenses.people.deletedPeople'),
    balance: r.balance,
    text: r.balance > 0n ? t('expenses.balances.owed', { amount: money(r.balance) }) : r.balance < 0n ? t('expenses.balances.owes', { amount: money(-r.balance) }) : t('expenses.balances.settled'),
  })),
)
const transfers = computed(() => props.ledger.settleUp)
const busy = ref(false)
const failed = ref(false)

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
  <section class="expenses-balances">
    <h2>{{ t('expenses.tabs.balances') }}</h2>
    <ul class="veyq-list">
      <li v-for="row in rows" :key="row.key" class="veyq-list-row expenses-static-row">
        <span class="veyq-row-main"><span class="veyq-row-title">{{ row.name }}</span></span>
        <span :class="['expenses-amount', { 'is-positive': row.balance > 0n, 'is-negative': row.balance < 0n }]">{{ row.text }}</span>
      </li>
    </ul>

    <div class="veyq-section-header expenses-subhead-row">
      <h3 class="expenses-subhead">{{ t('expenses.balances.settleUp') }}</h3>
      <VeyqButton v-if="canWrite && transfers.length > 1" :label="t('expenses.balances.recordAll')" icon="check" size="small" :loading="busy" @click="record(transfers)" />
    </div>
    <p v-if="!transfers.length" class="veyq-empty veyq-empty-inline">{{ t('expenses.balances.allSettled') }}</p>
    <ul v-else class="veyq-list">
      <li v-for="(tr, i) in transfers" :key="i" class="veyq-list-row expenses-static-row">
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ t('expenses.balances.pays', { from: personLabel(ledger, tr.from, t), to: personLabel(ledger, tr.to, t) }) }}</span>
        </span>
        <span class="expenses-amount">{{ money(tr.amount) }}</span>
        <VeyqButton v-if="canWrite" :label="t('expenses.balances.record')" variant="secondary" appearance="outlined" size="small" :disabled="busy" @click="record([tr])" />
      </li>
    </ul>
    <VeyqMessage v-if="failed" tone="error">{{ t('expenses.entry.saveFailed') }}</VeyqMessage>
  </section>
</template>
