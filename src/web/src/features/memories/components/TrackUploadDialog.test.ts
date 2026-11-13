// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130 decision 9): the add's dialog - one group per picked file with the Edit's two fields, empty
// at first with the file's name as the placeholder, each file's label handed on in the files' order; Cancel hands none.
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import TrackUploadDialog from './TrackUploadDialog.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import { realI18n } from '../../../test/i18n'

let wrapper: VueWrapper | null = null
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

const gpx = (name: string) => new File(['<gpx/>'], name)
function open(files: { file: File; fallbackName: string | null }[]) {
  wrapper = mount(TrackUploadDialog, { props: { files }, attachTo: document.body, global: { plugins: [realI18n()] } })
  return wrapper
}
const field = (id: string) => document.querySelector<HTMLInputElement>(`[data-testid="${id}"]`)!
function type(id: string, value: string) {
  field(id).value = value
  field(id).dispatchEvent(new Event('input'))
}
async function submit() {
  document.querySelector<HTMLFormElement>('[data-testid="track-upload"]')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
}

describe('TrackUploadDialog', () => {
  it('asks a name and an activity per file and hands each file\'s label on in order', async () => {
    const w = open([{ file: gpx('one.gpx'), fallbackName: 'Sunrise' }, { file: gpx('two.gpx'), fallbackName: null }])
    await flushPromises()
    expect(Array.from(document.querySelectorAll('[data-testid="track-upload-file"] legend')).map((l) => l.textContent)).toEqual(['one.gpx', 'two.gpx'])
    expect(document.body.textContent).toContain('Add tracks')
    expect([field('track-upload-0-name').value, field('track-upload-0-name').placeholder]).toEqual(['', 'Sunrise'])
    expect(field('track-upload-1-name').placeholder).toBe('')

    type('track-upload-0-name', '  Ridge  ')
    const selects = w.findAllComponents(VeyqSelect)
    expect(selects).toHaveLength(2)
    expect(selects.map((s) => (s.props() as unknown as { modelValue: string }).modelValue)).toEqual(['none', 'none'])
    selects[1].vm.$emit('update:modelValue', 'cycling')
    await submit()
    expect(w.emitted('add')?.[0]).toEqual([[{ name: 'Ridge', activity: null }, { name: null, activity: 'cycling' }]])
  })

  it('says "Add track" for one file, and Cancel hands no label on', async () => {
    const w = open([{ file: gpx('one.gpx'), fallbackName: 'Sunrise' }])
    await flushPromises()
    expect(document.querySelector('[data-testid="track-upload-add"]')!.textContent).toContain('Add track')
    expect(document.querySelector('[data-testid="track-upload-add"]')!.textContent).not.toContain('Add tracks')
    ;(document.querySelector('[data-testid="track-upload-cancel"]') as HTMLElement).click()
    await flushPromises()
    expect(w.emitted('close')).toHaveLength(1)
    expect(w.emitted('add')).toBeUndefined()
  })
})
