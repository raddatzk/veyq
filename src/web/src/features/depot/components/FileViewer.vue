<script setup lang="ts">
// The viewer of an opened file (inbox #412): the file's own content, decrypted when the page opens and shown in the page
// or full screen - an image, a text, a PDF drawn page by page onto canvases by pdf.js (core's `shared/pdf/PdfPages`, pdf.js loaded on the first PDF),
// a video or audio through the service worker's media route (ADR 0047), and for anything else (a format the browser cannot
// decode, a PDF whose password was declined, a file over its cap) a plain statement and the download. A password-protected PDF
// asks for its password in the page (a field only pdf.js ever sees; see below) before it is shown. Depot makes no
// thumbnail and no preview blob: this reads the file itself.
//
// **What is rendered, and how** (every rule is a security rule - the bytes are a peer's):
// - the kind is decided by the decrypted BYTES (`view.ts`), never by the claimed type alone;
// - an image is an `<img>` on an object URL typed with the SNIFFED type (SVG included: never inline, never `<object>`/
//   `<iframe>`); a decode failure (`error`) is the "cannot be shown" message, not an exception;
// - a text is interpolated (`textContent`), never `innerHTML`: markup in it stays visible characters;
// - a PDF is never handed to the browser's own viewer (no <iframe>/<object>/<embed>: the policy forbids them): pdf.js draws
//   it onto <canvas> elements with a selectable text layer over each, no annotation layer and no script - a link inside it is not clickable;
// - a video/audio plays from the service worker's `/__media/<handle>` (the page holds the key, the worker none); no worker,
//   no playback;
// - nothing is logged and nothing leaves the client; the object URL and the playback handle are revoked on a new file, on
//   unmount and on a failure.
//
// The same component serves the member and the share guest (inbox #401): it gets a reader and the facts, whoever opened it.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import { formatBytes } from '../../../shared/formatBytes'
import { useFileViewer, type ViewerTarget } from '../useFileViewer'
import PdfPages from '../../../shared/pdf/PdfPages.vue'
import { TEXT_CAP } from '../view'

const props = defineProps<{
  /** What to open; null while the page has not read the file's blob yet. */
  target: ViewerTarget | null
  /** The file's name, for the accessible names and the image's alt text. */
  name: string
  /** Whether the session may download: the fallbacks then offer it. */
  canDownload: boolean
}>()
const emit = defineEmits<{ download: [] }>()
const { t, locale } = useI18n()

const { state, retry, cancel, submitPassword, cancelPassword, imageFailed, mediaFailed } = useFileViewer(() => props.target)
const ready = computed(() => (state.value.status === 'ready' ? state.value.view : null))
const percent = computed(() => (state.value.status === 'loading' ? Math.round(state.value.progress * 100) : 0))
const unsupportedKey = computed(() => {
  const s = state.value
  return s.status === 'unsupported' ? `depot.viewer.unsupported.${s.reason}` : ''
})

// --- plain text: copy and wrap ---------------------------------------------------------------------------------------------
// The text is ordinary selectable page text (nothing here sets user-select, and it is `textContent`, never markup); these two
// buttons only add convenience. Copy writes exactly what is SHOWN (a truncated text copies its shown part, and the button and
// its note say so). Where the clipboard API is not available (an insecure page, a refusal) the text is selected and the
// browser asked to copy the selection; if that is refused too, the note tells the user to press Ctrl/Cmd+C: no copy ever
// fails silently.
const textEl = ref<HTMLElement | null>(null)
const wrap = ref(true)
const copyNote = ref('')
let copyNoteTimer: ReturnType<typeof setTimeout> | undefined

