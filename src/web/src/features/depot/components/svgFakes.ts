// Test kit for the SVG view's tests (inbox #1059): a decoded picture without a browser behind it. happy-dom draws no canvas,
// so `paint` does what the real one does to the canvas' size - the real `planRaster`, so the cap is the shipped one - and
// records each call; the real painting is `svgRaster.test.ts`'s and the browser pass's (ADR 0155 § The file facts' slice D14).
// Not a test file (no test cases); imported by the *.test.ts that mount the viewer.
import { vi, type Mock } from 'vitest'
import { planRaster, type SvgPicture, type SvgRaster, type SvgRegion } from '../svgRaster'

export type FakePaint = { canvas: HTMLCanvasElement; region: SvgRegion; scale: number; deviceRatio: number; raster: SvgRaster }
export type FakePicture = SvgPicture & { paints: FakePaint[]; paint: Mock<SvgPicture['paint']>; close: Mock<SvgPicture['close']> }

export function fakePicture(width = 200, height = 100): FakePicture {
  const paints: FakePaint[] = []
  return {
    width,
    height,
    paints,
    paint: vi.fn<SvgPicture['paint']>((canvas, region, scale, deviceRatio) => {
      const raster = planRaster(region, deviceRatio)
      canvas.width = raster.width
      canvas.height = raster.height
      paints.push({ canvas, region, scale, deviceRatio, raster })
      return raster
    }),
    close: vi.fn<SvgPicture['close']>(),
  }
}
