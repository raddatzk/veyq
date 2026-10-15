<script setup lang="ts">
// The Memory date editor (cr-0067, uc.memory.date main flow §2). A mode switch — `auto` / `single` /
// `range` / `ongoing` — reveals the fitting inputs (none / one day / from+to / a start). `auto` shows the
// materialized range derived from the Memory's media (read-only preview); the manual modes take `YYYY-MM-DD`
// days via the ported IsoDateField. On save the parent seals the chosen [MemoryDate] under `K_node` — this
// component is presentation only, it never touches crypto. Invalid manual range (`to` < `from`) is blocked (E4).
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import IsoDateField from './IsoDateField.vue'
import { formatClosedRange, isValidMemoryDateRange } from '../memoryDateRange'
import type { MemoryDate, MemoryDateMode } from '../memoryDate'

const props = defineProps<{
  date: MemoryDate | null // the current stored date (null = the implicit empty `auto`)
  autoRange: MemoryDate // the materialized `auto` range derived from the Memory's media (preview + save value)
}>()
const emit = defineEmits<{ save: [MemoryDate]; close: [] }>()
const { t, locale } = useI18n()

const modeOptions = computed<{ label: string; value: MemoryDateMode }[]>(() => [
  { label: t('memory.date.mode.auto'), value: 'auto' },
  { label: t('memory.date.mode.single'), value: 'single' },
  { label: t('memory.date.mode.range'), value: 'range' },
  { label: t('memory.date.mode.ongoing'), value: 'ongoing' },
])

const mode = ref<MemoryDateMode>(props.date?.mode ?? 'auto')
const from = ref(props.date?.from ?? '')
const to = ref(props.date?.to ?? '')

const autoPreview = computed(() => formatClosedRange(props.autoRange.from, props.autoRange.to, locale.value))
const rangeInvalid = computed(() => mode.value === 'range' && Boolean(from.value) && Boolean(to.value) && !isValidMemoryDateRange(from.value, to.value))

const canSave = computed(() => {
  switch (mode.value) {
    case 'auto':
      return true
    case 'single':
    case 'ongoing':
      return Boolean(from.value)
    case 'range':
      return isValidMemoryDateRange(from.value, to.value)
    default:
      return false
  }
})

function buildDate(): MemoryDate {
  switch (mode.value) {
    case 'auto':
      return { ...props.autoRange, mode: 'auto' } // seal the materialized range so the card shows it at once
    case 'single':
      return { mode: 'single', from: from.value, to: from.value }
    case 'range':
      return { mode: 'range', from: from.value, to: to.value }
    case 'ongoing':
      return { mode: 'ongoing', from: from.value }
  }
}

function save() {
  if (!canSave.value) return
  emit('save', buildDate())
  emit('close')
}
</script>

<template>
  <Dialog :visible="true" :header="t('memory.date.title')" modal @update:visible="emit('close')">
    <form class="veyq-form memory-date-editor" @submit.prevent="save">
      <SelectButton
        v-model="mode"
        :options="modeOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        :aria-label="t('memory.date.title')"
      />

      <p v-if="mode === 'auto'" class="memory-date-auto">
        <span class="memory-date-auto-value">{{ autoPreview || t('memory.date.noDate') }}</span>
        <span class="memory-date-auto-hint">{{ autoPreview ? t('memory.date.autoHint') : t('memory.date.autoEmpty') }}</span>
      </p>

      <label v-else-if="mode === 'single'">
        {{ t('memory.date.day') }}
        <IsoDateField v-model="from" :aria-label="t('memory.date.day')" />
      </label>

      <template v-else-if="mode === 'range'">
        <label>
          {{ t('memory.date.from') }}
          <IsoDateField v-model="from" :max-date="to" :aria-label="t('memory.date.from')" />
        </label>
        <label>
          {{ t('memory.date.to') }}
          <IsoDateField v-model="to" :min-date="from" :aria-label="t('memory.date.to')" />
        </label>
      </template>

      <label v-else>
        {{ t('memory.date.start') }}
        <IsoDateField v-model="from" :aria-label="t('memory.date.start')" />
      </label>

      <Message v-if="rangeInvalid" severity="error" :closable="false">{{ t('memory.date.invalidRange') }}</Message>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <Button type="submit" :label="t('common.save')" icon="pi pi-check" :disabled="!canSave" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.memory-date-editor {
  min-width: min(360px, 80vw);
}
.memory-date-auto {
  display: grid;
  gap: 0.2rem;
  margin: 0;
}
.memory-date-auto-value {
  font-weight: 700;
}
.memory-date-auto-hint {
  color: var(--app-muted);
  font-size: 0.86rem;
}
</style>
