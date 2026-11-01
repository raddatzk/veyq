// @vitest-environment happy-dom
// The member's sibling pre-check (inbox #389) against the REAL registry (`src/test/setup.ts` registers both
// applications): each sibling folds by its own kind, only siblings of one route group meet, the renamed node never
// collides with itself, and the message names the sibling's noun from ITS kind, the neutral one for a kind nobody registered.
import { describe, it, expect } from 'vitest'
import { collidingSibling, nameTakenMessage } from './siblingCollision'
import { slugTokenFor } from './tree'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { treeNode } from '../../test/models'
import { t } from '../../test/i18n'

const node = (id: string, kind: string, name: string, parentId: string | null = 'p') => treeNode({ id, kind, name, parentId })

describe('collidingSibling', () => {
  const nodes = [
    node('f1', 'FOLDER', 'Trips'),
    node('x1', 'FILE', 'Foto.JPG'),
    node('x2', 'FILE', 'Day1.md'),
    node('r1', 'FOLDER', 'Trips', null), // the same name at the scope root: not a sibling of p's children
  ]

  it('finds the sibling under the same parent whose slug the new name takes', () => {
    expect(collidingSibling(nodes, 'p', 'FOLDER', 'trips')?.id).toBe('f1')
    expect(collidingSibling(nodes, null, 'FOLDER', 'TRIPS')?.id).toBe('r1')
    expect(collidingSibling(nodes, 'other', 'FOLDER', 'Trips')).toBeNull()
  })

  it('folds each node by its own kind: a file keeps its punctuation, a folder does not', () => {
    expect(collidingSibling(nodes, 'p', 'FILE', 'foto.jpg')?.id).toBe('x1')
    expect(collidingSibling(nodes, 'p', 'FILE', 'Day1 md')).toBeNull() // fileNameSlug keeps the space and the dot apart
    // A folder 'Foto JPG' slugs to 'foto-jpg', the file to 'foto.jpg': they coexist. A folder 'a.b' and a file 'a-b' do not.
    expect(collidingSibling(nodes, 'p', 'FOLDER', 'Foto JPG')).toBeNull()
    expect(collidingSibling([node('x3', 'FILE', 'a-b')], 'p', 'FOLDER', 'a.b')?.id).toBe('x3')
  })

  it('folds a decomposed umlaut like the composed one (inbox #365)', () => {
    expect(collidingSibling([node('m', 'FOLDER', 'Müller')], 'p', 'FOLDER', 'Müller')?.id).toBe('m')
  })

  // ADR 0080 decision 16, amended 2026-11-01: a name is unique within its route group only.
  describe('per route group', () => {
    const MEMORY = 'MEMORY'
    const POT = 'EXPENSES_POT'
    const DROPBOX = 'DEPOT_DROPBOX'

    it('a Memory and a Pot of one name under one parent do not collide, either way round', () => {
      expect(collidingSibling([node('m', MEMORY, 'Korfubanien')], 'p', POT, 'Korfubanien')).toBeNull()
      expect(collidingSibling([node('e', POT, 'Korfubanien')], 'p', MEMORY, 'korfubanien')).toBeNull()
    })

    it('a Memory or a Pot does not collide with a folder or a Depot node of its name', () => {
      const plain = [node('f', 'FOLDER', 'Korfubanien'), node('x', 'FILE', 'korfubanien'), node('b', DROPBOX, 'Korfubanien')]
      expect(collidingSibling(plain, 'p', MEMORY, 'Korfubanien')).toBeNull()
      expect(collidingSibling(plain, 'p', POT, 'Korfubanien')).toBeNull()
      expect(collidingSibling([node('e', POT, 'Korfubanien')], 'p', 'FOLDER', 'Korfubanien')).toBeNull()
    })

    it('two Pots, and a Memory and a chapter, of one name still collide', () => {
      expect(collidingSibling([node('e', POT, 'Korfubanien')], 'p', POT, 'KORFUBANIEN')?.id).toBe('e')
      expect(collidingSibling([node('m', MEMORY, 'Tag 1')], 'p', 'CHAPTER', 'tag-1')?.id).toBe('m')
    })

    it('a folder and a Depot file or drop box of one name still collide (the plain names are one group)', () => {
      expect(collidingSibling([node('x', 'FILE', 'report')], 'p', 'FOLDER', 'Report')?.id).toBe('x')
      expect(collidingSibling([node('f', 'FOLDER', 'Report')], 'p', DROPBOX, 'report')?.id).toBe('f')
    })

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

  it('a rename never collides with the node itself', () => {
    expect(collidingSibling(nodes, 'p', 'FOLDER', 'trips', 'f1')).toBeNull()
  })
})

describe('nameTakenMessage', () => {
  it('names the sibling and its kind\'s noun', () => {
    expect(nameTakenMessage(t, node('x1', 'FILE', 'Foto.JPG'))).toBe(t('node.nameTakenBy', { kind: t('depot.aFile'), name: 'Foto.JPG' }))
    expect(nameTakenMessage(t, node('f1', 'FOLDER', 'Trips'))).toBe(t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Trips' }))
    expect(nameTakenMessage(t, node('m1', 'MEMORY', 'Rom'))).toBe(t('node.nameTakenBy', { kind: t('memory.aMemory'), name: 'Rom' }))
  })

  it('a kind nobody registered is the neutral item', () => {
    expect(nameTakenMessage(t, node('u', 'SOMETHING_NEW', 'Rom'))).toBe(t('node.nameTakenBy', { kind: t('node.anItem'), name: 'Rom' }))
  })
})
