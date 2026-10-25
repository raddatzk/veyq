<script setup lang="ts">
// An entry's history (uc.expenses.entry-history, ADR 0108 decisions 3 and 6): every revision the Pot holds of it, newest
// first, with when the server stored it and what it said — and, for a member, who wrote it (the writing member or link
// guest, recorded by the platform: a member's read carries the writer, a guest's schema has none, decision 3). A
// revision that did not open says so instead of a guess.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Person } from '../../../core/people/directory'
import type { PotSession } from '../potSession'
import { isMoney } from '../domain/format'
import { convertToBase } from '../domain/money'
import type { PotMeta } from '../domain/potMeta'
import { formatMoney } from '../amounts'

const props = defineProps<{
  session: PotSession
  meta: PotMeta
  entryId: string
  writers: boolean
  people: ReadonlyMap<string, Person> | null
}>()
const { t, locale } = useI18n()

const when = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))

const rows = computed(() =>
  props.session.held.value
    .filter((r) => r.entryId === props.entryId)
    .sort((a, b) => b.revision - a.revision)
    .map((r) => {
      const body = r.body
      let what: string
      if (!body) what = t('expenses.history.unreadable')
      else if (body.kind === 'deleted') what = t('expenses.history.deleted')
      else if (isMoney(body)) {
        const amount = convertToBase(BigInt(body.amount), body.minorUnits, body.rate, props.meta.base.minorUnits)
        const money = formatMoney(amount, props.meta.base.currency, props.meta.base.minorUnits, locale.value)
        what = body.title ? `${body.title} · ${money}` : money
      } else what = t('expenses.history.other')
      const writer = props.writers ? props.session.writerName(r.writer, props.people) : null
      return {
        key: `${r.entryId}:${r.revision}`,
        what,
        when: when.value.format(r.createdAt),
        writer: props.writers ? (writer ?? t('expenses.history.unknownWriter')) : null,
      }
    }),
)
</script>

<template>
  <section class="expenses-history" :aria-label="t('expenses.history.title')">
    <ol class="veyq-list">
      <li v-for="row in rows" :key="row.key" class="veyq-list-row expenses-history-row">
        <span class="veyq-row-main">
          <span class="veyq-row-title">{{ row.what }}</span>
          <span class="veyq-row-subtitle">{{ row.writer ? t('expenses.history.byAt', { name: row.writer, when: row.when }) : row.when }}</span>
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.expenses-history-row {
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
}
</style>
