// The justified gallery-grid geometry (cr-0059), ported verbatim from the design source
// (legacy/frontend-noe2ee components/media/mediaGridLayout.ts) — pure math, no DOM, no data layer. Rows of
// aspect-preserving tiles are justified to the container width (Google-Photos style); the last/widow row keeps
// the target height instead of stretching. The per-media aspect ratio comes from the E2EE-sealed dimensions
// (operator-blind), bounded by galleryDisplayAspectRatio so one extreme photo can't dominate a row.

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

const fallbackAspectRatio = 4 / 3
const minGalleryAspectRatio = 3 / 4
const maxGalleryAspectRatio = 4 / 3

function layoutRow(
  row: Required<MediaGridLayoutSource>[],
  top: number,
  containerWidth: number,
  targetHeight: number,
  gap: number,
  forceJustify: boolean,
): { tiles: MediaGridLayoutTile[]; height: number } {
  const rowGapTotal = gap * Math.max(row.length - 1, 0)
  const availableWidth = Math.max(containerWidth - rowGapTotal, 0)
  const aspectRatioSum = row.reduce((sum, item) => sum + item.aspectRatio, 0)
  const height = forceJustify && aspectRatioSum > 0 ? availableWidth / aspectRatioSum : targetHeight

  let left = 0
  const tiles = row.map((item, index) => {
    const isLast = index === row.length - 1
    const rawWidth = item.aspectRatio * height
    const renderedWidth = forceJustify && isLast ? Math.max(containerWidth - left, 0) : rawWidth

    const tile: MediaGridLayoutTile = {
      id: item.id,
      left,
      top,
      renderedWidth,
      renderedHeight: height,
      widow: !forceJustify,
    }

    left += renderedWidth + gap
    return tile
  })

  return { tiles, height }
}

export function calculateJustifiedMediaLayout(input: MediaGridLayoutInput): MediaGridLayoutResult {
  const containerWidth = Math.max(0, Math.floor(input.containerWidth))
  const targetHeight = Math.max(0, input.targetHeight)
  const gap = Math.max(0, input.gap)
  const showWidows = input.showWidows ?? true
  const items = input.items.map((item) => ({
    id: item.id,
    aspectRatio: safeAspectRatio(item.aspectRatio),
  }))

  if (items.length === 0 || containerWidth <= 0 || targetHeight <= 0) {
    return { tiles: [], height: 0 }
  }

  const targetAspectRatio = containerWidth / targetHeight
  const tiles: MediaGridLayoutTile[] = []
  let row: Required<MediaGridLayoutSource>[] = []
  let rowAspectRatio = 0
  let top = 0

  for (const item of items) {
    row.push(item)
    rowAspectRatio += item.aspectRatio

    if (rowAspectRatio >= targetAspectRatio) {
      const laidOutRow = layoutRow(row, top, containerWidth, targetHeight, gap, true)
      tiles.push(...laidOutRow.tiles)
      top += laidOutRow.height + gap
      row = []
      rowAspectRatio = 0
    }
  }

  if (row.length > 0 && showWidows) {
    const laidOutRow = layoutRow(row, top, containerWidth, targetHeight, gap, false)
    tiles.push(...laidOutRow.tiles)
    top += laidOutRow.height
  } else if (row.length === 0 && tiles.length > 0) {
    top -= gap
  }

  return {
    tiles,
    height: Math.max(top, 0),
  }
}

export function galleryDisplayAspectRatio(width: number | null | undefined, height: number | null | undefined): number {
  if (!width || !height || width <= 0 || height <= 0) {
    return fallbackAspectRatio
  }

  const aspectRatio = width / height
  if (!Number.isFinite(aspectRatio) || aspectRatio <= 0) {
    return fallbackAspectRatio
  }

  return Math.min(maxGalleryAspectRatio, Math.max(minGalleryAspectRatio, aspectRatio))
}

function safeAspectRatio(value: number | undefined): number {
  return value && Number.isFinite(value) && value > 0 ? value : fallbackAspectRatio
}
