<script setup lang="ts">
// uc.admin.learn-of-new-releases (admin-web, ADR 0097) — the second half of the "Release" rubric, under the running
// release: what the daily check found, and the check itself (on or off, where it reads, when it last succeeded).
// What it keeps honest:
//   - an unscheduled major is the one notice that means "now", so it renders as an error, never as one row among others;
//   - a build with no release stamp checks nothing, whatever the switch says — the panel says so instead of showing an
//     empty list that would read as "nothing newer";
//   - switching the check off asks nothing (it only stops a request), and the panel re-renders from the server's answer.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  getReleaseNotices, InstanceReleaseNoticeKindEnum, isUrgent, noticeDay, setReleaseCheck, UPGRADE_PAGE,
  type InstanceReleaseNotice, type InstanceReleaseNotices,
} from './releaseNotices'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())

const state = ref<InstanceReleaseNotices | null>(null)
const enabled = ref(true)
const source = ref('')
const actionError = ref('')

function apply(s: InstanceReleaseNotices) {
  state.value = s
  enabled.value = s.enabled
  source.value = s.source ?? ''
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getReleaseNotices()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const { run: runSave, pending: saving } = useAsyncAction(
  async () => apply(await setReleaseCheck(enabled.value, source.value.trim() || null)),
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { code } = await decodeHttpError(e)
      actionError.value = t(code === 'invalid_request' ? 'releaseCheck.invalidSource' : 'releaseCheck.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  await runSave()
}

/** One notice in words: what was found and, for a dated one, the day. */
function line(n: InstanceReleaseNotice): string {
  const day = noticeDay(n.date) ?? ''
  const major = n.release.split('.')[0]
  switch (n.kind) {
    case InstanceReleaseNoticeKindEnum.Release: return t('releaseCheck.kindRelease', { release: n.release })
    case InstanceReleaseNoticeKindEnum.PlannedMajor: return t('releaseCheck.kindPlanned', { major, release: n.release, day })
    case InstanceReleaseNoticeKindEnum.UnscheduledMajor: return t('releaseCheck.kindUnscheduled', { major, release: n.release })
    default: return t('releaseCheck.kindReminder', { major, day, days: n.daysAhead ?? '' })
  }
}

const urgent = computed(() => state.value?.notices.filter(isUrgent) ?? [])
const others = computed(() => state.value?.notices.filter((n) => !isUrgent(n)) ?? [])
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('releaseCheck.title') }}</h2>
          <p>{{ t('releaseCheck.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('releaseCheck.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('release.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('releaseCheck.loadFailed') }}</Message>

      <template v-else-if="state">
        <Message v-for="n in urgent" :key="n.id" severity="error" :closable="false" class="notice notice--urgent">
          <strong>{{ t('releaseCheck.urgent') }}</strong> {{ line(n) }}
        </Message>

        <Message v-if="!state.releaseLine" severity="info" :closable="false">{{ t('releaseCheck.noReleaseLine') }}</Message>
        <ul v-if="others.length" class="notice-list">
          <li v-for="n in others" :key="n.id" class="notice" :data-kind="n.kind">{{ line(n) }}</li>
        </ul>
        <p v-else-if="state.releaseLine && !urgent.length" class="settings-muted notice-none">{{ t('releaseCheck.none') }}</p>
        <p v-if="state.notices.length" class="settings-muted"><a :href="UPGRADE_PAGE" target="_blank" rel="noopener">{{ t('releaseCheck.upgradeLink') }}</a></p>

        <h3>{{ t('releaseCheck.checkTitle') }}</h3>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="check-toggle">
          <Checkbox v-model="enabled" input-id="release-check-enabled" binary />
          <label for="release-check-enabled">{{ t('releaseCheck.enabled') }}</label>
        </div>
        <p class="settings-muted check-state">
          {{ !state.enabled ? t('releaseCheck.off')
            : state.lastSuccessAt ? t('releaseCheck.lastSuccess', { at: state.lastSuccessAt.toLocaleString(locale) })
            : t('releaseCheck.neverSucceeded') }}
        </p>
        <label>
          <span>{{ t('releaseCheck.source') }}</span>
          <InputText v-model="source" class="check-source" :placeholder="t('releaseCheck.sourcePlaceholder')" autocomplete="off" />
          <small class="settings-muted">{{ t('releaseCheck.sourceHint') }}</small>
        </label>
        <div>
          <Button type="button" class="check-save" :label="t('releaseCheck.save')" icon="pi pi-check" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.notice-list {
  margin: 0;
  padding-left: 1.2rem;
  display: grid;
  gap: 0.35rem;
}
.check-toggle {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
</style>
