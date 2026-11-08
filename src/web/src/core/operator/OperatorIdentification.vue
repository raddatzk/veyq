<script setup lang="ts">
// uc.admin.operator-details — the footer on the login page, the share page and the signed-in shell that says who runs
// this instance and under which terms:
//  - the terms of this instance, always: the public page of the operator section of the terms (ADR 0035 decision 6;
//    DSA Art. 14(1) asks the terms to be publicly available and easily accessible). It needs no read, so it is there
//    even while the operator's values cannot be read; it opens in a tab of its own, so a share page keeps its key;
//  - the instance privacy notice, always, the same way (ADR 0035 decision 8, GDPR Art. 13: one click from every page);
//  - the veyq project's own two links, always (uc.platform.support-the-project, ADR 0122): "Why is veyq secure?" and
//    "Donate", to the project's website in the reader's language (`core/project/projectLinks.ts`, their one home), each
//    in a tab of its own so a share page keeps its key;
//  - behind "Legal notice", the provider identification (ADR 0035 decision 7, § 5 DDG) and the single point of contact
//    for users and authorities with its languages (ADR 0093 § 11, DSA Art. 11-12, TCO Art. 15) — each where the Server
//    Owner published it; the link is absent while neither is set or the read failed
//    (fail-open, `operatorDetails.ts`), so it never stands between a visitor and the page.
// A guest-mode mount: the read is the public client, never an authenticated one.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { loadPublishedOperator, type PublishedOperator } from './operatorDetails'
import { OPERATOR_TERMS_PATH } from '../terms/operatorSection'
import { privacyLink } from '../privacy/privacyNotice'
import { PROJECT_LINK_REL, projectPageUrl } from '../project/projectLinks'

const { t, locale } = useI18n()
const published = ref<PublishedOperator>({ identification: null, contact: null })
const open = ref(false)

onMounted(async () => {
  published.value = await loadPublishedOperator()
})
</script>

<template>
  <span class="operator-identification">
    <a class="operator-identification__terms" :href="OPERATOR_TERMS_PATH" target="_blank" rel="noopener">{{ t('operatorDetails.terms') }}</a>
    <a class="operator-identification__privacy" :href="privacyLink()" target="_blank" rel="noopener">{{ t('operatorDetails.privacy') }}</a>
    <a class="operator-identification__project" :href="projectPageUrl('security', locale)" target="_blank" :rel="PROJECT_LINK_REL">{{ t('project.security') }}</a>
    <a class="operator-identification__project" :href="projectPageUrl('donate', locale)" target="_blank" :rel="PROJECT_LINK_REL">{{ t('project.donate') }}</a>
    <template v-if="published.identification || published.contact">
      <button type="button" class="operator-identification__link" :aria-expanded="open" @click="open = !open">
        {{ t('operatorDetails.link') }}
      </button>
      <address v-if="open" class="operator-identification__values" :aria-label="t('operatorDetails.title')">
        <template v-if="published.identification">
          <strong v-if="published.identification.name">{{ published.identification.name }}</strong>
          <span v-if="published.identification.address" class="operator-identification__address">{{ published.identification.address }}</span>
          <a v-if="published.identification.email" :href="`mailto:${published.identification.email}`">{{ published.identification.email }}</a>
          <span v-if="published.identification.representative">{{ t('operatorDetails.representative', { name: published.identification.representative }) }}</span>
        </template>
        <template v-if="published.contact">
          <span class="operator-identification__contact">
            {{ t('operatorDetails.contact') }} <a :href="`mailto:${published.contact.email}`">{{ published.contact.email }}</a>
          </span>
          <span v-if="published.contact.languages.length" class="operator-identification__languages">
            {{ t('operatorDetails.contactLanguages', { langs: published.contact.languages.join(', ') }) }}
          </span>
        </template>
      </address>
    </template>
  </span>
</template>

<style scoped>
.operator-identification {
  display: inline-flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: baseline;
  gap: 0.25rem 1rem;
}
.operator-identification__terms,
.operator-identification__privacy,
.operator-identification__project {
  color: var(--app-muted);
  font-size: 0.82rem;
}
.operator-identification__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--app-muted);
  font: inherit;
  font-size: 0.82rem;
  text-decoration: underline;
  cursor: pointer;
}
.operator-identification__values {
  display: grid;
  flex-basis: 100%;
  gap: 0.2rem;
  margin-top: 0.5rem;
  color: var(--app-muted);
  font-size: 0.82rem;
  font-style: normal;
  text-align: center;
}
.operator-identification__address {
  white-space: pre-line;
}
.operator-identification__values a {
  color: var(--app-accent);
}
</style>
