// Whether a bookmark's page shows its share in place (ADR 0121 decision 4): each refusal beside the pass it differs from by
// one input, so a check that stopped refusing is red on its own row.
import { describe, it, expect } from 'vitest'
import type { SetupStatus } from '../../api/generated'
import { embeddability, type EmbedInputs } from './shareInstance'

const MAJOR = 3
const setup = (major: number, ids: string[]): SetupStatus =>
  ({ setupRequired: false, version: { major, build: 1 }, capabilities: ids.map((id) => ({ id })), suites: [], instanceSalt: 'x' }) as SetupStatus
const SERVES = ['core.public-shares', 'core.public-shares.contribute', 'core.public-shares.embeddable']
const PASS: EmbedInputs = { embedding: true, setup: setup(MAJOR, SERVES), major: MAJOR }

describe('embeddability', () => {
  it('embeds where this instance embeds and the share\'s instance, of this major, lets its links be shown elsewhere', () => {
    expect(embeddability(PASS)).toEqual({ embeds: true })
  })

  it.each<[string, Partial<EmbedInputs>, string]>([
    ['this instance switched embedding off', { embedding: false }, 'switchedOff'],
    ['no answer from the share\'s instance', { setup: null }, 'unreachable'],
    ['an answer without a version (not a veyq instance)', { setup: { capabilities: [] } as unknown as SetupStatus }, 'unreachable'],
    ['an answer without a capability list', { setup: { version: { major: MAJOR } } as unknown as SetupStatus }, 'unreachable'],
    ['a newer major', { setup: setup(MAJOR + 1, SERVES) }, 'otherVersion'],
    ['an older major', { setup: setup(MAJOR - 1, SERVES) }, 'otherVersion'],
    ['no public links at all', { setup: setup(MAJOR, ['core.public-shares.embeddable']) }, 'noLinks'],
    ['links, but not to be shown elsewhere', { setup: setup(MAJOR, ['core.public-shares']) }, 'notEmbeddable'],
  ])('refuses: %s', (_name, change, reason) => {
    expect(embeddability({ ...PASS, ...change })).toEqual({ embeds: false, reason })
  })
})
