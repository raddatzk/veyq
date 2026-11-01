// @vitest-environment happy-dom
// The layer's tags input (ADR 0107): chips in the field, suggestions while typing. Driven by the key events Reka's
// TagsInput and Combobox listen to (keydown on the text field), never by calling into the component: the caret walks
// into the chips, Backspace at the text's start removes the chip before it in ONE press, Enter picks the highlighted
// suggestion, and an option already a chip is refused.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { computed, defineComponent, h, ref } from 'vue'
import VeyqTagsInput, { type VeyqTag, type VeyqTagOption } from './VeyqTagsInput.vue'
import { realI18n, t } from '../../test/i18n'

type Option = VeyqTagOption & { name: string }
const POOL: Option[] = [
  { key: 'member:cleo', label: 'Cleo', name: 'Cleo' },
  { key: 'member:dora', label: 'Dora', name: 'Dora' },
]

/** A caller as the Pot's create dialog is one: it owns the chips and decides what the typed text suggests — here every
 *  pool entry holding the text and, last, the text as a name, even one that is a chip already (the layer refuses it). */
const Caller = defineComponent({
  props: { onSubmit: { type: Function, default: () => {} } },
  setup(props) {
    const tags = ref<VeyqTag[]>([{ key: 'name:anna', label: 'Anna' }, { key: 'name:ben', label: 'Ben', removeLabel: 'Take Ben out' }])
    const query = ref('')
    const options = computed<Option[]>(() => {
      const typed = query.value.trim()
      const members = POOL.filter((o) => !tags.value.some((x) => x.key === o.key) && o.label.toLowerCase().includes(typed.toLowerCase()))
      return typed ? [...members, { key: `name:${typed.toLowerCase()}`, label: `Add ${typed}`, name: typed }] : members
    })
    return () =>
      h('form', { onSubmit: (e: Event) => (e.preventDefault(), props.onSubmit()) }, [
        h(VeyqTagsInput<VeyqTag, Option>, {
          tags: tags.value,
          options: options.value,
          query: query.value,
          'onUpdate:query': (q: string) => (query.value = q),
          'aria-label': 'People',
          onAdd: (o: Option) => tags.value.push({ key: o.key, label: o.name }),
          onRemove: (x: VeyqTag) => (tags.value = tags.value.filter((y) => y.key !== x.key)),
        }),
      ])
  },
})

const field = () => document.body.querySelector<HTMLInputElement>('input.veyq-tags-input__input')!
const chips = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__tag')].map((c) => c.querySelector('.veyq-tags-input__label')!.textContent!.trim())
const active = () => document.body.querySelector('.veyq-tags-input__tag[data-state="active"] .veyq-tags-input__label')?.textContent?.trim() ?? null
const options = () => [...document.body.querySelectorAll<HTMLElement>('.veyq-tags-input__option')]
const highlighted = () => document.body.querySelector('.veyq-tags-input__option[data-highlighted]')?.textContent?.trim() ?? null

async function press(key: string) {
  field().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}
async function type(text: string) {
  const el = field()
  el.focus()
  el.value = text
  el.setSelectionRange(text.length, text.length)
  el.dispatchEvent(new InputEvent('input', { data: text.slice(-1), bubbles: true }))
  await flushPromises()
}
async function mountCaller(onSubmit: () => void = () => {}) {
  const w = mount(Caller, { props: { onSubmit }, global: { plugins: [realI18n()] }, attachTo: document.body })
  await flushPromises()
  field().focus()
  return w
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('VeyqTagsInput', () => {
  it('shows each chip with a remove button named for it (the caller\'s label, else core\'s), and a press removes that chip', async () => {
    await mountCaller()
    expect(chips()).toEqual(['Anna', 'Ben'])
    const removes = [...document.body.querySelectorAll<HTMLButtonElement>('.veyq-tags-input__remove')]
    expect(removes.map((b) => b.getAttribute('aria-label'))).toEqual([t('ui.removeTag', { label: 'Anna' }), 'Take Ben out'])
    removes[0]!.click()
    await flushPromises()
    expect(chips()).toEqual(['Ben'])
  })

  it('Backspace at the text\'s start removes the chip before the caret in one press; with text before the caret it edits the text', async () => {
    const first = await mountCaller()
    await press('Backspace')
    expect(chips()).toEqual(['Anna'])
    expect(active()).toBeNull()
    await press('Backspace')
    expect(chips()).toEqual([])
    // control: the caret after typed text — Backspace is the text's, no chip goes
    first.unmount()
    await mountCaller()
    await type('Fi')
    await press('Backspace')
    expect(chips()).toEqual(['Anna', 'Ben'])
  })

  it('← and → walk the caret across the chips and back into the text; Backspace or Delete removes the chip it is on', async () => {
    await mountCaller()
    await press('ArrowLeft')
    expect(active()).toBe('Ben')
    await press('ArrowLeft')
    expect(active()).toBe('Anna')
    await press('ArrowRight')
    expect(active()).toBe('Ben')
    await press('ArrowRight')
    expect(active()).toBeNull()
    expect(chips()).toEqual(['Anna', 'Ben'])
    await press('ArrowLeft')
    await press('ArrowLeft')
    await press('Delete')
    expect(chips()).toEqual(['Ben'])
    await press('ArrowLeft')
    expect(active()).toBe('Ben')
    await press('Backspace')
    expect(chips()).toEqual([])
  })

  it('typing opens the suggestions with the first one highlighted; Enter adds it, clears the text, and submits nothing', async () => {
    let submitted = 0
    await mountCaller(() => submitted++)
    await type('o')
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Cleo', 'Dora', 'Add o'])
    expect(highlighted()).toBe('Cleo')
    await press('ArrowDown')
    expect(highlighted()).toBe('Dora')
    await press('Enter')
    expect(chips()).toEqual(['Anna', 'Ben', 'Dora'])
    expect(field().value).toBe('')
    expect(submitted).toBe(0)
  })

  it('the caller\'s "as a name" entry adds the text as a chip; the text alone never becomes one', async () => {
    await mountCaller()
    await type('Finn')
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Add Finn'])
    await press('Enter')
    expect(chips()).toEqual(['Anna', 'Ben', 'Finn'])
  })

  it('refuses an option whose key is a chip already (control: a new one above is added)', async () => {
    const w = await mountCaller()
    await type('ANNA')
    expect(highlighted()).toBe('Add ANNA')
    await press('Enter')
    expect(chips()).toEqual(['Anna', 'Ben'])
    expect(w.findComponent(VeyqTagsInput).emitted('add')).toBeUndefined()
  })
})
