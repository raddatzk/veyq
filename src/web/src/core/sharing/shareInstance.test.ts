// Whether a bookmark's page shows its share in place (ADR 0121 decision 4): each refusal beside the pass it differs from by
// one input, so a check that stopped refusing is red on its own row.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import type { SetupStatus } from '../../api/generated'
import { EMBED_MAJORS, embeddability, olderMajorsInWindow, type EmbedInputs } from './shareInstance'

const MAJOR = 3
const setup = (major: number, ids: string[]): SetupStatus =>
  ({ setupRequired: false, version: { major, build: 1 }, capabilities: ids.map((id) => ({ id })), suites: [], instanceSalt: 'x' }) as SetupStatus
const SERVES = ['core.public-shares', 'core.public-shares.contribute', 'core.public-shares.embeddable']
const PASS: EmbedInputs = { embedding: true, setup: setup(MAJOR, SERVES), majors: [MAJOR] }

describe('embeddability', () => {
  it('embeds where this instance embeds and the share\'s instance, of this major, lets its links be shown elsewhere', () => {
    expect(embeddability(PASS)).toEqual({ embeds: true })
  })

  it('embeds an older major this client carries an adapter for (ADR 0085 decision 6\'s exception)', () => {
    expect(embeddability({ ...PASS, setup: setup(MAJOR - 1, SERVES), majors: [MAJOR, MAJOR - 1] })).toEqual({ embeds: true })
  })

  it.each<[string, Partial<EmbedInputs>, string]>([
    ['this instance switched embedding off', { embedding: false }, 'switchedOff'],
    ['no answer from the share\'s instance', { setup: null }, 'unreachable'],
    ['an answer without a version (not a veyq instance)', { setup: { capabilities: [] } as unknown as SetupStatus }, 'unreachable'],
    ['an answer without a capability list', { setup: { version: { major: MAJOR } } as unknown as SetupStatus }, 'unreachable'],
    ['a newer major', { setup: setup(MAJOR + 1, SERVES) }, 'otherVersion'],
    ['an older major', { setup: setup(MAJOR - 1, SERVES) }, 'otherVersion'],
    ['an older major past the adapters carried', { setup: setup(MAJOR - 3, SERVES), majors: [MAJOR, MAJOR - 1, MAJOR - 2] }, 'otherVersion'],
    ['no public links at all', { setup: setup(MAJOR, ['core.public-shares.embeddable']) }, 'noLinks'],
    ['links, but not to be shown elsewhere', { setup: setup(MAJOR, ['core.public-shares']) }, 'notEmbeddable'],
  ])('refuses: %s', (_name, change, reason) => {
    expect(embeddability({ ...PASS, ...change })).toEqual({ embeds: false, reason })
  })
})

describe('olderMajorsInWindow', () => {
  it.each<[number, number[]]>([
    [0, []],
    [1, []],
    [2, [1]],
    [3, [2, 1]],
    [4, [3, 2]],
  ])('a client of major %i keeps speaking %j (ADR 0085 decision 5\'s planned window, released majors only)', (major, older) => {
    expect(olderMajorsInWindow(major)).toEqual(older)
  })
})

// THE TRIPWIRE (owner 2026-11-09, ADR 0085 § Consequences): no adapter is built before a major 2 is released, and this
// is what keeps that from being forgotten. VERSION is read from disk, as `capabilities.test.ts` does, so it is red in
// the commit that moves VERSION to 2 until the embed carries major 1's guest adapter and lists it in EMBED_MAJORS. The
// native apps' shared core needs the same tripwire over its own window (inbox #436); the train's step 1 names both.
const versionFileMajor = Number(readFileSync(fileURLToPath(new URL('../../../../../VERSION', import.meta.url)), 'utf8').trim())

describe('EMBED_MAJORS', () => {
  it('speaks this client\'s own major and none newer', () => {
    expect(Number.isInteger(versionFileMajor)).toBe(true)
    expect(EMBED_MAJORS).toContain(versionFileMajor)
    expect(EMBED_MAJORS.every((m) => m <= versionFileMajor)).toBe(true)
  })

  it.each(olderMajorsInWindow(versionFileMajor).map((m) => [m]))(
    'carries the guest adapter of major %i, which the window still holds (red from VERSION 2 on until it is built)',
    (older) => {
      expect(
        EMBED_MAJORS,
        `VERSION is ${versionFileMajor}: the embed must still speak major ${older} (ADR 0085 decisions 5 and 6, ADR 0121 ` +
          `decision 4). Build its guest adapter — the guest client generated from major ${older}'s contracts and its write ` +
          `formats — and only then add ${older} to EMBED_MAJORS (infra/README § Cutting a release, step 1).`,
      ).toContain(older)
    },
  )
})
