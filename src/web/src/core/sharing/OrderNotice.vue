<script setup lang="ts">
// A guest uploader's notice of an authority's removal order (TCO Art. 11, ADR 0093 § 12): on the share page and the
// drop page, wherever the link or one of the guest's own items answers blocked — asked only when this device holds the
// guest's session for the link (`loadOrderNotice`), and silent whenever the server has nothing to tell it. It says when
// the order was received and where to ask for the reasons or a copy of the order (Art. 11(2)); never more. A draft until
// a qualified legal review (ADR 0093 § Legal basis).
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { loadOrderNotice, type OrderNotice } from './orderNotice'

const props = defineProps<{ shareId: string }>()

const { t, locale } = useI18n()
const notice = ref<OrderNotice | null>(null)

const day = (d: Date) => d.toLocaleDateString(locale.value, { year: 'numeric', month: 'long', day: 'numeric' })

async function load() {
  try {
    notice.value = await loadOrderNotice(props.shareId)
  } catch {
    notice.value = null // a failed read tells the guest nothing rather than something wrong
  }
}

onMounted(load)
</script>

<template>
  <VeyqMessage v-if="notice" tone="info" class="order-notice" data-testid="order-notice">
    <p v-for="(at, i) in notice.receivedAt" :key="i" class="order-notice-removal">
      {{ t('share.orderNotice.removed', { date: day(at) }) }}
    </p>
    <p class="order-notice-ask">
      {{ notice.contact ? t('share.orderNotice.ask', { contact: notice.contact }) : t('share.orderNotice.askOperator') }}
    </p>
  </VeyqMessage>
</template>

<style scoped>
.order-notice p {
  margin: 0;
}
.order-notice p + p {
  margin-top: 0.35rem;
}
</style>
