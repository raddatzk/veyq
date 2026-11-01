// @vitest-environment happy-dom
// The first test this component has ever had. It shipped with cr-0116 driving the share note and its only
// caller's test stubs it out (`LocaleMarkdownEditor: true`), so until the lift into `shared/ui` nothing
// anywhere asserted what it does — which is also why the lift owes one: every other `shared/ui` component here
// carries a colocated test, and a component nobody tests is a component nobody can move safely.
//
// What only this file can pin is the map semantics: **the keys ARE the languages**. A row exists because the
// map has that key, never because the app supports that language; `''` is a language somebody added and left
// empty, not a language that is absent. Everything the tenant-terms editor was rebuilt to say rests on that.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

import LocaleMarkdownEditor from './LocaleMarkdownEditor.vue'
import type { LocaleMap } from './localeMap'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  VeyqDialog: {
    props: ['visible', 'header'],
    emits: ['update:visible'],
    template: '<div class="dlg"><h2>{{ header }}</h2><slot /></div>',
  },
  VeyqButton: {
    props: ['label', 'icon', 'variant'],
    // `emits` is load-bearing: without it the parent's @click also arrives as a native fallthrough listener,
    // so one click fires the handler twice (the same trap `ConfirmDialog.test.ts` records).
    emits: ['click'],
    template: '<button v-bind="$attrs" :class="variant" @click="$emit(\'click\')">{{ label }}</button>',
  },
  // A native select in the layer's place: Reka's list only renders while open, in a portal.
  VeyqSelect: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      '<select class="sel" :value="modelValue ?? \'\'" @change="$emit(\'update:modelValue\', $event.target.value)">' +
      '<option value="" /><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
  },
}

type Props = InstanceType<typeof LocaleMarkdownEditor>['$props']
const mountEditor = (props: Props, slots: Record<string, string> = {}) =>
  mount(LocaleMarkdownEditor, { props, slots, global: { plugins: [realI18n()], stubs } })

const rows = (w: VueWrapper) => w.findAll('.locale-md-row')
const langs = (w: VueWrapper) => rows(w).map((r) => r.find('.locale-md-lang').text())
const button = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)
const emitted = (w: VueWrapper) => w.emitted('update:modelValue') as LocaleMap[][] | undefined
const lastMap = (w: VueWrapper) => emitted(w)!.at(-1)![0]
const addButton = (w: VueWrapper) => button(w, t('localeEditor.addLanguage'))
/** Open "Add language" and read the languages its picker offers. */
async function offered(w: VueWrapper): Promise<string[]> {
  await addButton(w)!.trigger('click')
  return w.findAll('.sel option').map((o) => o.text()).filter(Boolean)
}
/** Add a language through the editor: open it, pick the language, write the text, save. */
async function add(w: VueWrapper, locale: string, text: string) {
  await addButton(w)!.trigger('click')
  await w.find('.sel').setValue(locale)
  await w.find('textarea').setValue(text)
  await button(w, t('common.save'))!.trigger('click')
}

describe('LocaleMarkdownEditor — the map is the language list', () => {
  it('renders one row per language the map carries, and none for the rest', () => {
    // BITE 1 (lever: render `SUPPORTED_LOCALES` instead of `Object.keys(modelValue)`). The app speaks two
    // languages and this map carries one — exactly one row, and it is the one that has text.
    const w = mountEditor({ modelValue: { de: '# Bedingungen' } })
    expect(rows(w)).toHaveLength(1)
    expect(langs(w)).toEqual(['Deutsch'])
    // Asserted against the ROWS, not the whole render: "Add English" is legitimately on the page, and a
    // `w.text()` probe would have called that a violation of the very thing this test exists to pin.
    expect(rows(w).some((r) => r.text().includes('English'))).toBe(false)
  })

  it('an empty string is a language that EXISTS — not an absent one', () => {
    // BITE 2 (lever: collapse the map with `.filter(v => v)`). `{ de: '' }` is a German text somebody started;
    // `{}` is no German at all. Nothing outside this component can see that distinction, so it is pinned here.
    const started = mountEditor({ modelValue: { de: '' } })
    expect(langs(started)).toEqual(['Deutsch'])
    expect(started.find('.locale-md-empty').exists()).toBe(false)

    const absent = mountEditor({ modelValue: {} })
    expect(rows(absent)).toHaveLength(0)
    expect(absent.find('.locale-md-empty').text()).toBe(t('localeEditor.empty'))
  })

  it('has ONE add button, whose editor offers only the languages not added yet, and none once all exist', async () => {
    // Owner 2026-11-01: a button per language grows into a row nobody reads as languages come; the language is
    // picked in the editor instead, and a language already there is never offered again.
    const none = mountEditor({ modelValue: {} })
    expect(none.findAll('.locale-md-add button')).toHaveLength(1)
    expect(await offered(none)).toEqual(['English', 'Deutsch'])

    expect(await offered(mountEditor({ modelValue: { de: 'x' } }))).toEqual(['English'])

    const both = mountEditor({ modelValue: { de: 'x', en: 'y' } })
    expect(both.find('.locale-md-add').exists()).toBe(false)
  })

  it('adds the picked language with the text written, and only on Save', async () => {
    const w = mountEditor({ modelValue: { de: 'x' } })
    await add(w, 'en', 'Hello')
    expect(lastMap(w)).toEqual({ de: 'x', en: 'Hello' })
    expect(w.find('.dlg').exists()).toBe(false)
  })

  it('a cancelled add adds nothing, and Save waits for a language', async () => {
    const w = mountEditor({ modelValue: {} })
    await addButton(w)!.trigger('click')
    expect(w.find('.dlg h2').text()).toBe(t('localeEditor.addTitle'))
    expect(button(w, t('common.save'))!.attributes('disabled')).toBeDefined()
    await w.find('textarea').setValue('weg')
    await button(w, t('common.cancel'))!.trigger('click')
    expect(emitted(w)).toBeUndefined()
  })

  it('narrows to the languages the caller allows', async () => {
    expect(await offered(mountEditor({ modelValue: {}, locales: ['de'] }))).toEqual(['Deutsch'])
  })
})

