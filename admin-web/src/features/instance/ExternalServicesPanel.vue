<script setup lang="ts">
// uc.admin.configure-external-services — the "External services" rubric of the server settings, beside Capabilities:
// the address of each outside service a member's browser calls (ADR 0088 decision 7, ADR 0089). Built on the
// capabilities panel's shape. What it keeps honest:
//   - the service's name is the register entry's, served in both languages; the address is the operator's own input;
//   - removing an address asks first, because it takes the service away from every member at once;
//   - a row re-renders from the server's answer, so what is shown is what is stored.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { clearExternalService, listExternalServices, setExternalService, type InstanceExternalService } from './externalServices'
import { capabilityError, inLocale, type CapabilityLocale } from './capabilities'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const services = ref<InstanceExternalService[]>([])
const urls = ref<Record<string, string>>({})
const attributions = ref<Record<string, string>>({})
const error = ref('')
const busy = ref<string | null>(null)

function fill(list: InstanceExternalService[]) {
  services.value = list
  for (const s of list) {
    urls.value[s.id] = s.url ?? ''
    attributions.value[s.id] = s.attribution ?? ''
  }
}

const { pending: loading, error: loadErr } = useAsyncData(async () => fill(await listExternalServices()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))
const title = (s: InstanceExternalService) => inLocale(s.title, locale.value as CapabilityLocale)

function replace(now: InstanceExternalService) {
  fill(services.value.map((s) => (s.id === now.id ? now : s)))
}

async function save(s: InstanceExternalService) {
  error.value = ''
  busy.value = s.id
  try {
    const attribution = s.takesAttribution ? attributions.value[s.id]?.trim() || null : null
    replace(await setExternalService(s.id, (urls.value[s.id] ?? '').trim(), attribution))
  } catch (e) {
    on401(e)
    const { status, code } = await capabilityError(e)
    if (status !== 401) error.value = t(code === 'invalid_request' ? 'services.invalid' : 'services.saveFailed', { title: title(s) })
  } finally {
    busy.value = null
  }
}

async function remove(s: InstanceExternalService) {
  const accepted = await ask({
    header: t('services.removeTitle', { title: title(s) }),
    message: t('services.removeBody'),
    acceptLabel: t('services.remove'),
    acceptIcon: 'pi pi-times',
  })
  if (!accepted) return
  error.value = ''
  busy.value = s.id
  try {
    replace(await clearExternalService(s.id))
  } catch (e) {
    on401(e)
    const { status } = await capabilityError(e)
    if (status !== 401) error.value = t('services.saveFailed', { title: title(s) })
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('services.navTitle') }}</h2>
          <p>{{ t('services.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('services.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('capabilities.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('services.loadFailed') }}</Message>

      <template v-else>
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <ul class="service-list">
          <li v-for="s in services" :key="s.id" class="service" :data-id="s.id">
            <div class="service__head">
              <strong>{{ title(s) }}</strong>
              <span class="veyq-badge service__state" :class="s.url ? 'success' : 'warning'">
                {{ s.url ? t('services.configured') : t('services.notConfigured') }}
              </span>
              <code class="service__id">{{ s.id }}</code>
            </div>
            <label :for="`service-url-${s.id}`">{{ t('services.url') }}</label>
            <InputText :id="`service-url-${s.id}`" v-model="urls[s.id]" class="service__url" :disabled="busy !== null" />
            <template v-if="s.takesAttribution">
              <label :for="`service-attribution-${s.id}`">{{ t('services.attribution') }}</label>
              <InputText :id="`service-attribution-${s.id}`" v-model="attributions[s.id]" class="service__attribution" :disabled="busy !== null" />
            </template>
            <div class="service__actions">
              <Button type="button" class="service__save" :label="t('services.save')" icon="pi pi-check" size="small" :loading="busy === s.id" :disabled="busy !== null" @click="save(s)" />
              <Button
                v-if="s.url"
                type="button"
                class="service__remove"
                :label="t('services.remove')"
                icon="pi pi-times"
                severity="secondary"
                outlined
                size="small"
                :disabled="busy !== null"
                @click="remove(s)"
              />
            </div>
          </li>
        </ul>
      </template>
    </section>
  </section>
</template>

<style scoped>
.service-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 1.25rem;
}
.service {
  display: grid;
  gap: 0.35rem;
}
.service__head {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.5rem;
  align-items: baseline;
}
.service__id {
  flex-basis: 100%;
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 0.8rem;
  color: var(--app-muted);
}
.service__actions {
  display: flex;
  gap: 0.5rem;
}
</style>
