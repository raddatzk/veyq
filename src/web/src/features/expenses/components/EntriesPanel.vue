<script setup lang="ts">
// A Pot's entries (uc.expenses.record-entries): the live ones newest first — an invalid one marked and counted nowhere
// (owner 2026-10-24), one that did not open marked as such — and below them the Pot's own list of deleted entries, each
// opened to read or restore (a deleted entry stays inside its Pot, ADR 0108 decision 6). A row opens the entry dialog.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { categoryLabel } from '../domain/categories'
import { ownCategoryNames, type PotMeta } from '../domain/potMeta'
import type { LedgerEntry, PotLedger } from '../domain/ledger'
import { formatMoney } from '../amounts'
import { personLabel } from '../people'

const props = defineProps<{ ledger: PotLedger; meta: PotMeta; canWrite: boolean }>()
const emit = defineEmits<{ open: [entryId: string | null] }>()
const { t, locale } = useI18n()

const byDate = (a: LedgerEntry, b: LedgerEntry) => (b.content?.date ?? '').localeCompare(a.content?.date ?? '') || a.id.localeCompare(b.id)
const live = computed(() => props.ledger.entries.filter((e) => e.status !== 'deleted').sort(byDate))
const deleted = computed(() => props.ledger.entries.filter((e) => e.status === 'deleted').sort(byDate))
const own = computed(() => ownCategoryNames(props.meta))
const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeZone: 'UTC' }))

function title(e: LedgerEntry): string {
  const c = e.content
  if (!c) return t('expenses.entries.unreadable')
  if (c.kind === 'transfer') return c.title || t('expenses.entries.transferTitle', { from: personLabel(props.ledger, c.from, t), to: personLabel(props.ledger, c.to, t) })
  return c.title
}
function subtitle(e: LedgerEntry): string {
  const c = e.content
  if (!c) return ''
  const parts = [dateFormat.value.format(new Date(`${c.date}T00:00:00Z`))]
  if (c.kind !== 'transfer') {
    const l = categoryLabel(c.category, own.value)
    parts.push('key' in l ? t(l.key) : 'name' in l ? l.name : t('expenses.unknownCategory'))
    const who = (c.kind === 'expense' ? c.payers : c.receivers).map((p) => personLabel(props.ledger, p.participant, t))
    parts.push(t(c.kind === 'expense' ? 'expenses.entries.paidBy' : 'expenses.entries.receivedBy', { names: [...new Set(who)].join(', ') }))
  }
  if (c.currency !== props.meta.base.currency) parts.push(formatMoney(BigInt(c.amount), c.currency, c.minorUnits, locale.value))
  return parts.join(' · ')
}
function amount(e: LedgerEntry): string {
  if (e.effect) return formatMoney(e.content?.kind === 'refund' ? -e.effect.base : e.effect.base, props.meta.base.currency, props.meta.base.minorUnits, locale.value)
  return ''
}
</script>

<template>
  <section class="expenses-entries">
    <div class="veyq-section-header">
      <h2>{{ t('expenses.tabs.entries') }}</h2>
      <VeyqButton v-if="canWrite" :label="t('expenses.entries.add')" icon="plus" size="small" @click="emit('open', null)" />
    </div>
    <p v-if="!live.length" class="veyq-empty veyq-empty-inline">{{ canWrite ? t('expenses.entries.empty') : t('expenses.entries.emptyReadOnly') }}</p>
    <ul v-else class="veyq-list">
      <li v-for="e in live" :key="e.id">
        <button type="button" class="veyq-list-row expenses-row" :data-entry="e.id" @click="emit('open', e.id)">
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ title(e) }}</span>
            <span class="veyq-row-subtitle">{{ subtitle(e) }}</span>
          </span>
          <span class="expenses-row-badges">
            <span v-if="e.status === 'invalid'" class="veyq-badge warning">{{ t('expenses.entries.invalid') }}</span>
            <span v-else-if="e.status === 'unreadable'" class="veyq-badge warning">{{ t('expenses.entries.unreadableBadge') }}</span>
            <span v-if="e.edited" class="veyq-badge muted">{{ t('expenses.entries.edited') }}</span>
          </span>
          <span class="expenses-amount">{{ amount(e) }}</span>
        </button>
      </li>
    </ul>

    <template v-if="deleted.length">
      <h3 class="expenses-subhead">{{ t('expenses.entries.deletedTitle') }}</h3>
      <ul class="veyq-list">
        <li v-for="e in deleted" :key="e.id">
          <button type="button" class="veyq-list-row expenses-row" :data-entry="e.id" @click="emit('open', e.id)">
            <span class="veyq-row-main">
              <span class="veyq-row-title">{{ title(e) }}</span>
              <span class="veyq-row-subtitle">{{ subtitle(e) }}</span>
            </span>
            <span class="expenses-row-badges"><span class="veyq-badge muted">{{ t('expenses.entries.deletedBadge') }}</span></span>
            <span class="expenses-amount" />
          </button>
        </li>
      </ul>
    </template>
  </section>
</template>
