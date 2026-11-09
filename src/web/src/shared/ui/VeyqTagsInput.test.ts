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

  it('without result groups the highlight is Reka\'s alone: ↓ moves it, and new text puts it back on the first suggestion', async () => {
    await mountCaller()
    await type('o')
    await press('ArrowDown')
    expect(highlighted()).toBe('Dora')
    await type('O') // the same suggestions under a changed text
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Cleo', 'Dora', 'Add O'])
    expect(highlighted()).toBe('Cleo')
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

// With results under the suggestions (the shell's search, uc.platform.search step 4): the first entry of the list is
// highlighted, a suggestion or a result, and Enter takes it; an entry the member moved the highlight to (keys or pointer)
// stays highlighted while they type on, as long as it is listed. Reka alone puts the highlight back on the first entry
// whenever the text changes (the picker test above is that control). The list has two areas, the suggestions under
// their heading and the results after them with none (cr-search-chips-for-everything).
describe('VeyqTagsInput with results', () => {
  const PEOPLE = ['Ken', 'Kevin']
  const RESULTS = [{ key: 'r:kenia', label: 'Kenia' }, { key: 'r:kevin', label: 'Kevins Geburtstag' }]
  /** The shell's search in small: the people whose name starts with the text as chip suggestions (a chip stays offered -
   *  the layer refuses it - so a remembered entry could outlive the chip), the results holding the text below them. */
  function mountSearch() {
    const added: string[] = []
    const chosen: string[] = []
    const Search = defineComponent({
      setup() {
        const query = ref('')
        const open = ref(false)
        const tags = ref<VeyqTag[]>([])
        const typed = () => query.value.trim().toLowerCase()
        return () =>
          h(VeyqTagsInput<VeyqTag, VeyqTagOption>, {
            tags: tags.value,
            options: typed() ? PEOPLE.filter((n) => n.toLowerCase().startsWith(typed())).map((n) => ({ key: `p:${n}`, label: n })) : [],
            results: typed() ? RESULTS.filter((r) => r.label.toLowerCase().includes(typed())) : [],
            resultsLabel: 'Results',
            optionsLabel: 'Filter by',
            query: query.value,
            'onUpdate:query': (q: string) => (query.value = q),
            open: open.value,
            'onUpdate:open': (o: boolean) => (open.value = o),
            'aria-label': 'Search',
            onAdd: (o: VeyqTagOption) => (added.push(o.key), tags.value.push({ key: o.key, label: o.label })),
            onChoose: (o: VeyqTagOption) => chosen.push(o.key),
          })
      },
    })
    mount(Search, { global: { plugins: [realI18n()] }, attachTo: document.body })
    return { added, chosen }
  }

  // both areas headed ('Filter by', 'Results') again (owner 2026-11-09: the results are always headed), each heading naming its group
  it('heads the suggestions and the results, each heading naming its group, and highlights the first entry, a suggestion', async () => {
    mountSearch()
    await flushPromises()
    await type('k')
    expect([...document.body.querySelectorAll('.veyq-tags-input__group-label')].map((x) => x.textContent?.trim())).toEqual(['Filter by', 'Results'])
    const results = document.body.querySelector('.veyq-tags-input__results')!
    expect(results.getAttribute('role')).toBe('group')
    expect(document.getElementById(results.getAttribute('aria-labelledby') ?? '')?.textContent?.trim()).toBe('Results')
    expect(results.querySelectorAll('.veyq-tags-input__option')).toHaveLength(2)
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Ken', 'Kevin', 'Kenia', 'Kevins Geburtstag'])
    expect(highlighted()).toBe('Ken')
  })

  // The owner's example: "K" highlights Ken, ↓ moves to Kevin, typing "e" keeps Kevin (Reka alone would go back to Ken).
  it('keeps the entry the member moved to with ↓ while they type on', async () => {
    mountSearch()
    await flushPromises()
    await type('k')
    await press('ArrowDown')
    expect(highlighted()).toBe('Kevin')
    await type('ke')
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Ken', 'Kevin', 'Kenia', 'Kevins Geburtstag'])
    expect(highlighted()).toBe('Kevin')
    await type('kev')
    expect(highlighted()).toBe('Kevin')
  })

  // The owner's amendment: a dropped-out entry falls back to the first entry of ITS area (was: its group) and stays
  // remembered - listed again, it is highlighted again (Backspace is the usual way back).
  it('when the remembered entry drops out, highlights the first entry of its area, and the entry again once it is back', async () => {
    mountSearch()
    await flushPromises()
    await type('ke')
    await press('ArrowDown')
    await press('ArrowDown')
    await press('ArrowDown')
    expect(highlighted()).toBe('Kevins Geburtstag')
    await type('ken') // "Kevins Geburtstag" is no longer listed; its area's first entry is Kenia, the list's first Ken
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Ken', 'Kenia'])
    expect(highlighted()).toBe('Kenia')
    await type('ke') // back: highlighted again
    expect(highlighted()).toBe('Kevins Geburtstag')
  })

  it('falls back to the first entry of the list when the remembered entry\'s area is empty', async () => {
    mountSearch()
    await flushPromises()
    await type('ke')
    await press('ArrowDown')
    expect(highlighted()).toBe('Kevin')
    await type('kevins') // no person starts so: the suggestions are gone
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Kevins Geburtstag'])
    expect(highlighted()).toBe('Kevins Geburtstag')
    await type('kev')
    expect(highlighted()).toBe('Kevin')
  })

  it('a move after a drop-out replaces the remembered entry', async () => {
    mountSearch()
    await flushPromises()
    await type('ke')
    await press('ArrowDown')
    await press('ArrowDown')
    await press('ArrowDown')
    await type('ken')
    expect(highlighted()).toBe('Kenia')
    await press('ArrowUp')
    expect(highlighted()).toBe('Ken')
    await type('ke') // "Kevins Geburtstag" is back, but Ken is what the member moved to last
    expect(highlighted()).toBe('Ken')
  })

  it('remembers an entry the pointer moved onto like one reached by ↓', async () => {
    mountSearch()
    await flushPromises()
    await type('k')
    options()[3]!.dispatchEvent(new PointerEvent('pointermove', { bubbles: true }))
    await flushPromises()
    expect(highlighted()).toBe('Kevins Geburtstag')
    await type('ke')
    expect(highlighted()).toBe('Kevins Geburtstag')
  })

  it('Enter takes the highlighted entry: a suggestion makes a chip, a result is chosen', async () => {
    const { added, chosen } = mountSearch()
    await flushPromises()
    await type('k')
    await press('Enter')
    expect(added).toEqual(['p:Ken'])
    expect(chips()).toEqual(['Ken'])
    expect(chosen).toEqual([])
    await type('kev')
    expect(highlighted()).toBe('Kevin')
    await press('ArrowDown')
    expect(highlighted()).toBe('Kevins Geburtstag')
    await press('Enter')
    expect(chosen).toEqual(['r:kevin'])
    expect(added).toEqual(['p:Ken'])
  })

  it('remembers nothing once a chip was added: the first entry is highlighted again', async () => {
    const { added } = mountSearch()
    await flushPromises()
    await type('k')
    await press('ArrowDown')
    await press('Enter')
    expect(added).toEqual(['p:Kevin'])
    await type('k') // Kevin is offered again (and refused); the highlight is Reka's first entry
    expect(options().map((o) => o.textContent?.trim())).toEqual(['Ken', 'Kevin', 'Kenia', 'Kevins Geburtstag'])
    expect(highlighted()).toBe('Ken')
  })
})

