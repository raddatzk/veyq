<script setup lang="ts">
// The one control for entering a Secret Key (uc.account.unlock § One Secret-Key control). Five screens ask for
// one — sign-in, recovery, change-password, key rotation, and a tenant-invite acceptance by someone who already
// has an account — and before this component the import affordance existed as copy-pasted markup on exactly
// one of them, while three use-cases asserted it on all of them. A bare input is indistinguishable from an
// unbuilt one, which is how that drift survived.
//
// Three ways in, all decoded ON-DEVICE and none of them a network call: upload the kit (PDF or a QR
// screenshot), scan the QR with the camera, or paste the kit token in place of the key. The paste path is why
// the model is normalised on input rather than at submit: every host used to owe itself a `parseKit` at read
// time and only one of them paid it.
//
// **Decoder-agnostic where it must be.** `accepts` stays a caller-supplied predicate and a decoded string this
// component does not recognise is handed back via `scanned` — ADR 0059 §1: one scan entry serves both of this
// app's QR payloads and recognising a payload is the caller's job. Without that pass-through the sign-in
// screen's pairing dispatch would break.
//
// `expectedEmail` is optional on purpose. Where the screen knows which account it is for, a kit carrying a
// different address WARNS — it never blocks and never clears the key. The e-mail in a kit is metadata, an
// account may have changed address, and only the wrap can judge a key; the alternative to warning here is the
// generic AEAD failure telling the person their password might be wrong when they picked up the wrong kit.
//
// A kit made at another address than this page's says where it was made, for the same reason and as quietly: one
// account may be reached under several addresses, so the kit's server (en.account.key-model § The Emergency Kit) is a
// hint, never a check.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqInput from './VeyqInput.vue'
import VeyqMessage from './VeyqMessage.vue'
import VeyqIcon from './VeyqIcon.vue'
import { parseKit, type KitData } from '@veyq/crypto/emergencyKit'
import { readSecretKey } from '@veyq/crypto/secretKey'
import { importKitFromFile } from '../../features/account/emergencyKit/emergencyKit'
import KitScanner from '../../features/account/emergencyKit/KitScanner.vue'

// Multi-root (the field, its three messages, the hidden file input, the teleported scanner) — there is no single
// element an inherited class could land on, and the hosts style the <label> around it instead.
//
// **Trap for host tests:** this adds a hidden `<input type="file">` to every form that uses it, so a host test
// selecting its Secret-Key field by shape — `findAll('input')[n]`, `input:not([type="password"])`, a count of
// the inputs on the step — silently picks up the picker instead. Three host tests broke that way when the
// control landed. Name the field: `input[name="secret-key"]`, which is why `name` is a prop with that default.
defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    inputId?: string
    /** Kept a prop because `input[name="secret-key"]` is how screens and their tests identify this field. */
    name?: string
    required?: boolean
    disabled?: boolean
    /** The account this screen is for, when it knows — enables the wrong-kit warning. */
    expectedEmail?: string
    /** Which decoded strings the camera should stop on. Defaults to "an Emergency Kit". */
    accepts?: (text: string) => boolean
  }>(),
  { name: 'secret-key' },
)

const model = defineModel<string>({ required: true })
const emit = defineEmits<{
  /** A kit was imported, scanned or pasted — the host may use its e-mail (the key is already in the model). */
  kit: [KitData]
  /** A decoded string `accepts` allowed but that is not a kit — the host's own payload. */
  scanned: [string]
}>()

const { t } = useI18n()

const fileInput = ref<HTMLInputElement | null>(null)
const showSecret = ref(false)
const scanOpen = ref(false)
const unreadable = ref(false)
const mismatchedEmail = ref('')
/** The host a kit was made at, where that is not this page's; empty for a kit with no server or this one. */
const otherServer = ref('')
const here = typeof location !== 'undefined' ? location.host : ''

// A key of the full length whose check symbols disagree was misread or mistyped, and saying so here beats the generic
// "password or Secret Key didn't match" after a slow derivation. Anything shorter, or outside the alphabet, says
// nothing while it is being typed; the host's submit refuses it as it refuses any key that does not open the account.
const typo = computed(() => {
  const read = readSecretKey(model.value ?? '')
  if (read.ok) read.key.fill(0)
  return !read.ok && read.problem === 'typo'
})

