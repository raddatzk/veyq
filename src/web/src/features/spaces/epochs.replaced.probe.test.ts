// ADR 0115's probe on the web, written against the tree before it is built: a scope has one key at a time, so a device
// holding epoch 2 holds that key and no other — nothing it is served opens an older one. Today `deriveEpochKeys` peels the
// chain links down to E_0. Skipped until ADR 0115's web phase replaces the peel; the red run is quoted in its phase report.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { deriveEpochKeys, epochLinkContext, type EpochLink } from './epochs'

const SCOPE = '5c09e000-0000-4000-8000-0000000000e1'

function link(epoch: number, keyE: Uint8Array, keyPrev: Uint8Array): EpochLink {
  return { epoch, wrappedPrev: b64std(v1.seal(epochLinkContext(SCOPE, epoch), keyE, keyPrev)) }
}

describe.skip('ADR 0115 probe: a held scope key opens no earlier one', () => {
  it('a device holding epoch 2 holds exactly the epoch-2 key', () => {
    const e0 = v1.randomBytes(32)
    const e1 = v1.randomBytes(32)
    const e2 = v1.randomBytes(32)
    const ek = deriveEpochKeys(SCOPE, e2, 2, [link(1, e1, e0), link(2, e2, e1)])
    expect(ek.keys.get(2)).toEqual(e2) // control: the granted key is held
    expect([...ek.keys.keys()]).toEqual([2])
  })
})
