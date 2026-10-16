import { describe, it, expect } from 'vitest'
import * as v1 from './v1'

// ADR 0093 § 9's property, end to end through the package's own exports: a report hands the moderator the key of ONE
// field, and that key opens that one revision of that one field — not a sibling's name, not the node's key or meta, not
// the next revision. Before the field seal the only key that opened node A's name was its parent's K_node, and this
// file was red against that API (B1, B2: the handed-over key opened sibling B's name, A's node key and the next
// revision). The vectors pin the bytes (v1.conformance.test.ts, #field_seal); this pins what a handed-over key reaches.
const SCOPE = 'c0ffee00-0000-4000-8000-00000000000a'
const A = 'c0ffee00-0000-4000-8000-00000000000c'
const B = 'c0ffee00-0000-4000-8000-00000000000d'
const nameCtx = (nodeId: string) => ({ purpose: 'node-name', scopeId: SCOPE, nodeId, epoch: 3, placementRevision: 0 }) as const
const metaCtx = (nodeId: string) => ({ purpose: 'node-meta', scopeId: SCOPE, nodeId }) as const
const contentKeyCtx = (nodeId: string) =>
  ({ purpose: 'content-key', scopeId: SCOPE, nodeId, epoch: 3, placementRevision: 0 }) as const
const text = (s: string) => new TextEncoder().encode(s)
const read = (b: Uint8Array) => new TextDecoder().decode(b)

// What a reporter's client hands over for node A's name, and how the moderator opens with it.
const handOver = (parentKey: Uint8Array, envelope: Uint8Array): Uint8Array => v1.fieldKeyOf(nameCtx(A), parentKey, envelope)
const openNameWith = (nodeId: string, key: Uint8Array, envelope: Uint8Array) => v1.openFieldWith(nameCtx(nodeId), key, envelope)

describe('one field, one revision, one key (ADR 0093 § 9)', () => {
  const parentKey = v1.randomBytes(32)
  const nodeKeyA = v1.randomBytes(32)
  const nameA = v1.sealField(nameCtx(A), parentKey, text('A'))
  const nameB = v1.sealField(nameCtx(B), parentKey, text('B'))
  const wrapA = v1.seal(contentKeyCtx(A), parentKey, nodeKeyA)
  const metaA = v1.sealField(metaCtx(A), nodeKeyA, text('{"v":1}'))

  it('B1: the key handed over for node A\'s name opens A\'s name (the control)', () => {
    expect(read(openNameWith(A, handOver(parentKey, nameA), nameA))).toBe('A')
  })

  it('B1: the key handed over for node A\'s name does not open sibling B\'s name', () => {
    // The control on the same predicate: B's name opens under the key derived for IT.
    expect(read(openNameWith(B, v1.fieldKeyOf(nameCtx(B), parentKey, nameB), nameB))).toBe('B')
    expect(() => openNameWith(B, handOver(parentKey, nameA), nameB)).toThrow(v1.OpenRefused)
  })

  it('B1: the key handed over for node A\'s name reaches neither A\'s node key nor A\'s node-meta', () => {
    const handed = handOver(parentKey, nameA)
    expect(v1.open(contentKeyCtx(A), parentKey, wrapA)).toEqual(nodeKeyA)
    expect(() => v1.open(contentKeyCtx(A), handed, wrapA)).toThrow(v1.OpenRefused)
    expect(read(v1.openField(metaCtx(A), nodeKeyA, metaA))).toBe('{"v":1}')
    expect(() => v1.openFieldWith(metaCtx(A), handed, metaA)).toThrow(v1.OpenRefused)
  })

  it('B2: the key of one revision of A\'s name does not open the next revision, even of the same text', () => {
    const first = v1.sealField(nameCtx(A), parentKey, text('A'))
    const second = v1.sealField(nameCtx(A), parentKey, text('A'))
    const handed = handOver(parentKey, first)
    expect(read(openNameWith(A, handed, first))).toBe('A')
    expect(read(v1.openField(nameCtx(A), parentKey, second))).toBe('A')
    expect(() => openNameWith(A, handed, second)).toThrow(v1.OpenRefused)
  })
})
