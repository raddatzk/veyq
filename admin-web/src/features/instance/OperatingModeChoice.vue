<script setup lang="ts">
// uc.admin.operator-details / uc.admin.setup-server step 4 — the operating mode's choice with its notice (ADR 0093
// § 14): the same block in first-run setup and in Server → Operating mode, so the notice can never be shown in one and
// skipped in the other. The notice comes first and stays visible; the tick appears with *private*, the only mode that
// needs it. Whether a choice may be sent is the facade's rule (`operatingModeRequest`), read here through
// `needsAcceptance` — this component only collects.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Checkbox from 'primevue/checkbox'
import SelectButton from 'primevue/selectbutton'
import { OperatingMode, needsAcceptance } from './operator'
import { OPERATING_MODE_NOTICE } from '../../i18n/operatingModeNotice'
import type { SupportedLocale } from '../../i18n'

const mode = defineModel<OperatingMode | null>('mode', { required: true })
const accepted = defineModel<boolean>('accepted', { required: true })
defineProps<{ disabled?: boolean }>()

const { t, locale } = useI18n()
const notice = computed(() => OPERATING_MODE_NOTICE[locale.value as SupportedLocale] ?? OPERATING_MODE_NOTICE.en)
const options = computed(() => [
  { value: OperatingMode.Private, label: t('operatingMode.private') },
  { value: OperatingMode.Organisation, label: t('operatingMode.organisation') },
])
</script>

<template>
  <div class="mode-choice">
    <div class="mode-choice__notice" data-test="mode-notice">
      <p>{{ notice.intro }}</p>
      <p>{{ notice.leavesOff }}</p>
      <p>{{ notice.exemption }}</p>
      <p>{{ notice.reversible }}</p>
    </div>
    <SelectButton
      v-model="mode"
      class="mode-choice__select"
      :options="options"
      option-label="label"
      option-value="value"
      :allow-empty="false"
      :disabled="disabled"
      :aria-label="t('operatingMode.choose')"
    />
    <label v-if="mode && needsAcceptance(mode)" class="mode-choice__accept">
      <Checkbox v-model="accepted" :binary="true" input-id="mode-accept" :disabled="disabled" />
      <span>{{ notice.accept }}</span>
    </label>
  </div>
</template>

<style scoped>
.mode-choice {
  display: grid;
  gap: 0.85rem;
}
.mode-choice__notice p {
  margin: 0 0 0.6rem;
  color: var(--app-muted);
  font-size: 0.92rem;
  line-height: 1.45;
}
.mode-choice__accept {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
