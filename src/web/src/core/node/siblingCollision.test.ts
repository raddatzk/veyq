// @vitest-environment happy-dom
// The sibling pre-check's message (inbox #389) and the slug token per route group against the REAL registry
// (`src/test/setup.ts` registers the applications): the message names the sibling's noun from ITS kind, the neutral one for a
// kind nobody registered. The check itself is the platform's, held by contracts/platform/tree/v1-vectors.json.
import { describe, it, expect } from 'vitest'
import { nameTakenMessage } from './siblingCollision'
import { slugTokenFor } from './tree'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { treeNode } from '../../test/models'
import { t } from '../../test/i18n'

const node = (id: string, kind: string, name: string, parentId: string | null = 'p') => treeNode({ id, kind, name, parentId })

describe('the slug token by route group (ADR 0080 decision 16)', () => {
  const MEMORY = 'MEMORY'
  const POT = 'EXPENSES_POT'

  it('the token a Memory and a Pot of one name get under one parent differ; its input is `<routeSlug>/<slug>`', () => {
    const key = new Uint8Array(32).fill(3)
    const memory = slugTokenFor(key, 'p', MEMORY, 'Korfubanien')
    const pot = slugTokenFor(key, 'p', POT, 'Korfubanien')
    expect(memory).not.toBe(pot)
    expect(memory).toBe(b64std(v1.slugToken(key, 'p', 'memories/korfubanien')))
    expect(pot).toBe(b64std(v1.slugToken(key, 'p', 'expenses/korfubanien')))
    // a folder keeps the bare slug, and a Depot file of its name meets it
    expect(slugTokenFor(key, 'p', 'FOLDER', 'Korfubanien')).toBe(b64std(v1.slugToken(key, 'p', 'korfubanien')))
    expect(slugTokenFor(key, 'p', 'FILE', 'korfubanien')).toBe(slugTokenFor(key, 'p', 'FOLDER', 'Korfubanien'))
  })
})

describe('nameTakenMessage', () => {
  it('names the sibling and its kind\'s noun', () => {
    expect(nameTakenMessage(t, node('x1', 'FILE', 'Foto.JPG'))).toBe(t('words.node_name_taken_by', [t('depot.aFile'), 'Foto.JPG']))
    expect(nameTakenMessage(t, node('f1', 'FOLDER', 'Trips'))).toBe(t('words.node_name_taken_by', [t('node.aFolder'), 'Trips']))
    expect(nameTakenMessage(t, node('m1', 'MEMORY', 'Rom'))).toBe(t('words.node_name_taken_by', [t('memory.aMemory'), 'Rom']))
  })

  it('a kind nobody registered is the neutral item', () => {
    expect(nameTakenMessage(t, node('u', 'SOMETHING_NEW', 'Rom'))).toBe(t('words.node_name_taken_by', [t('words.node_an_item'), 'Rom']))
  })
})
