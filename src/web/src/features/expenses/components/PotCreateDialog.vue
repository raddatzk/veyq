<script setup lang="ts">
// The Pot's `createDialog` (uc.expenses.create-pot, ADR 0108 decision 2): a name, the base currency — sealed into the
// node's meta at creation, fixed once the first entry exists — and its first people, appended as one batch after the
// node is created (ADR 0108 decision 1). Only a member creates a Pot (`../rights.ts`' `createPot`; Expenses declares no
// guest-creatable kind). A name that collides with a sibling is refused before sending (core's `siblingCollision.ts`).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { NodeCreateDialogEmits, NodeCreateDialogProps } from '../../../core/applications/registry'
import { memberCreateOptions } from '../../../core/node/rereadNode'
import { treeStore } from '../../../core/node/treeStore'
import { collidingSibling, nameTakenMessage } from '../../../core/node/siblingCollision'
import { httpStatus, HttpStatus } from '../../../shared/httpError'
import { knownCurrencies, minorUnitsOf } from '../amounts'
import { createPot, PotPeopleNotAdded } from '../createPot'
import { POT_KIND } from '../kinds'

const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t, locale } = useI18n()

/** The reader's own currency as a first guess: the euro for German, else the dollar. */
const name = ref('')
const currency = ref(locale.value === 'de' ? 'EUR' : 'USD')
const people = ref<string[]>(['', ''])
const loading = ref(false)
const error = ref<string | null>(null)
const currencyOptions = computed(() => knownCurrencies().map((c) => ({ value: c, label: c })))
const names = computed(() => people.value.map((p) => p.trim()).filter((p) => p.length > 0))
const canSubmit = computed(() => !loading.value && name.value.trim().length > 0 && !!currency.value && names.value.every((n) => n.length <= 100))

async function submit() {
  if (!canSubmit.value) return
  error.value = null
  const sibling = collidingSibling(treeStore.nodes, props.parentId ?? null, POT_KIND, name.value.trim())
  if (sibling) {
    error.value = nameTakenMessage(t, sibling)
    return
  }
  loading.value = true
  try {
    const parent = props.parentId && props.parentKey && props.parentEpoch != null ? { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch } : null
    await createPot(props.scopeId, props.epochs, parent, name.value.trim(), { currency: currency.value, minorUnits: minorUnitsOf(currency.value) }, names.value, memberCreateOptions(props.scopeId, parent))
    emit('done')
    emit('close')
  } catch (e) {
    if (e instanceof PotPeopleNotAdded) {
      // The Pot exists; its people can be added on its page.
      emit('done')
      error.value = t('expenses.create.peopleFailed')
    } else error.value = httpStatus(e) === HttpStatus.conflict ? t('node.slugTaken') : t('expenses.create.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('expenses.create.title')" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <VeyqInput v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>
      <label>
        {{ t('expenses.baseCurrency') }}
        <VeyqSelect v-model="currency" :options="currencyOptions" :disabled="loading" />
        <span class="veyq-form-copy">{{ t('expenses.create.currencyHint') }}</span>
      </label>
      <fieldset class="expenses-create-people">
        <legend>{{ t('expenses.tabs.people') }}</legend>
        <VeyqInput
          v-for="(_, i) in people"
          :key="i"
          v-model="people[i]"
          autocomplete="off"
          :aria-label="t('expenses.people.nameLabel')"
          :placeholder="t('expenses.people.nameLabel')"
          :disabled="loading"
        />
        <VeyqButton :label="t('expenses.people.add')" icon="user-plus" variant="secondary" appearance="text" size="small" :disabled="loading" @click="people.push('')" />
      </fieldset>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.create')" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.expenses-create-people {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
  margin: 0;
  padding: 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
}
</style>