function selectShownText(): void {
  const el = textEl.value
  const sel = typeof window !== 'undefined' ? window.getSelection() : null
  if (el && sel) sel.selectAllChildren(el)
}
const copiedNote = (truncated: boolean) => (truncated ? 'depot.viewer.text.copiedShown' : 'depot.viewer.text.copied')
async function copyText(text: string, truncated: boolean): Promise<void> {
  clearTimeout(copyNoteTimer)
  try {
    await navigator.clipboard.writeText(text) // throws where there is no clipboard (an insecure page) or the browser refuses
    copyNote.value = copiedNote(truncated)
  } catch {
    // The older route for the same thing: select the shown text and ask the browser to copy the selection. Where that is
    // refused too, the text stays selected and the note says to press Ctrl/Cmd+C.
    selectShownText()
    let copied = false
    try {
      copied = document.execCommand('copy')
    } catch {
      copied = false
    }
    copyNote.value = copied ? copiedNote(truncated) : 'depot.viewer.text.copyManual'
  }
  copyNoteTimer = setTimeout(() => (copyNote.value = ''), 6000)
}
watch(
  () => (state.value.status === 'ready' && state.value.view.kind === 'text' ? state.value.view.text : null),
  () => {
    clearTimeout(copyNoteTimer)
    copyNote.value = ''
  },
)
onBeforeUnmount(() => clearTimeout(copyNoteTimer))

// --- the password of a protected PDF --------------------------------------------------------------------------------------
// The field's value goes to pdf.js and nowhere else (`useFileViewer.submitPassword` -> the engine's `onPassword`): it is not
// stored, logged, sent, or kept in the state; it is cleared from this component the moment it is submitted, when the prompt
// closes and on unmount.
const password = ref('')
const passwordInput = ref<{ $el: HTMLInputElement } | null>(null)
const prompting = computed(() => state.value.status === 'password')
const passwordWrong = computed(() => state.value.status === 'password' && state.value.wrong)
const passwordBusy = computed(() => state.value.status === 'password' && state.value.busy)
const passwordFieldId = `file-viewer-password-${useId()}`
const passwordDescribedBy = computed(() => `${passwordFieldId}-hint${passwordWrong.value ? ` ${passwordFieldId}-error` : ''}`)

function onSubmitPassword(): void {
  if (!password.value || passwordBusy.value) return
  const given = password.value
  password.value = ''
  submitPassword(given)
}
watch(prompting, (on) => {
  if (!on) password.value = ''
})
// Focus the field when the prompt opens and again after a wrong answer (it is disabled while the engine tries one).
watch(
  () => prompting.value && !passwordBusy.value,
  (ready) => {
    if (ready) void nextTick(() => passwordInput.value?.$el?.focus())
  },
  { immediate: true, flush: 'post' },
)
onBeforeUnmount(() => {
  password.value = ''
})

// --- full screen -----------------------------------------------------------------------------------------------------
// The Fullscreen API on the viewer's own container, with Escape (the browser's). Where it is missing or refused (an
// iPhone's Safari has none for an element) a fixed-position overlay stands in, closed by Escape or the same button.

const root = ref<HTMLElement | null>(null)
const full = ref(false)
let native = false

function onFullscreenChange(): void {
  if (native && document.fullscreenElement !== root.value) {
    native = false
    full.value = false
  }
}
function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && full.value && !native) full.value = false
}
async function toggleFullscreen(): Promise<void> {
  if (full.value) {
    if (native && document.fullscreenElement) await document.exitFullscreen().catch(() => {})
    native = false
    full.value = false
    return
  }
  const el = root.value
  if (el && typeof el.requestFullscreen === 'function' && document.fullscreenEnabled !== false) {
    try {
      await el.requestFullscreen()
      native = true
      full.value = true
      return
    } catch {
      // refused (a policy, no activation): the overlay below
    }
  }
  full.value = true
}
onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.removeEventListener('keydown', onKeydown)
  if (native && document.fullscreenElement) void document.exitFullscreen().catch(() => {})
})
</script>

