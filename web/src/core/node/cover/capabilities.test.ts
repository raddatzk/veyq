// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { coverCapabilities } from './capabilities'
import { FOLDER_KIND } from '../kind'
import { ShareRole } from '../../../api/generated/sharing'
import type { EpochKeys } from '../../../features/spaces/epochs'
import { guestAccessOf, memberAccessOf } from '../../../test/models'

// The cover capability (inbox #411): one fail-closed function for every kind. The registry is the real one `src/test/setup.ts`
// fills (core's FOLDER, Memories' and Depot's kinds), so what a kind says about itself is what is asserted.
const EPOCHS = {} as EpochKeys
const writer = memberAccessOf({ epochs: EPOCHS })
const at = (kind: string | null, inIdentityFolder = false) => ({ kind, inIdentityFolder })

describe('coverCapabilities', () => {
  it.each([FOLDER_KIND, 'MEMORY', 'CHAPTER', 'DEPOT_DROPBOX'])('a writer with the keys may set the cover of a %s', (kind) => {
    expect(coverCapabilities(writer, at(kind)).setCover).toBe(true)
  })

  it.each(['FILE', 'NOT_A_REGISTERED_KIND', null])('and of nothing else: %s has no cover', (kind) => {
    expect(coverCapabilities(writer, at(kind)).setCover).toBe(false)
  })

  it('every other column is no: a pending deletion, locked keys, a guest\'s identity folder', () => {
    expect(coverCapabilities(memberAccessOf({ epochs: EPOCHS, readonly: true }), at(FOLDER_KIND)).setCover).toBe(false)
    expect(coverCapabilities(memberAccessOf({ epochs: null }), at(FOLDER_KIND)).setCover).toBe(false)
    expect(coverCapabilities(writer, at(FOLDER_KIND, true)).setCover).toBe(false)
  })

  it.each([ShareRole.Read, ShareRole.ReadWrite, ShareRole.WriteOnly, 'FUTURE_ROLE'])('a %s guest sets no cover, on any kind', (role) => {
    for (const kind of [FOLDER_KIND, 'MEMORY', 'DEPOT_DROPBOX']) {
      expect(coverCapabilities(guestAccessOf(role), at(kind)).setCover, `${role} ${kind}`).toBe(false)
    }
  })
})
