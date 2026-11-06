// A bookmark's sealed meta (ADR 0120 decision 3): the link and its password under the node's own key, padded so the
// operator learns neither the host's length nor whether there is a password - and read back fail closed.
import { describe, expect, it } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { sealNodeMeta, unsealNodeMeta } from '../node/nodeMeta'
import { keptLink } from './bookmarkLink'
import { bookmarkMetaObject, bookmarkOf, META_PAD } from './bookmarkMeta'

const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000aa', nodeId: '5c09e000-0000-4000-8000-0000000000bb' }
const linkAt = (host: string) => keptLink(`https://${host}/s/${'S'.repeat(43)}#k=${b64url(new Uint8Array(32).fill(3))}`)!

describe('the bookmark meta', () => {
  it('opens under the node\'s key as the link and password that were sealed, and under no other key', () => {
    const key = v1.randomBytes(32)
    const link = linkAt('photos.example.org')
    const sealed = sealNodeMeta(key, ADDRESS, bookmarkMetaObject(link, 'correct horse'))
    expect(bookmarkOf(unsealNodeMeta(key, ADDRESS, sealed))).toEqual({ link, password: 'correct horse' })
    expect(bookmarkOf(unsealNodeMeta(key, ADDRESS, sealNodeMeta(key, ADDRESS, bookmarkMetaObject(link, null))))).toEqual({ link, password: null })
    expect(bookmarkOf(unsealNodeMeta(v1.randomBytes(32), ADDRESS, sealed))).toBeNull()
    expect(bookmarkOf(unsealNodeMeta(key, { ...ADDRESS, nodeId: '5c09e000-0000-4000-8000-0000000000cc' }, sealed))).toBeNull()
  })

  it('pads the sealed plaintext to a multiple of 512 bytes, so a host\'s length or a password does not show', () => {
    const key = v1.randomBytes(32)
    const sizes = new Set<number>()
    for (const [host, password] of [['a.example', null], ['photos.a-much-longer-instance-name.example.org', null], ['a.example', 'pw'], ['a.example', 'ä'.repeat(40)]] as const) {
      const meta = bookmarkMetaObject(linkAt(host), password)
      expect(new TextEncoder().encode(JSON.stringify({ ...meta, v: 1 })).length % META_PAD, host).toBe(0)
      sizes.add(sealNodeMeta(key, ADDRESS, meta).length)
    }
    expect(sizes.size, 'one sealed size for every one of them').toBe(1)
  })

  it('reads nothing from a meta that keeps no bookmark this build reads (fail closed)', () => {
    const link = linkAt('photos.example.org')
    expect(bookmarkOf(null)).toBeNull()
    expect(bookmarkOf({ v: 1 })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: 'x' })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: [link.link] })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: `${link.link}&report` } })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: 'https://photos.example.org/' } })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: link.link, password: 7 } })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: link.link, password: '' } })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: link.link, password: 'x'.repeat(257) } })).toBeNull()
    expect(bookmarkOf({ v: 1, bookmark: { v: 1, link: link.link, password: 'x'.repeat(256) } })?.password).toHaveLength(256) // control
  })
})