<template>
  <div v-if="state.status !== 'idle'" ref="root" class="file-viewer" :class="{ 'file-viewer-full': full }" role="region" :aria-label="t('depot.viewer.label', { name })">
    <div class="file-viewer-stage" :aria-busy="state.status === 'loading'">
      <div v-if="state.status === 'loading'" class="file-viewer-status" role="status">
        <VeyqIcon name="spinner" spin />
        <span>{{ t('depot.viewer.loading') }}<template v-if="percent > 0"> {{ percent }} %</template></span>
        <VeyqButton :label="t('depot.viewer.stop')" size="small" variant="secondary" appearance="text" @click="cancel" />
      </div>

      <img
        v-else-if="ready?.kind === 'image'"
        class="file-viewer-image"
        :src="ready.url"
        :alt="name"
        @error="imageFailed"
      />
      <template v-else-if="ready?.kind === 'text'">
        <div class="file-viewer-text-tools" role="toolbar" :aria-label="t('depot.viewer.text.toolbar')">
          <VeyqButton
            class="file-viewer-text-copy"
            icon="copy"
            :label="t('depot.viewer.text.copy')"
            :aria-label="ready.truncated ? t('depot.viewer.text.copyShown') : t('depot.viewer.text.copy')"
            size="small"
            variant="secondary"
            appearance="outlined"
            @click="copyText(ready.text, ready.truncated)"
          />
          <VeyqButton
            class="file-viewer-text-wrap"
            icon="align-left"
            :label="t('depot.viewer.text.wrap')"
            :aria-pressed="wrap"
            size="small"
            variant="secondary"
            :appearance="wrap ? 'filled' : 'outlined'"
            @click="wrap = !wrap"
          />
          <span v-if="copyNote" class="file-viewer-text-note" role="status">{{ t(copyNote) }}</span>
        </div>
        <pre
          ref="textEl"
          class="file-viewer-text"
          :class="{ 'file-viewer-text-nowrap': !wrap }"
          tabindex="0"
          :aria-label="t('depot.viewer.textLabel', { name })"
        >{{ ready.text }}</pre>
      </template>
      <PdfPages v-else-if="ready?.kind === 'pdf'" :doc="ready.doc" :name="name" />
      <video
        v-else-if="ready?.kind === 'stream' && ready.element === 'video'"
        class="file-viewer-video"
        :src="ready.src"
        :aria-label="name"
        controls
        playsinline
        preload="metadata"
        @error="mediaFailed"
      />
      <audio
        v-else-if="ready?.kind === 'stream'"
        class="file-viewer-audio"
        :src="ready.src"
        :aria-label="name"
        controls
        preload="metadata"
        @error="mediaFailed"
      />

      <form
        v-else-if="state.status === 'password'"
        class="file-viewer-password"
        :aria-label="t('depot.viewer.pdf.passwordTitle', { name })"
        @submit.prevent="onSubmitPassword"
        @keydown.esc.stop.prevent="cancelPassword"
      >
        <p class="file-viewer-password-title">{{ t('depot.viewer.pdf.passwordTitle', { name }) }}</p>
        <label class="file-viewer-password-label" :for="passwordFieldId">{{ t('depot.viewer.pdf.passwordLabel') }}</label>
        <VeyqInput
          :id="passwordFieldId"
          ref="passwordInput"
          v-model="password"
          class="file-viewer-password-input"
          type="password"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          :disabled="passwordBusy"
          :invalid="passwordWrong"
          :aria-invalid="passwordWrong"
          :aria-describedby="passwordDescribedBy"
        />
        <p v-if="passwordWrong" :id="passwordFieldId + '-error'" class="file-viewer-password-error" role="alert">{{ t('depot.viewer.pdf.passwordWrong') }}</p>
        <p :id="passwordFieldId + '-hint'" class="file-viewer-hint">{{ passwordBusy ? t('depot.viewer.pdf.passwordChecking') : t('depot.viewer.pdf.passwordHint') }}</p>
        <span class="file-viewer-password-actions">
          <VeyqButton type="submit" class="file-viewer-password-open" :label="t('depot.viewer.pdf.passwordOpen')" size="small" :disabled="!password || passwordBusy" />
          <VeyqButton class="file-viewer-password-cancel" :label="t('depot.viewer.pdf.passwordCancel')" size="small" variant="secondary" appearance="outlined" :disabled="passwordBusy" @click="cancelPassword" />
        </span>
      </form>
      <VeyqMessage v-else-if="state.status === 'error'" tone="error" class="file-viewer-message">
        <span class="file-viewer-note">
          <span>{{ t('depot.viewer.error') }}</span>
          <VeyqButton :label="t('depot.viewer.retry')" size="small" variant="secondary" appearance="outlined" @click="retry" />
        </span>
      </VeyqMessage>
      <VeyqMessage v-else-if="state.status === 'cancelled'" tone="info" class="file-viewer-message">
        <span class="file-viewer-note">
          <span>{{ t('depot.viewer.cancelled') }}</span>
          <VeyqButton :label="t('depot.viewer.retry')" size="small" variant="secondary" appearance="outlined" @click="retry" />
        </span>
      </VeyqMessage>
      <VeyqMessage v-else-if="state.status === 'too-large' || state.status === 'unsupported'" tone="info" class="file-viewer-message">
        <span class="file-viewer-note">
          <span v-if="state.status === 'too-large'">{{ t('depot.viewer.tooLarge', { limit: formatBytes(state.limit, locale) }) }}</span>
          <span v-else>{{ t(unsupportedKey) }}</span>
          <VeyqButton
            v-if="canDownload"
            class="file-viewer-download"
            icon="download"
            :label="t('depot.detail.download')"
            :aria-label="t('depot.download', { name })"
            size="small"
            variant="secondary"
            appearance="outlined"
            @click="emit('download')"
          />
        </span>
      </VeyqMessage>

      <p v-if="ready?.kind === 'text' && ready.truncated" class="file-viewer-hint" role="note">
        {{ t('depot.viewer.textTruncated', { shown: formatBytes(TEXT_CAP, locale) }) }}
      </p>
    </div>

    <VeyqButton
      v-if="ready"
      class="file-viewer-fullscreen"
      :icon="full ? 'window-minimize' : 'window-maximize'"
      :label="full ? t('depot.viewer.exitFullscreen') : t('depot.viewer.fullscreen')"
      :aria-label="full ? t('depot.viewer.exitFullscreen') : t('depot.viewer.fullscreen')"
      :aria-pressed="full"
      size="small"
      variant="secondary"
      appearance="outlined"
      @click="toggleFullscreen"
    />
  </div>
