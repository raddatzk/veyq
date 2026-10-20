import { describe, it, expect } from 'vitest'
import { ARCHIVE_NAME_MAX_BYTES, archiveName, isArchiveName, isArchivePath } from './archiveName'

const bytes = (s: string) => new TextEncoder().encode(s).length
const one = (name: string) => archiveName(name, new Set())

describe('archiveName: the hostile cases (ADR 0082 decision 3)', () => {
  it.each([
    ['..', '_'],
    ['.', '_'],
    ['', '_'],
    ['   ', '_'],
    ['a/b', 'a_b'],
    ['a\\b', 'a_b'],
    ['CON.txt', '_CON.txt'],
    ['con', '_con'],
    ['Lpt1.tar.gz', '_Lpt1.tar.gz'],
    ['name. ', 'name'],
    ['trailing...', 'trailing'],
    ['Alfama: day 1', 'Alfama_ day 1'],
    ['what?<>|*"', 'what______'],
    ['tab\there\u0000', 'tab_here_'],
    ['.env', '.env'],
    ['Trip 2025', 'Trip 2025'],
    ['Übersicht', 'Übersicht'],
  ])('%j becomes %j', (name, safe) => {
    expect(one(name)).toBe(safe)
    expect(isArchiveName(one(name))).toBe(true)
  })

  it('a 300-character name is cut to the bound on a character boundary, keeping its extension', () => {
    const long = `${'ä'.repeat(300)}.jpg`
    const safe = one(long)
    expect(bytes(safe)).toBeLessThanOrEqual(ARCHIVE_NAME_MAX_BYTES)
    expect(safe.endsWith('.jpg')).toBe(true)
    expect(safe.startsWith('ää')).toBe(true)
    expect(isArchiveName(safe)).toBe(true)
  })

  it('two names that differ only in case are numbered, the suffix before the extension', () => {
    const taken = new Set<string>()
    expect(archiveName('IMG.jpg', taken)).toBe('IMG.jpg')
    expect(archiveName('img.JPG', taken)).toBe('img (2).JPG')
    expect(archiveName('Img.jpg', taken)).toBe('Img (3).jpg')
    expect(archiveName('notes', taken)).toBe('notes')
    expect(archiveName('NOTES', taken)).toBe('NOTES (2)')
  })

  it('names that only become equal once made safe clash too', () => {
    const taken = new Set<string>()
    expect(archiveName('a/b', taken)).toBe('a_b')
    expect(archiveName('a:b', taken)).toBe('a_b (2)')
  })

  it('a numbered name at the bound is still a name a reader accepts', () => {
    const taken = new Set<string>()
    const long = 'x'.repeat(400)
    archiveName(long, taken)
    expect(isArchiveName(archiveName(long, taken))).toBe(true)
  })

  it('NFC: the two spellings of one name are one name', () => {
    const taken = new Set<string>()
    expect(archiveName('Café', taken)).toBe('Café')
    expect(archiveName('Café', taken)).toBe('Café (2)')
  })
})

describe('isArchiveName / isArchivePath: what a reader accepts', () => {
  it('refuses what the rules never produce', () => {
    for (const bad of ['', '.', '..', 'a/b', 'CON', 'x.', 'x ', ' x', 'a:b', 'Café', 'y'.repeat(256)]) expect(isArchiveName(bad), bad).toBe(false)
  })
  it('a path is safe segments, never into the manifest directory, never absolute or upward', () => {
    expect(isArchivePath('Trip 2025/Lisbon/IMG_0412.jpg')).toBe(true)
    for (const bad of ['/abs', 'a//b', 'a/../b', '../a', '.veyq/manifest.json', '.VEYQ/x', 'a/', '']) expect(isArchivePath(bad), bad).toBe(false)
  })
})