// Offered only where a camera can exist at all; a camera-less device falls back to upload or paste.
const scanSupported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
const iconCount = computed(() => (scanSupported ? 3 : 2))

function acceptsScan(text: string): boolean {
  return props.accepts ? props.accepts(text) : parseKit(text) !== null
}

/** Take a kit: fill the key, hand the host the rest, and say so if it is for another account. */
function applyKit(kit: KitData) {
  model.value = kit.secretKey
  unreadable.value = false
  const expected = props.expectedEmail?.trim().toLowerCase()
  // Warn, never block: the key stays, the host's submit stays enabled.
  mismatchedEmail.value = expected && kit.email.trim().toLowerCase() !== expected ? kit.email : ''
  otherServer.value = kit.server !== undefined && kit.server !== location.origin ? new URL(kit.server).host : ''
  emit('kit', kit)
}

function triggerUpload() {
  unreadable.value = false
  fileInput.value?.click()
}

async function onUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // allow re-picking the same file
  if (!file) return
  const kit = await importKitFromFile(file)
  // Generic failure — never say WHY a kit could not be read.
  if (kit) applyKit(kit)
  else unreadable.value = true
}

function onScanned(text: string) {
  const kit = parseKit(text)
  // Dispatch on the prefix, not on which of the two happens to parse: reading the kit first keeps this path
  // byte-identical to what the sign-in screen did before the extraction.
  if (kit) applyKit(kit)
  else emit('scanned', text)
  scanOpen.value = false
}

// The paste path. A kit token pasted in place of the key is replaced by the key it carries, so every host reads
// a bare Secret Key and none of them owes itself a decode at submit time. Terminates after one pass: a bare key
// does not parse as a kit.
watch(model, (value) => {
  const kit = value ? parseKit(value.trim()) : null
  if (kit) applyKit(kit)
})
</script>

<template>
  <div class="secret-field" :style="{ '--secret-icons': iconCount }">
    <VeyqInput
      v-model="model"
      :id="inputId"
      :type="showSecret ? 'text' : 'password'"
      :name="name"
      autocomplete="off"
      :spellcheck="false"
      :required="required"
      :disabled="disabled"
      fluid
    />
    <span class="secret-icons">
      <button
        type="button"
        class="veyq-field-action"
        :aria-label="t('unlock.upload')"
        :title="t('unlock.upload')"
        :disabled="disabled"
        @click="triggerUpload"
      >
        <VeyqIcon name="upload" />
      </button>
      <button
        v-if="scanSupported"
        type="button"
        class="veyq-field-action"
        :aria-label="t('unlock.scan')"
        :title="t('unlock.scan')"
        :disabled="disabled"
        @click="scanOpen = true"
      >
        <VeyqIcon name="qrcode" />
      </button>
      <button
        type="button"
        class="veyq-field-action"
        :aria-label="t(showSecret ? 'common.hide' : 'common.show')"
        :title="t(showSecret ? 'common.hide' : 'common.show')"
        :disabled="disabled"
        @click="showSecret = !showSecret"
      >
        <VeyqIcon :name="showSecret ? 'eye-slash' : 'eye'" />
      </button>
    </span>
  </div>
  <VeyqMessage v-if="typo" tone="error">{{ t('unlock.secretKeyTypo') }}</VeyqMessage>
  <VeyqMessage v-if="unreadable" tone="error">{{ t('unlock.kitUnreadable') }}</VeyqMessage>
  <VeyqMessage v-if="mismatchedEmail" tone="warn">
    {{ t('unlock.kitEmailMismatch', { kitEmail: mismatchedEmail, expected: expectedEmail }) }}
  </VeyqMessage>
  <VeyqMessage v-if="otherServer" tone="info">{{ t('unlock.kitOtherServer', { server: otherServer, here }) }}</VeyqMessage>
  <input ref="fileInput" type="file" accept="application/pdf,image/*" class="visually-hidden" @change="onUpload" />
  <!-- Teleported: the overlay owns the whole screen, and every host renders this control inside a <label>,
       where a click anywhere in the overlay would also re-focus the input behind it. -->
  <Teleport to="body">
    <KitScanner v-if="scanOpen" :accepts="acceptsScan" @found="onScanned" @close="scanOpen = false" />
  </Teleport>
</template>