</template>

<style scoped>
.file-viewer {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem;
  /* A grid item of the file page's `.veyq-section` (one implicit auto column): without this its minimum is its content's
     width, so a PDF page or an unwrapped text line widened the section's column instead of scrolling inside the viewer
     (F12: the PDF fit width then fed itself, 14 px per frame). */
  min-width: 0;
  margin: 0 0 1rem;
}
.file-viewer-stage {
  width: 100%;
  min-width: 0;
}
.file-viewer-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--app-muted);
}
.file-viewer-image,
.file-viewer-video {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  margin: 0 auto;
  object-fit: contain;
}
.file-viewer-video {
  width: 100%;
  background: var(--app-backdrop-strong);
}
.file-viewer-audio {
  display: block;
  width: 100%;
}
.file-viewer-text {
  margin: 0;
  max-height: 70vh;
  overflow: auto;
  padding: 0.75rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.875rem;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  tab-size: 4;
}
.file-viewer-text-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin: 0 0 0.5rem;
}
.file-viewer-text-note {
  color: var(--app-muted);
}
.file-viewer-text.file-viewer-text-nowrap {
  white-space: pre;
  overflow-wrap: normal;
}
.file-viewer-hint {
  margin: 0.5rem 0 0;
  color: var(--app-muted);
}
.file-viewer-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
}
.file-viewer-password {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem;
  max-width: 24rem;
}
.file-viewer-password-title {
  margin: 0;
  font-weight: 600;
}
.file-viewer-password-input {
  width: 100%;
}
.file-viewer-password-error {
  margin: 0;
  color: var(--app-danger);
}
.file-viewer-password-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
/* The overlay stands in where the Fullscreen API is missing; the native :fullscreen state gets the same fill. */
.file-viewer-full,
.file-viewer:fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1000;
  margin: 0;
  padding: 1rem;
  overflow: auto;
  background: var(--app-bg);
}
.file-viewer-full .file-viewer-stage,
.file-viewer:fullscreen .file-viewer-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.file-viewer-full .file-viewer-image,
.file-viewer-full .file-viewer-video,
.file-viewer:fullscreen .file-viewer-image,
.file-viewer:fullscreen .file-viewer-video,
.file-viewer-full .file-viewer-text,
.file-viewer:fullscreen .file-viewer-text {
  max-height: calc(100vh - 6rem);
}
</style>
