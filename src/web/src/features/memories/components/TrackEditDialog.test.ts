// @vitest-environment happy-dom
// uc.memory.tracks A8 (ADR 0130 decision 9): the Edit dialog - the label as it is filled in, an emptied name handed on as
// none (the file names the track again), "No activity" as none, and the reason a save did not land.
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import TrackEditDialog from './TrackEditDialog.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import { realI18n } from '../../../test/i18n'
import type { TrackLabel } from '../track/trackLabel'

let wrapper: VueWrapper | null = null
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

function open(label: TrackLabel, extra: { error?: 'conflict' | 'failed' | null } = {}) {
  wrapper = mount(TrackEditDialog, {
    props: { label, fallbackName: 'Morning', ...extra },
    attachTo: document.body,
    global: { plugins: [realI18n()] },
  })
  return wrapper
}
/** The activity field's props (the layer's select is generic, so its wrapper's `props()` is typed by no one prop). */
const selectProps = (w: VueWrapper) => w.findComponent(VeyqSelect).props() as unknown as { modelValue: string; options: { label: string }[] }
const nameField = () => document.querySelector<HTMLInputElement>('[data-testid="track-edit-name"]')!
async function submit() {
  document.querySelector<HTMLFormElement>('[data-testid="track-edit"]')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
}

describe('TrackEditDialog', () => {
  it('shows the label, the file\'s name as the placeholder, and hands the edited label on', async () => {
    const w = open({ name: 'Ridge', activity: 'running' })
    await flushPromises()
    expect(nameField().value).toBe('Ridge')
    expect(nameField().placeholder).toBe('Morning')
    expect(selectProps(w).modelValue).toBe('running')

    nameField().value = '  Gipfel  '
    nameField().dispatchEvent(new Event('input'))
    w.findComponent(VeyqSelect).vm.$emit('update:modelValue', 'skiing')
    await submit()
    expect(w.emitted('save')?.[0]).toEqual([{ name: 'Gipfel', activity: 'skiing' }])
  })

  it('hands an emptied name and "No activity" on as none', async () => {
    const w = open({ name: 'Ridge', activity: 'hiking' })
    await flushPromises()
    nameField().value = '   '
    nameField().dispatchEvent(new Event('input'))
    w.findComponent(VeyqSelect).vm.$emit('update:modelValue', 'none')
    await submit()
    expect(w.emitted('save')?.[0]).toEqual([{ name: null, activity: null }])
  })

  it('offers no activity and the ten, in the old application\'s order', async () => {
    const w = open({ name: null, activity: null })
    await flushPromises()
    expect(selectProps(w).modelValue).toBe('none')
    expect(selectProps(w).options.map((o) => o.label)).toEqual([
      'No activity', 'Hiking', 'Walking', 'Running', 'Cycling', 'Mountain biking', 'Road cycling', 'Gravel cycling', 'Skiing',
      'Water', 'Other',
    ])
  })

  it('says why a save did not land', async () => {
    open({ name: null, activity: null }, { error: 'conflict' })
    await flushPromises()
    expect(document.body.textContent).toContain('Someone else changed this track meanwhile.')
  })
})
