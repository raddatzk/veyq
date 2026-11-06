<script setup lang="ts">
// The bookmark's `createDialog` (uc.sharing.keep-a-received-link, ADR 0120 decision 4): a link someone sent, its password
// where it has one, and a name. The link is checked as it is typed (`bookmarkLink.ts`: a share link with its key, `https`
// or a development host) and never fetched; until the member names it, the name follows the link's host. What it says
// before the member saves: whoever can open this place in the space can open the link (the consequence of decision 1 in the
// ADR's Consequences). A name that collides with a sibling is refused before sending (core's `siblingCollision.ts`).
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqPassword from '../../shared/ui/VeyqPassword.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import type { NodeCreateDialogEmits, NodeCreateDialogProps } from '../applications/registry'
import { memberCreateOptions } from '../node/rereadNode'
import { treeStore } from '../node/treeStore'
import { collidingSibling, nameTakenMessage } from '../node/siblingCollision'
import { SHARE_BOOKMARK_KIND } from '../node/kind'
import { httpStatus, HttpStatus } from '../../shared/httpError'
import { hostOf, parseShareLink } from './bookmarkLink'
import { MAX_PASSWORD_LENGTH } from './bookmarkMeta'
import { createBookmark } from './createBookmark'

const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t } = useI18n()

const linkInput = ref('')
const password = ref('')
const name = ref('')
/** The member typed a name of their own: the host no longer fills it. */
const named = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const linkId = useId()
const passwordId = useId()

const parsed = computed(() => parseShareLink(linkInput.value))
/** What is wrong with the link, shown once something was typed. */
const linkProblem = computed(() => (!parsed.value.ok && parsed.value.problem !== 'empty' ? t(`node.bookmark.problem.${parsed.value.problem}`) : null))
/** The host's name, numbered past a sibling that already holds it ("… (2)"), so a second link of one host does not
 *  collide only when it is saved. */
const suggestedName = computed(() => {
  if (!parsed.value.ok) return ''
  const base = t('node.bookmark.defaultName', { host: hostOf(parsed.value.value) })
  const taken = (name: string) => !!collidingSibling(treeStore.nodes, props.parentId ?? null, SHARE_BOOKMARK_KIND, name)
  for (let n = 1; n < 100; n++) {
    const name = n === 1 ? base : `${base} (${n})`
    if (!taken(name)) return name
  }
  return base
})
const shownName = computed({
  get: () => (named.value ? name.value : suggestedName.value),
  set: (v: string) => {
    named.value = true
    name.value = v
  },
})
const canSubmit = computed(() => !loading.value && parsed.value.ok && shownName.value.trim().length > 0 && password.value.length <= MAX_PASSWORD_LENGTH)

async function submit() {
  if (!canSubmit.value || !parsed.value.ok) return
  const link = parsed.value.value
  const finalName = shownName.value.trim()
  error.value = null
  const sibling = collidingSibling(treeStore.nodes, props.parentId ?? null, SHARE_BOOKMARK_KIND, finalName)
  if (sibling) {
    error.value = nameTakenMessage(t, sibling)
    return
  }
  loading.value = true
  try {
    const parent = props.parentId && props.parentKey && props.parentEpoch != null && props.parentScopeEpoch != null
      ? { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch, scopeEpoch: props.parentScopeEpoch }
      : null
    await createBookmark(props.scopeId, props.epochs, parent, finalName, link, password.value || null, memberCreateOptions(props.scopeId, parent))
    emit('done')
    emit('close')
  } catch (e) {
    error.value = httpStatus(e) === HttpStatus.conflict ? t('node.slugTaken') : t('node.bookmark.createFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <VeyqDialog :visible="true" :header="t('node.bookmark.createTitle')" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('node.bookmark.createIntro') }}</p>
      <div class="veyq-form-field">
        <label :for="linkId">{{ t('node.bookmark.linkLabel') }}</label>
        <VeyqInput
          :id="linkId"
          v-model="linkInput"
          data-testid="bookmark-link"
          autocomplete="off"
          spellcheck="false"
          required
          autofocus
          :invalid="!!linkProblem"
          :disabled="loading"
        />
        <span v-if="linkProblem" class="veyq-form-error" data-testid="bookmark-link-problem">{{ linkProblem }}</span>
      </div>
      <div class="veyq-form-field">
        <label :for="passwordId">{{ t('node.bookmark.passwordLabel') }}</label>
        <VeyqPassword :input-id="passwordId" v-model="password" autocomplete="off" :disabled="loading" />
        <span class="veyq-form-copy">{{ t('node.bookmark.passwordHint') }}</span>
      </div>
      <label>
        {{ t('node.nameLabel') }}
        <VeyqInput v-model="shownName" data-testid="bookmark-name" autocomplete="off" required :disabled="loading" />
      </label>
      <p class="veyq-form-copy" data-testid="bookmark-who-can-open">{{ t('node.bookmark.whoCanOpen') }}</p>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('node.bookmark.save')" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>
