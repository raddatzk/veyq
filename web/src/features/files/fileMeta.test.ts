// A file's sealed metadata envelope (`fileMeta.ts`): what it holds, under which key and binding it opens, and how a
// read for display and a read for a write differ. The seal context is spelled out as a LITERAL here (`node-meta`,
// scope, node), never taken from the module, so a changed context in the source disagrees with this test.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fileMetaOf, openFileMetaForWrite, readFileMeta, sealFileMeta } from './fileMeta'
import { MetaUnreadableError } from '../../core/node/sealedMetaWrite'

const SCOPE = '5c09e000-0000-4000-8000-0000000000aa'
const NODE = '5c09e000-0000-4000-8000-0000000000f1'
const OTHER = '5c09e000-0000-4000-8000-0000000000f2'
const nodeKey = v1.randomBytes(32)
const at = { scopeId: SCOPE, nodeId: NODE }
const META = { mime: 'application/pdf', size: 123_456, modifiedAt: 1_700_000_000_000 }
const sealLiteral = (key: Uint8Array, nodeId: string, value: unknown) =>
  b64std(v1.seal({ purpose: 'node-meta', scopeId: SCOPE, nodeId }, key, new TextEncoder().encode(JSON.stringify(value))))

describe('a file\'s metadata', () => {
  it('is what the browser reports for the picked file: MIME type, plaintext size, modified time', () => {
    const file = new File([new Uint8Array(42)], 'Bericht.pdf', { type: 'application/pdf', lastModified: 1_650_000_000_000 })
    expect(fileMetaOf(file)).toEqual({ mime: 'application/pdf', size: 42, modifiedAt: 1_650_000_000_000 })
  })

  it('is sealed under the node key with the node-meta seal bound to (scope, node), as a v1 JSON envelope', () => {
    const sealed = sealFileMeta(nodeKey, at, { v: 1, ...META })
    const opened = JSON.parse(new TextDecoder().decode(v1.open({ purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE }, nodeKey, fromB64std(sealed))))
    expect(opened).toEqual({ v: 1, ...META })
    // bound: another node's address, or another key, does not open it
    expect(() => v1.open({ purpose: 'node-meta', scopeId: SCOPE, nodeId: OTHER }, nodeKey, fromB64std(sealed))).toThrow(v1.OpenRefused)
    expect(() => v1.open({ purpose: 'node-meta', scopeId: SCOPE, nodeId: NODE }, v1.randomBytes(32), fromB64std(sealed))).toThrow(v1.OpenRefused)
  })

  it('reads back for display, and is null when absent, another node\'s, under another key or malformed', () => {
    expect(readFileMeta(nodeKey, at, sealLiteral(nodeKey, NODE, { v: 1, ...META }))).toEqual(META)
    expect(readFileMeta(nodeKey, at, null)).toBeNull()
    expect(readFileMeta(nodeKey, at, sealLiteral(nodeKey, OTHER, { v: 1, ...META }))).toBeNull()
    expect(readFileMeta(nodeKey, at, sealLiteral(v1.randomBytes(32), NODE, { v: 1, ...META }))).toBeNull()
    for (const bad of [{ v: 1, ...META, size: -1 }, { v: 1, ...META, mime: 7 }, { v: 1, mime: 'x', size: 1 }, 'a string', null]) {
      expect(readFileMeta(nodeKey, at, sealLiteral(nodeKey, NODE, bad))).toBeNull()
    }
  })

  it('opens for a write: absent is fresh, a field it does not know is kept, an unreadable blob is refused', () => {
    expect(openFileMetaForWrite(nodeKey, at, null)).toEqual({ v: 1 })
    expect(openFileMetaForWrite(nodeKey, at, sealLiteral(nodeKey, NODE, { v: 1, ...META, later: { x: 1 } })))
      .toEqual({ v: 1, ...META, later: { x: 1 } })
    expect(() => openFileMetaForWrite(nodeKey, at, sealLiteral(nodeKey, OTHER, { v: 1 }))).toThrow(MetaUnreadableError)
    expect(() => openFileMetaForWrite(nodeKey, at, sealLiteral(nodeKey, NODE, [1, 2]))).toThrow(MetaUnreadableError)
  })
})
