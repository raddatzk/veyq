<script setup lang="ts">
// A Pot's overviews (uc.expenses.balances, docs/apps/expenses.md § What the client computes): the cost per category, and
// per person what they paid beside what their share was — either alone misleads. Refunds count against their category;
// transfers are not costs. Computed in the client from the live, valid entries (`ledger.ts`'s `overviewOf`). The viewer's
// own row is marked *you*, as they chose in the balances.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { categoryLabel } from '../domain/categories'
import { ownCategoryNames, type PotMeta } from '../domain/potMeta'
import type { PotLedger } from '../domain/ledger'
import { formatMoney } from '../amounts'
import { personLabel, type MemberDirectory } from '../people'
import { whoAmI } from '../whoAmI'

const props = defineProps<{ ledger: PotLedger; meta: PotMeta; potId: string; directory?: MemberDirectory }>()
/** The viewer's own row, as they chose in the balances ("Who am I", `../whoAmI.ts`). */
const me = computed(() => whoAmI(props.potId).me.value)
const { t, locale } = useI18n()

const money = (v: bigint) => formatMoney(v, props.meta.base.currency, props.meta.base.minorUnits, locale.value)
const own = computed(() => ownCategoryNames(props.meta))
const categories = computed(() =>
  [...props.ledger.overview.byCategory].map(([id, total]) => {
    const l = categoryLabel(id, own.value)
    return { id, name: 'key' in l ? t(l.key) : 'name' in l ? l.name : t('expenses.unknownCategory'), total }
  }).sort((a, b) => (b.total > a.total ? 1 : b.total < a.total ? -1 : 0)),
)
const total = computed(() => [...props.ledger.overview.byCategory.values()].reduce((a, b) => a + b, 0n))
const persons = computed(() => {
  // Every erased person is one "deleted people" here too (the screen merges them, the data does not).
  const rows = new Map<string, { name: string; paid: bigint; share: bigint }>()
  for (const [id, v] of props.ledger.overview.byPerson) {
    const p = props.ledger.participants.find((x) => x.id === id)
    const key = p?.erased ? 'deleted-people' : id
    const row = rows.get(key) ?? { name: personLabel(props.ledger, id, t, props.directory ?? null), paid: 0n, share: 0n }
    row.paid += v.paid
    row.share += v.share
    rows.set(key, row)
  }
  return [...rows.entries()].map(([key, r]) => ({ key, ...r }))
})
</script>

<template>
  <section class="expenses-overview">
    <h2>{{ t('expenses.tabs.overview') }}</h2>
    <p v-if="!categories.length" class="veyq-empty veyq-empty-inline">{{ t('expenses.overview.empty') }}</p>
    <template v-else>
      <h3 class="expenses-subhead">{{ t('expenses.overview.byCategory') }}</h3>
      <table class="expenses-table">
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
      <h3 class="expenses-subhead">{{ t('expenses.overview.byPerson') }}</h3>
      <table class="expenses-table">
        <thead>
          <tr>
            <th scope="col">{{ t('expenses.overview.person') }}</th>
            <th scope="col" class="expenses-amount">{{ t('expenses.overview.paid') }}</th>
            <th scope="col" class="expenses-amount">{{ t('expenses.overview.share') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in persons" :key="p.key" :class="{ 'is-you': p.key === me }" :data-person="p.key">
            <th scope="row">{{ p.name }}<span v-if="p.key === me" class="expenses-you"> · {{ t('expenses.whoAmI.you') }}</span></th>
            <td class="expenses-amount">{{ money(p.paid) }}</td>
            <td class="expenses-amount">{{ money(p.share) }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>
