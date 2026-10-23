// @vitest-environment happy-dom
// The heart as a core action (core/favorites/favoriteActions.ts, inbox #409): what it offers a member for each kind, what it
// offers a guest (nothing, and the store is not even read), and what a click does (toggle in the access's scope).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./favoritesStore', () => ({ isFavorite: vi.fn(), toggleFavorite: vi.fn(async () => {}) }))

import { favoriteHeaderActions, favoriteRowActions } from './favoriteActions'
import { isFavorite, toggleFavorite } from './favoritesStore'
import { FOLDER_KIND } from '../node/kind'
import { MEMORY_KIND } from '../../features/memories/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { guestAccessOf, memberAccessOf } from '../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const t = (key: string) => `<${key}>`
const member = memberAccessOf({ scopeId: SCOPE })
const failed = vi.fn<(summary: string) => void>()

beforeEach(() => {
  vi.mocked(isFavorite).mockReset().mockReturnValue(false)
  vi.mocked(toggleFavorite).mockReset().mockResolvedValue(undefined)
  failed.mockReset()
})

describe('the heart', () => {
  it.each([FOLDER_KIND, MEMORY_KIND, FILE_KIND, DROPBOX_KIND])('a member gets one first-class, unpressed heart for a %s, in header and row alike', (kind) => {
    const node = { id: 'n1', kind }
    for (const actions of [favoriteHeaderActions(t, failed, member, node), favoriteRowActions(t, failed, member, node)]) {
      expect(actions).toHaveLength(1)
      expect(actions[0]).toMatchObject({ id: 'favorite', label: '<node.favorite>', icon: 'heart', pressed: false, placement: 'before' })
    }
    expect(favoriteRowActions(t, failed, member, node)[0].first, 'a visible button on a row, never in the menu').toBe(true)
  })

  it('a pinned node shows the filled heart, pressed, and the way to remove it', () => {
    vi.mocked(isFavorite).mockImplementation((id) => id === 'n1')
    expect(favoriteRowActions(t, failed, member, { id: 'n1', kind: FOLDER_KIND })[0]).toMatchObject({
      label: '<node.unfavorite>', icon: 'heart-fill', pressed: true,
    })
    expect(favoriteRowActions(t, failed, member, { id: 'n2', kind: FOLDER_KIND })[0]).toMatchObject({ pressed: false })
  })

  it('a click toggles THIS node in the access\'s scope', () => {
    favoriteHeaderActions(t, failed, member, { id: 'n1', kind: MEMORY_KIND })[0].onClick()
    expect(toggleFavorite).toHaveBeenCalledWith('n1', SCOPE)
  })

  // inbox #500: the store reverts the heart and rethrows; the click's owner says the pin did not land, in the host's
  // toast — and says nothing when it did.
  it('a pin that does not land says so; one that lands says nothing', async () => {
    favoriteRowActions(t, failed, member, { id: 'n1', kind: FOLDER_KIND })[0].onClick()
    await vi.waitFor(() => expect(toggleFavorite).toHaveBeenCalled())
    await Promise.resolve()
    expect(failed).not.toHaveBeenCalled()
    vi.mocked(toggleFavorite).mockRejectedValueOnce(new Error('400'))
    favoriteHeaderActions(t, failed, member, { id: 'n1', kind: FOLDER_KIND })[0].onClick()
    await vi.waitFor(() => expect(failed).toHaveBeenCalledWith('<node.favoriteFailed>'))
    expect(failed).toHaveBeenCalledTimes(1)
  })

  it('a share guest gets no heart in any role, and the pin store is not even read', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY']) {
      const guest = guestAccessOf(role)
      expect(favoriteHeaderActions(t, failed, guest, { id: 'n1', kind: FOLDER_KIND }), role).toEqual([])
      expect(favoriteRowActions(t, failed, guest, { id: 'n1', kind: MEMORY_KIND }), role).toEqual([])
    }
    expect(isFavorite).not.toHaveBeenCalled()
  })

  it('no heart for the scope root or a kind this client cannot draw', () => {
    expect(favoriteHeaderActions(t, failed, member, null)).toEqual([])
    expect(favoriteRowActions(t, failed, member, { id: 'n1', kind: 'FUTURE_KIND' })).toEqual([])
    expect(isFavorite).not.toHaveBeenCalled()
  })
})
