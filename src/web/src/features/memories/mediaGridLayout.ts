// The justified gallery-grid geometry (cr-0059): rows of aspect-preserving tiles justified to the container width
// (Google-Photos style); the last/widow row keeps the target height instead of stretching. The per-media aspect ratio comes
// from the E2EE-sealed dimensions (operator-blind), bounded by galleryDisplayAspectRatio so one extreme photo can't dominate
// a row. The rule is Memories' Kotlin `MediaGridLayout` (ADR 0155 decision 7.3, § Memories' move), drawn through
// `@veyq/core-js/memories` and held by `contracts/memories/v1-vectors.json`: a change to it is made there, never here. This
// file keeps the web's shapes and hands the core what it asks.
import * as core from '@veyq/core-js/memories'

export type MediaGridLayoutSource = {
  id: string
  aspectRatio?: number
}

export type MediaGridLayoutInput = {
  items: MediaGridLayoutSource[]
  containerWidth: number
  targetHeight: number
  gap: number
  showWidows?: boolean
}

export type MediaGridLayoutTile = {
  id: string
  left: number
  top: number
  renderedWidth: number
  renderedHeight: number
  widow: boolean
}

export type MediaGridLayoutResult = {
  tiles: MediaGridLayoutTile[]
  height: number
}

export function calculateJustifiedMediaLayout(input: MediaGridLayoutInput): MediaGridLayoutResult {
  const layout = core.memoriesGridLayout(input.items, input.containerWidth, input.targetHeight, input.gap, input.showWidows ?? true)
  return { tiles: layout.tiles as unknown as MediaGridLayoutTile[], height: layout.height }
}

export function galleryDisplayAspectRatio(width: number | null | undefined, height: number | null | undefined): number {
  return core.memoriesDisplayAspect(width, height)
}
