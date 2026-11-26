// uc.account.unlock § One Secret-Key control — where a typed key stands, the answer the field's counter and every
// Secret-Key screen's submit read. The case that made it: the kit's first line alone (three blocks) looked like a key.
import { describe, expect, it } from 'vitest'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { secretKeyEntry, secretKeyReady } from './secretKeyEntry'

const KEY = formatSecretKey(new Uint8Array(32).fill(0x11))
const BLOCKS = KEY.split('-')
/** KEY with its last check symbol changed: 54 symbols, and a typo. */
const TYPO = KEY.slice(0, -1) + (KEY.endsWith('A') ? 'B' : 'A')

describe('secretKeyEntry', () => {
  it('counts the block being typed — the kit’s first line alone is block 3 of 9, not a key', () => {
    expect(secretKeyEntry(BLOCKS.slice(0, 3).join('-'))).toEqual({ state: 'partial', block: 3 })
    expect(secretKeyEntry(BLOCKS.slice(0, 3).join('-') + '-' + BLOCKS[3][0])).toEqual({ state: 'partial', block: 4 })
    expect(secretKeyEntry(KEY[0])).toEqual({ state: 'partial', block: 1 })
    expect(secretKeyEntry(KEY.slice(0, -1))).toEqual({ state: 'partial', block: 9 })
  })

  it('counts symbols the way the key is read — spaces, hyphens and case do not count, look-alikes do', () => {
    expect(secretKeyEntry(BLOCKS.slice(0, 2).join('  ').toLowerCase())).toEqual({ state: 'partial', block: 2 })
    expect(secretKeyEntry('oil')).toEqual({ state: 'partial', block: 1 })
  })

  it('answers every way a key can be wrong, and only a readable one is ready', () => {
    expect(secretKeyEntry('')).toEqual({ state: 'empty' })
    expect(secretKeyEntry('  - ')).toEqual({ state: 'empty' })
    expect(secretKeyEntry(BLOCKS[0] + 'U')).toEqual({ state: 'foreign' })
    expect(secretKeyEntry(KEY + '0')).toEqual({ state: 'tooLong' })
    expect(secretKeyEntry(TYPO)).toEqual({ state: 'typo' })
    expect(secretKeyEntry(KEY)).toEqual({ state: 'ready' })
    expect(secretKeyEntry(KEY.toLowerCase().replace(/-/g, ' '))).toEqual({ state: 'ready' })
  })

  it('lets a screen submit a readable key only', () => {
    expect(secretKeyReady(KEY)).toBe(true)
    for (const no of ['', BLOCKS.slice(0, 3).join('-'), TYPO, KEY + '0', KEY.replace(/.$/, 'U')]) expect(secretKeyReady(no), no).toBe(false)
  })
})
