// Copy that went wrong once and is pinned here (walk of the published Use docs, 2026-10-15): a title that counts
// ("Move 1 items"), and the German key-rotation copy that said "Raum" where the UI says "Bereich".
import { describe, expect, it } from 'vitest'
import { i18n } from './index'

const say = (locale: 'en' | 'de', key: string, count: number) => i18n.global.t(key, { count, kinds: 'x' }, { locale })

describe('counted titles agree with their count', () => {
  const titles = ['node.deleteMany.title', 'node.copy.many.title', 'node.move.many.title']

  it.each(titles)('%s: one item is singular, several plural (English)', (key) => {
    expect(say('en', key, 1)).toMatch(/ 1 item$/)
    expect(say('en', key, 3)).toMatch(/ 3 items$/)
  })

  it.each(titles)('%s: one entry is singular, several plural (German)', (key) => {
    expect(say('de', key, 1)).toMatch(/^1 Eintrag /)
    expect(say('de', key, 3)).toMatch(/^3 Einträge /)
  })

  it('the delete explanation counts too', () => {
    expect(say('en', 'node.deleteMany.copy', 1)).toContain('1 selected item moves')
    expect(say('de', 'node.deleteMany.copy', 1)).toContain('1 ausgewählter Eintrag kommt')
    expect(say('de', 'node.deleteMany.copy', 2)).toContain('2 ausgewählte Einträge kommen')
  })
})

describe('the German key-rotation copy says Bereich', () => {
  it('never says Raum / Räume', () => {
    const de = i18n.global.getLocaleMessage('de') as Record<string, Record<string, unknown>>
    const text = JSON.stringify([de.rotation, (de.members as Record<string, unknown>).compromiseRotation])
    expect(text).not.toMatch(/Raum|Räume/)
  })
})
