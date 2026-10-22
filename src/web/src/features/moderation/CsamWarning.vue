<script setup lang="ts">
// Before a case of child sexual abuse material opens (ADR 0093 § 13, uc.moderation.report-to-an-authority): a full page
// that says what never to do and what to do first — once known, serving it on is a making-available of the host's own,
// and a file or a screenshot is the thing never to make. Its words are ADR 0093 § 13's, a DRAFT until a qualified legal
// review, which the page says. Nothing opens until the moderator continues.
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'

const emit = defineEmits<{ continue: []; back: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="veyq-section moderation-csam-warning" role="alertdialog" aria-labelledby="moderation-csam-title">
    <h3 id="moderation-csam-title">{{ t('moderation.csam.title') }}</h3>
    <p class="veyq-form-copy moderation-csam-draft">{{ t('moderation.csam.draft') }}</p>
    <ul class="moderation-csam-rules">
      <li>{{ t('moderation.csam.dont') }}</li>
      <li>{{ t('moderation.csam.review') }}</li>
      <li>{{ t('moderation.csam.block') }}</li>
      <li>{{ t('moderation.csam.inform') }}</li>
    </ul>
    <div class="veyq-action-group">
      <VeyqButton variant="secondary" class="moderation-csam-back" icon="arrow-left" :label="t('moderation.csam.back')" @click="emit('back')" />
      <VeyqButton variant="danger" class="moderation-csam-continue" icon="exclamation-triangle" :label="t('moderation.csam.continue')" @click="emit('continue')" />
    </div>
  </div>
</template>
