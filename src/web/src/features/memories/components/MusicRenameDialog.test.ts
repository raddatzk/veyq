// @vitest-environment happy-dom
// uc.memory.background-music A7 (ADR 0135 decision 4): the Rename dialog - the track's own name as it is filled in, an
// emptied name handed on as none (the file names the track again), and the reason a save did not land.
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import MusicRenameDialog from './MusicRenameDialog.vue'
import { realI18n } from '../../../test/i18n'

let wrapper: VueWrapper | null = null
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

function open(name: string | null, extra: { error?: 'conflict' | 'failed' | null } = {}) {
  wrapper = mount(MusicRenameDialog, {
    props: { name, fallbackName: 'Morning Tune', ...extra },
    attachTo: document.body,
    global: { plugins: [realI18n()] },
  })
  return wrapper
}
const nameField = () => document.querySelector<HTMLInputElement>('[data-testid="music-rename-name"]')!
async function type(value: string) {
  nameField().value = value
  nameField().dispatchEvent(new Event('input'))
  await flushPromises()
}
async function submit() {
  document.querySelector<HTMLFormElement>('[data-testid="music-rename"]')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flushPromises()
}

describe('MusicRenameDialog', () => {
  it("shows the own name, the file's name as the placeholder, and hands the trimmed name on", async () => {
    const w = open('Ridge')
    await flushPromises()
    expect(nameField().value).toBe('Ridge')
    expect(nameField().placeholder).toBe('Morning Tune')
    await type('  Summer Song  ')
    await submit()
    expect(w.emitted('save')?.[0]).toEqual(['Summer Song'])
  })

  it("hands an emptied name on as none: the file's name is the title again", async () => {
    const w = open('Ridge')
    await flushPromises()
    await type('   ')
    await submit()
    expect(w.emitted('save')?.[0]).toEqual([null])
  })

  it('says why a save did not land, and shows a peer\'s name after a conflict', async () => {
    const w = open(null, { error: 'conflict' })
    await flushPromises()
    expect(document.body.textContent).toContain('Someone else renamed this track meanwhile.')
    ;(w.vm as unknown as { reset: (n: string | null) => void }).reset('Their Name')
    await flushPromises()
    expect(nameField().value).toBe('Their Name')
    await w.setProps({ error: 'failed' })
    expect(document.body.textContent).toContain('The track could not be renamed.')
  })
})