describe('LocaleMarkdownEditor — editing one language', () => {
  it('opens the dialog on the row it was asked about and commits the draft on Save', async () => {
    const w = mountEditor({ modelValue: { de: 'alt', en: 'old' } })
    await rows(w)[0].findAll('button')[0].trigger('click') // Edit, on the German row

    expect(w.find('.dlg h2').text()).toBe(t('localeEditor.editTitle', { lang: 'Deutsch' }))
    const area = w.find('textarea')
    expect((area.element as HTMLTextAreaElement).value).toBe('alt')

    await area.setValue('neu')
    await button(w, t('common.save'))!.trigger('click')
    expect(lastMap(w)).toEqual({ de: 'neu', en: 'old' })
    expect(w.find('.dlg').exists()).toBe(false)
  })

  it('discards the draft on Cancel — the map is not touched at all', async () => {
    const w = mountEditor({ modelValue: { de: 'alt' } })
    await rows(w)[0].findAll('button')[0].trigger('click')
    await w.find('textarea').setValue('weggeworfen')
    await button(w, t('common.cancel'))!.trigger('click')
    expect(emitted(w)).toBeUndefined()
  })

  it('carries the caller’s placeholder into the textarea — the one string that is per-caller', async () => {
    const w = mountEditor({ modelValue: { de: '' }, placeholder: 'Write the terms in Markdown…' })
    await rows(w)[0].findAll('button')[0].trigger('click')
    expect(w.find('textarea').attributes('placeholder')).toBe('Write the terms in Markdown…')
  })
})

describe('LocaleMarkdownEditor — removing a language', () => {
  const removeButton = (w: VueWrapper, lang: string) =>
    w.findAll('button').find((b) => b.attributes('aria-label') === t('localeEditor.removeLang', { lang }))

  it('drops the key outright when nobody vetoes', async () => {
    const w = mountEditor({ modelValue: { de: 'x', en: 'y' } })
    await removeButton(w, 'Deutsch')!.trigger('click')
    await flushPromises()
    expect(lastMap(w)).toEqual({ en: 'y' })
  })

  it('asks `beforeRemove` first, and a “no” leaves the map alone', async () => {
    const veto = vi.fn().mockResolvedValue(false)
    const w = mountEditor({ modelValue: { de: 'x' }, beforeRemove: veto })
    await removeButton(w, 'Deutsch')!.trigger('click')
    await flushPromises()
    expect(veto).toHaveBeenCalledWith('de')
    expect(emitted(w)).toBeUndefined()
  })

  it('removes once `beforeRemove` says yes', async () => {
    const w = mountEditor({ modelValue: { de: 'x' }, beforeRemove: () => true })
    await removeButton(w, 'Deutsch')!.trigger('click')
    await flushPromises()
    expect(lastMap(w)).toEqual({})
  })
})

describe('LocaleMarkdownEditor — the row-actions slot', () => {
  it('hands each row its own locale, so a caller can hang a per-language action there', () => {
    const w = mountEditor(
      { modelValue: { de: 'x', en: 'y' } },
      { 'row-actions': '<span class="extra">{{ params.locale }}</span>' },
    )
    expect(w.findAll('.extra').map((e) => e.text())).toEqual(['de', 'en'])
  })
})

describe('the share note still behaves as it did (cr-0116 control)', () => {
  // BITE 4 (lever: make `locales` required, i.e. drop its `SUPPORTED_LOCALES` default). The lift was supposed
  // to change nothing for the caller it did not set out to change, and `ShareCreateDialog` drives the component
  // with exactly these two props — `v-model` and a placeholder, no `:locales`, no `:beforeRemove`, no slot.
  // Driven that way it must still produce the same map for the same input as before the move.
  const asShareDialogDrivesIt = (note: LocaleMap) =>
    mountEditor({ modelValue: note, placeholder: t('share.notePlaceholder') })

  it('offers both languages, adds one, and writes the text under its key', async () => {
    const empty = asShareDialogDrivesIt({})
    expect(empty.find('.locale-md-empty').exists()).toBe(true)
    await add(empty, 'de', '')
    expect(lastMap(empty)).toEqual({ de: '' })

    const started = asShareDialogDrivesIt({ de: '' })
    await rows(started)[0].findAll('button')[0].trigger('click')
    await started.find('textarea').setValue('Willkommen!')
    await button(started, t('common.save'))!.trigger('click')
    expect(lastMap(started)).toEqual({ de: 'Willkommen!' })
  })

  it('removes a note language without asking anyone — the share note never had a confirmation', async () => {
    const w = asShareDialogDrivesIt({ de: 'x', en: 'y' })
    const remove = w.findAll('button').find((b) => b.attributes('aria-label') === t('localeEditor.removeLang', { lang: 'English' }))
    await remove!.trigger('click')
    await flushPromises()
    expect(lastMap(w)).toEqual({ de: 'x' })
  })
})
