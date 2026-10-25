<script setup lang="ts">
// One opened receipt (uc.expenses.attach-receipts step 3, ADR 0108 decision 7): its bytes decrypted in the browser and shown
// in the page — for a member, a guest and a moderator alike (it gets a `ReceiptSource`, whoever built it). Every rule is a
// security rule, as in Depot's viewer (the bytes are a peer's):
// - what it is, is decided by the decrypted BYTES (`receiptView`), never by the sealed type or name;
// - a raster image is an `<img>` on an object URL typed with the SNIFFED type; one the browser cannot decode (HEIC off
//   Safari) falls back to the download;
// - a PDF is drawn by core's page renderer (`shared/pdf/PdfPages.vue`, pdf.js onto canvases: no annotation layer, no
//   script, never the browser's viewer); a protected or unreadable one is not prompted for here — it is offered for download;
// - anything else (an SVG, a text, a file whose claim was wrong) is never rendered: a statement and the download;
// - the object URL, the PDF document and a read in flight are undone on a new receipt and on unmount.
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import PdfPages from '../../../shared/pdf/PdfPages.vue'
import { openPdf, PdfOpenError, type PdfDocument } from '../../../shared/pdf/pdfEngine'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { receiptView } from '../domain/receiptRules'
import { isBlockedRead, type ReceiptSource } from '../receipts'

const props = defineProps<{ source: ReceiptSource }>()
const emit = defineEmits<{ download: [] }>()
const { t } = useI18n()

type State =
  | { status: 'loading' }
  | { status: 'image'; url: string }
  | { status: 'pdf'; doc: PdfDocument }
  | { status: 'other' }
  | { status: 'blocked' }
  | { status: 'error' }

const state = shallowRef<State>({ status: 'loading' })
const image = useObjectUrl()
let controller: AbortController | null = null
let pdf: PdfDocument | null = null
let run = 0

function release(): void {
  controller?.abort()
  controller = null
  void pdf?.destroy()
  pdf = null
  image.clear()
}

async function open(): Promise<void> {
  release()
  const mine = ++run
  if (props.source.blocked) {
    state.value = { status: 'blocked' }
    return
  }
  const ctl = new AbortController()
  controller = ctl
  state.value = { status: 'loading' }
  try {
    const bytes = await props.source.read(ctl.signal)
    if (mine !== run) return
    const view = receiptView(bytes)
    if (view.kind === 'image') {
      image.set(new Blob([bytes as BlobPart], { type: view.type }))
      state.value = { status: 'image', url: image.url.value! }
    } else if (view.kind === 'pdf') {
      try {
        // No password prompt: a protected receipt is offered for download (the handler declines at once).
        const doc = await openPdf(bytes, ctl.signal, async () => null)
        if (mine !== run) {
          void doc.destroy()
          return
        }
        pdf = doc
        state.value = { status: 'pdf', doc }
      } catch (e) {
        if (!(e instanceof PdfOpenError)) throw e
        if (mine === run) state.value = { status: 'other' }
      }
    } else {
      state.value = { status: 'other' }
    }
  } catch (e) {
    if (mine !== run || ctl.signal.aborted) return
    state.value = isBlockedRead(e) ? { status: 'blocked' } : { status: 'error' }
  }
}

function imageFailed(): void {
  image.clear()
  state.value = { status: 'other' }
}

watch(() => props.source.id, () => void open(), { immediate: true })
onBeforeUnmount(() => {
  run++
  release()
})
</script>

<template>
  <div class="expenses-receipt-view" :aria-label="source.name" role="region">
    <p v-if="state.status === 'loading'" class="veyq-loading">{{ t('expenses.receipts.opening') }}</p>
    <img v-else-if="state.status === 'image'" class="expenses-receipt-image" :src="state.url" :alt="source.name" @error="imageFailed" />
    <PdfPages v-else-if="state.status === 'pdf'" :doc="state.doc" :name="source.name" />
    <VeyqMessage v-else-if="state.status === 'blocked'" tone="warn">{{ t('node.blockedByOperator') }}</VeyqMessage>
    <VeyqMessage v-else-if="state.status === 'other'" tone="info">
      {{ t('expenses.receipts.notShown') }}
      <VeyqButton :label="t('expenses.receipts.download')" icon="download" variant="secondary" appearance="text" size="small" @click="emit('download')" />
    </VeyqMessage>
    <VeyqMessage v-else tone="error">
      {{ t('expenses.receipts.openFailed') }}
      <VeyqButton :label="t('expenses.receipts.retry')" icon="refresh" variant="secondary" appearance="text" size="small" @click="open" />
    </VeyqMessage>
  </div>
</template>

<style scoped>
.expenses-receipt-view {
  display: grid;
  gap: 0.5rem;
  min-width: 0;
}
.expenses-receipt-image {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  margin: 0 auto;
  object-fit: contain;
  border-radius: var(--app-radius-sm);
}
</style>
