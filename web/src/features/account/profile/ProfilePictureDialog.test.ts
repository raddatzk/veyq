// @vitest-environment happy-dom
// The profile-picture dialog (uc.spaces.set-profile-picture): choose -> frame -> bake to the avatar's edge and byte budget; refuses
// a non-image (E1) and an undecodable file; "remove" only while a picture exists. Presentation + pixels only: it emits bytes.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { defineComponent } from 'vue'
import { realI18n, t } from '../../../test/i18n'

const renderSpaceIcon = vi.hoisted(() => vi.fn())
vi.mock('../../spaces/spaceIconImage', () => ({ renderSpaceIcon }))
vi.mock('../../../shared/image/mediaDeriver', () => ({ createBrowserDeriver: () => ({}) }))

import ProfilePictureDialog from './ProfilePictureDialog.vue'
import { AVATAR_EDGE, AVATAR_ENCODED_BUDGET } from './memberAvatar'

// A cropper that "decodes" at once and hands out a fixed crop; a `bad` source emits the load error instead.
const Cropper = defineComponent({
  props: ['src', 'aspect'],
  emits: ['load', 'error'],
  setup(props, { emit, expose }) {
    expose({ getImage: () => ({ nat: true }), getCrop: () => ({ x: 0, y: 0, width: 1, height: 1 }) })
    Promise.resolve().then(() => emit(String(props.src).includes('bad') ? 'error' : 'load'))
    return () => null
  },
})

async function mountDialog(props: { hasPicture?: boolean } = {}) {
  const w = mount(ProfilePictureDialog, {
    props,
    attachTo: document.body,
    global: { plugins: [realI18n(), PrimeVue], stubs: { ImageCropper: Cropper } },
  })
  await flushPromises()
  return w
}
async function pick(w: Awaited<ReturnType<typeof mountDialog>>, file: File) {
  const input = document.body.querySelector<HTMLInputElement>('[data-testid="profile-picture-file"]')!
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
  void w
}
const png = (name = 'a.png') => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/png' })
const use = () => document.body.querySelector<HTMLButtonElement>('[data-testid="profile-picture-use"]')!

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  let n = 0
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => `blob:pick-${++n}`), revokeObjectURL: vi.fn() }))
})

describe('ProfilePictureDialog', () => {
  it('"use" is disabled until a picture is chosen and framed; then it emits the baked bytes (128 px, the pad budget)', async () => {
    renderSpaceIcon.mockResolvedValue(new Uint8Array([7, 7]))
    const w = await mountDialog()
    expect(use().disabled).toBe(true)
    await pick(w, png())
    expect(use().disabled).toBe(false)
    use().click()
    await flushPromises()
    expect(renderSpaceIcon).toHaveBeenCalledWith(expect.anything(), expect.anything(), expect.anything(), { edge: AVATAR_EDGE, budget: AVATAR_ENCODED_BUDGET })
    expect(w.emitted('use')![0][0]).toEqual(new Uint8Array([7, 7]))
    w.unmount()
  })

  it('E1: a non-image file is refused with a message and nothing is kept', async () => {
    const w = await mountDialog()
    await pick(w, new File(['x'], 'a.txt', { type: 'text/plain' }))
    expect(document.body.textContent).toContain(t('profilePicture.notImage'))
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    w.unmount()
  })

  it('E1: an image the browser cannot decode is refused too, and its object URL is revoked', async () => {
    vi.mocked(URL.createObjectURL).mockReturnValue('blob:bad')
    const w = await mountDialog()
    await pick(w, png('bad.png'))
    expect(document.body.textContent).toContain(t('profilePicture.notImage'))
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:bad')
    w.unmount()
  })

  it('says so when the pixels cannot be prepared and emits nothing', async () => {
    renderSpaceIcon.mockRejectedValue(new Error('icon_encode_too_large'))
    const w = await mountDialog()
    await pick(w, png())
    use().click()
    await flushPromises()
    expect(document.body.textContent).toContain(t('profilePicture.failed'))
    expect(w.emitted('use')).toBeUndefined()
    w.unmount()
  })

  it('offers "remove" only while the member has a picture, and emits it', async () => {
    const without = await mountDialog()
    expect(document.body.querySelector('[data-testid="profile-picture-remove"]')).toBeNull()
    without.unmount()
    const withPic = await mountDialog({ hasPicture: true })
    document.body.querySelector<HTMLButtonElement>('[data-testid="profile-picture-remove"]')!.click()
    expect(withPic.emitted('remove')).toHaveLength(1)
    withPic.unmount()
  })
})
