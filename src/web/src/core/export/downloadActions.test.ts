import { describe, it, expect, beforeAll } from 'vitest'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { canDownload, downloadHeaderActions, downloadRowActions } from './downloadActions'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'

const unlocked = memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) })
const t = (k: string) => k

beforeAll(() => {
  if (!registry.kind('X_WITH').supported) {
    registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'X_WITH', icon: 'file', exportContent: async () => null })
    registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'X_WITHOUT', icon: 'file' })
    registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'X_ONE_FILE', icon: 'file', exportContent: async () => null, downloadsAsFile: true })
  }
})

describe('canDownload', () => {
  it('a member with open keys may download a folder and a kind that brings an export hook', () => {
    expect(canDownload(unlocked, { kind: FOLDER_KIND })).toBe(true)
    expect(canDownload(unlocked, { kind: 'X_WITH' })).toBe(true)
  })
  it('a READ or READ_WRITE guest downloads what it sees, as a member does (ADR 0082 decision 5)', () => {
    for (const role of ['READ', 'READ_WRITE']) {
      expect(canDownload(guestAccessOf(role), { kind: FOLDER_KIND })).toBe(true)
      expect(canDownload(guestAccessOf(role), { kind: 'X_WITH' })).toBe(true)
    }
  })
  it('fails closed: locked keys, a kind without a hook, a kind nobody registered, no node, a WRITE_ONLY or unknown guest', () => {
    expect(canDownload(memberAccessOf(), { kind: FOLDER_KIND })).toBe(false)
    expect(canDownload(unlocked, { kind: 'X_WITHOUT' })).toBe(false)
    expect(canDownload(unlocked, { kind: 'NOBODY' })).toBe(false)
    expect(canDownload(unlocked, null)).toBe(false)
    for (const role of ['WRITE_ONLY', 'ADMIN']) expect(canDownload(guestAccessOf(role), { kind: FOLDER_KIND })).toBe(false)
  })
  it('a kind that IS one file downloads as itself through its own action: no ZIP action from core', () => {
    expect(canDownload(unlocked, { kind: 'X_ONE_FILE' })).toBe(false)
    expect(canDownload(guestAccessOf('READ'), { kind: 'X_ONE_FILE' })).toBe(false)
  })
  it('stays available while the scope is read-only (a pending deletion): reading is not a write', () => {
    expect(canDownload(memberAccessOf({ epochs: epochsOf(new Uint8Array(32)), readonly: true }), { kind: FOLDER_KIND })).toBe(true)
  })
})

describe('the action', () => {
  it('is one "Download" action after the application\'s, for header and row alike, that calls back', () => {
    let called = 0
    for (const make of [downloadHeaderActions, downloadRowActions]) {
      const [a, ...rest] = make(t, unlocked, { id: 'n', kind: FOLDER_KIND }, () => called++)
      expect(rest).toEqual([])
      expect(a).toMatchObject({ id: 'download', label: 'export.action', icon: 'download', placement: 'after' })
      a.onClick()
    }
    expect(called).toBe(2)
    expect(downloadRowActions(t, memberAccessOf(), { id: 'n', kind: FOLDER_KIND }, () => {})).toEqual([])
  })
})
