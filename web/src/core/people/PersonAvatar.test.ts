// @vitest-environment happy-dom
// PersonAvatar (ADR 0084): the member's picture when the directory cache holds one, else the letter; decorative either way.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

const avatarUrlOf = vi.hoisted(() => vi.fn())
vi.mock('./avatarCache', () => ({ avatarUrlOf }))

import PersonAvatar from './PersonAvatar.vue'

const mountAvatar = (props: Record<string, unknown>) => mount(PersonAvatar, { props: props as { name: string }, global: { plugins: [PrimeVue] } })

beforeEach(() => {
  vi.clearAllMocks()
  avatarUrlOf.mockReturnValue(null)
})

describe('PersonAvatar', () => {
  it('shows the letter of the display name when the member has no picture', () => {
    const w = mountAvatar({ name: 'anna meyer', userId: 'u1' })
    expect(w.text()).toBe('A')
    expect(w.find('img').exists()).toBe(false)
  })

  it('shows the cached picture of the user as an image, hidden from assistive technology (the name is next to it)', () => {
    avatarUrlOf.mockImplementation((id: string) => (id === 'u1' ? 'blob:anna' : null))
    const w = mountAvatar({ name: 'Anna', userId: 'u1' })
    expect(avatarUrlOf).toHaveBeenCalledWith('u1')
    expect(w.get('img').attributes('src')).toBe('blob:anna')
    expect(w.attributes('aria-hidden')).toBe('true')
  })

  it('a share view / unknown user passes no userId and gets the letter', () => {
    // the cache itself answers null for an empty id (pinned in avatarCache.test.ts); the component invents no id
    avatarUrlOf.mockImplementation((id: string | null) => (id ? 'blob:x' : null))
    const w = mountAvatar({ name: 'Guest' })
    expect(w.text()).toBe('G')
    expect(w.find('img').exists()).toBe(false)
  })

  it('an explicit image (a preview) wins over the cache', () => {
    avatarUrlOf.mockReturnValue('blob:cached')
    const w = mountAvatar({ name: 'Anna', userId: 'u1', image: 'blob:preview' })
    expect(w.get('img').attributes('src')).toBe('blob:preview')
  })

  it('a picture that fails to load falls back to the letter (E3: never a broken image)', async () => {
    avatarUrlOf.mockReturnValue('blob:broken')
    const w = mountAvatar({ name: 'Anna', userId: 'u1' })
    await w.get('img').trigger('error')
    expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toBe('A')
  })
})
