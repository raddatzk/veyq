<script setup lang="ts">
// A multi-line text field (ADR 0107), our markup: a plain `<textarea>` styled as the layer's text field. `autoResize`
// grows it with its text instead of scrolling inside it, from the height its `rows` give. Every native attribute
// (`rows`, `readonly`, `placeholder`, …) falls through; a read-only one is bound with `:model-value` alone. Sheets reach
// it by `veyq-textarea` (styled in `layer.css`).
import { nextTick, onMounted, ref, watch } from 'vue'

const model = defineModel<string>()
const props = defineProps<{ autoResize?: boolean }>()

const field = ref<HTMLTextAreaElement | null>(null)
function fit() {
  const el = field.value
  if (!props.autoResize || !el) return
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}
onMounted(fit)
watch(model, () => void nextTick(fit))
</script>

<template>
  <textarea ref="field" v-model="model" :class="['veyq-input', 'veyq-textarea', { 'veyq-textarea--auto': autoResize }]" @input="fit" />
</template>
