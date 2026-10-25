<script setup lang="ts">
// A Pot's settings (ADR 0108 decision 2, a member's only — `../rights.ts`' `settings`): its base currency, fixed from its
// first entry on because every entry carries its rate to it, and its own categories beside the default set, added and
// renamed but never removed in the first cut (an entry names one by id). Written whole into the Pot's sealed meta with the
// revision read (`PotSession.writeMeta`, ADR 0077): the change runs again on what the server holds after a conflict, so
// two members adding a category at once both keep theirs.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { MAX_CATEGORY_NAME, MAX_OWN_CATEGORIES, type PotMeta } from '../domain/potMeta'
import type { PotLedger } from '../domain/ledger'
import type { PotSession } from '../potSession'
import { knownCurrencies, minorUnitsOf } from '../amounts'

const props = defineProps<{ session: PotSession; meta: PotMeta | null; ledger: PotLedger | null }>()
const { t } = useI18n()

/** The base currency is fixed once the Pot holds any money entry, live or deleted. */
const fixed = computed(() => (props.ledger?.entries.length ?? 0) > 0)
const currency = ref(props.meta?.base.currency ?? '')
watch(() => props.meta?.base.currency, (c) => (currency.value = c ?? ''))
const currencyOptions = computed(() => knownCurrencies().map((c) => ({ value: c, label: c })))
const newCategory = ref('')
const editing = ref<string | null>(null)
const editName = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

async function run(change: (current: PotMeta | null) => PotMeta): Promise<boolean> {
  busy.value = true
  error.value = null
  try {
    await props.session.writeMeta(change)
    return true
  } catch {
    error.value = t('expenses.settings.failed')
    return false
  } finally {
    busy.value = false
  }
}

function setCurrency(next: string | null | undefined) {
  if (!next || fixed.value || next === props.meta?.base.currency) return
  void run((current) => ({ base: { currency: next, minorUnits: minorUnitsOf(next) }, categories: current?.categories ?? [] }))
}
async function addCategory() {
  const name = newCategory.value.trim()
  if (!name || name.length > MAX_CATEGORY_NAME || !props.meta) return
  const id = crypto.randomUUID()
  const ok = await run((current) => {
    const now = current ?? props.meta!
    return { ...now, categories: [...now.categories.filter((c) => c.id !== id), { id, name }] }
  })
  if (ok) newCategory.value = ''
}
async function renameCategory(id: string) {
  const name = editName.value.trim()
  if (!name || name.length > MAX_CATEGORY_NAME || !props.meta) return
  const ok = await run((current) => {
    const now = current ?? props.meta!
    return { ...now, categories: now.categories.map((c) => (c.id === id ? { id, name } : c)) }
  })
  if (ok) editing.value = null
}
</script>

<template>
  <section class="expenses-settings">
    <h2>{{ t('expenses.tabs.settings') }}</h2>
    <form class="veyq-form" @submit.prevent>
      <label>
        {{ t('expenses.baseCurrency') }}
        <VeyqSelect :model-value="currency" :options="currencyOptions" :disabled="fixed || busy" @update:model-value="setCurrency" />
        <span class="veyq-form-copy">{{ fixed ? t('expenses.settings.currencyFixed') : t('expenses.create.currencyHint') }}</span>
      </label>
    </form>

    <template v-if="meta">
      <h3 class="expenses-subhead">{{ t('expenses.settings.categories') }}</h3>
      <p class="veyq-form-copy">{{ t('expenses.settings.categoriesHint') }}</p>
      <ul v-if="meta.categories.length" class="veyq-list">
        <li v-for="c in meta.categories" :key="c.id" class="veyq-list-row expenses-static-row">
          <span v-if="editing === c.id" class="expenses-inline-form">
            <VeyqInput v-model="editName" autocomplete="off" :aria-label="t('expenses.settings.categoryName')" @keydown.enter.prevent="renameCategory(c.id)" />
            <VeyqButton :label="t('common.save')" size="small" :loading="busy" @click="renameCategory(c.id)" />
            <VeyqButton :label="t('common.cancel')" variant="secondary" appearance="outlined" size="small" :disabled="busy" @click="editing = null" />
          </span>
          <template v-else>
            <span class="veyq-row-main"><span class="veyq-row-title">{{ c.name }}</span></span>
            <VeyqButton
              class="veyq-icon-button"
              icon="pencil"
              variant="secondary"
              appearance="outlined"
              size="small"
              :aria-label="t('expenses.settings.renameCategory', { name: c.name })"
              :disabled="busy"
              @click="(editing = c.id), (editName = c.name)"
            />
          </template>
        </li>
      </ul>
      <form v-if="meta.categories.length < MAX_OWN_CATEGORIES" class="expenses-inline-form" @submit.prevent="addCategory">
        <VeyqInput v-model="newCategory" autocomplete="off" :aria-label="t('expenses.settings.categoryName')" :placeholder="t('expenses.settings.categoryName')" />
        <VeyqButton type="submit" :label="t('expenses.settings.addCategory')" icon="plus" size="small" :loading="busy" :disabled="!newCategory.trim()" />
      </form>
    </template>
    <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
  </section>
</template>
