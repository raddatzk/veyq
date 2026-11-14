// @vitest-environment happy-dom
// uc.memory.tracks (ADR 0130 decisions 1, 2, 4): what counts as a track, how its file is read, and the numbers beside it.
import { describe, expect, it } from 'vitest'
import {
  GPX_MEDIA_TYPE, GpxError, TRACKS_PER_MEMORY, TRACK_MAX_BYTES, checkGpxFile, distanceMeters, isGpxFile, isTrackMeta, parseGpx,
  simplifyLine, trackColor, trackName, trackStats, TRACK_COLORS,
} from './gpx'

const gpx = (body: string, ns = 'http://www.topografix.com/GPX/1/1') =>
  `<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="test" xmlns="${ns}">${body}</gpx>`
const pt = (lat: number, lon: number, extra = '', tag = 'trkpt') => `<${tag} lat="${lat}" lon="${lon}">${extra}</${tag}>`

describe('isTrackMeta', () => {
  it('is a track by the GPX type, or by a .gpx name under a generic type', () => {
    expect(isTrackMeta({ type: GPX_MEDIA_TYPE })).toBe(true)
    expect(isTrackMeta({ type: 'APPLICATION/GPX+XML' })).toBe(true)
    expect(isTrackMeta({ type: 'application/xml', name: 'Ride.GPX' })).toBe(true)
    expect(isTrackMeta({ type: 'application/octet-stream', name: 'ride.gpx' })).toBe(true)
    expect(isTrackMeta({ name: 'ride.gpx' })).toBe(true)
  })

  it('is no track for a photo, a medium without meta, or a .gpx name under a photo type', () => {
    expect(isTrackMeta(null)).toBe(false)
    expect(isTrackMeta(undefined)).toBe(false)
    expect(isTrackMeta({ type: 'image/jpeg', name: 'IMG_1.jpg' })).toBe(false)
    expect(isTrackMeta({ type: 'image/jpeg', name: 'trick.gpx' })).toBe(false)
    expect(isTrackMeta({ type: 'application/xml', name: 'feed.xml' })).toBe(false)
  })
})

describe('isGpxFile', () => {
  it('takes a file by its GPX type or its extension', () => {
    expect(isGpxFile({ name: 'a.gpx', type: '' })).toBe(true)
    expect(isGpxFile({ name: 'a', type: GPX_MEDIA_TYPE })).toBe(true)
    expect(isGpxFile({ name: 'a.xml', type: 'application/xml' })).toBe(false)
  })
})

describe('parseGpx', () => {
  it('reads each track segment as a line, with elevation and time', () => {
    const parsed = parseGpx(gpx(
      `<metadata><name>Meta name</name></metadata><trk><name>Morning ride</name>` +
      `<trkseg>${pt(48.1, 11.5, '<ele>520</ele><time>2026-06-01T08:00:00Z</time>')}${pt(48.2, 11.6)}</trkseg>` +
      `<trkseg>${pt(48.3, 11.7)}</trkseg></trk>`,
    ))
    expect(parsed.name).toBe('Morning ride')
    expect(parsed.segments).toHaveLength(2)
    expect(parsed.segments[0][0]).toEqual({ lat: 48.1, lng: 11.5, ele: 520, time: Date.parse('2026-06-01T08:00:00Z') })
    expect(parsed.segments[0][1]).toEqual({ lat: 48.2, lng: 11.6 })
    expect(parsed.segments[1]).toEqual([{ lat: 48.3, lng: 11.7 }])
  })

  it('reads GPX 1.0 and a file without a namespace alike', () => {
    expect(parseGpx(gpx(`<trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk>`, 'http://www.topografix.com/GPX/1/0')).segments[0]).toHaveLength(2)
    expect(parseGpx(`<gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>`).segments[0]).toHaveLength(2)
  })

  it('falls back to the metadata name, then the route name, then none', () => {
    const seg = `<trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg>`
    expect(parseGpx(gpx(`<metadata><name>From metadata</name></metadata><trk>${seg}</trk>`)).name).toBe('From metadata')
    expect(parseGpx(gpx(`<trk>${seg}</trk>`)).name).toBeNull()
  })

  it('reads the routes when the file has no track points, and never the waypoints', () => {
    const parsed = parseGpx(gpx(`<wpt lat="9" lon="9"/><rte><name>Planned</name>${pt(1, 2, '', 'rtept')}${pt(3, 4, '', 'rtept')}</rte>`))
    expect(parsed.name).toBe('Planned')
    expect(parsed.segments).toEqual([[{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }]])
  })

  it('skips a point without or out of its coordinates, and an impossible elevation, keeping the rest', () => {
    const parsed = parseGpx(gpx(
      `<trk><trkseg>${pt(1, 2)}<trkpt lat="x" lon="2"/>${pt(91, 2)}${pt(1, 181)}<trkpt lon="2"/>${pt(3, 4, '<ele>200000</ele>')}</trkseg></trk>`,
    ))
    expect(parsed.segments).toEqual([[{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }]])
  })

  it('refuses a document type declaration before parsing (no entity is expanded)', () => {
    const withEntity = `<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "y">]><gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>`
    expect(() => parseGpx(withEntity)).toThrow(GpxError)
    // the control: the same document without it reads
    expect(parseGpx(`<?xml version="1.0"?><gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>`).segments).toHaveLength(1)
  })

  it('refuses another root, and a text that is no XML', () => {
    const problem = (text: string) => {
      try {
        parseGpx(text)
        return null
      } catch (e) {
        return e instanceof GpxError ? e.problem : 'other'
      }
    }
    expect(problem(`<kml><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></kml>`)).toBe('invalid')
    expect(problem('not xml at all <<<')).toBe('invalid')
    expect(problem(gpx(`<trk><trkseg>${pt(1, 2)}</trkseg></trk>`))).toBe('tooShort')
    expect(problem(gpx(''))).toBe('tooShort')
  })
})

describe('trackName', () => {
  it("prefers the file's own name, then the file name without .gpx, then none", () => {
    expect(trackName({ name: ' Ride ' }, 'x.gpx')).toBe('Ride')
    expect(trackName({ name: null }, 'Alps day 2.GPX')).toBe('Alps day 2')
    expect(trackName(null, 'Alps.gpx')).toBe('Alps')
    expect(trackName(null, '.gpx')).toBeNull()
    expect(trackName(null, undefined)).toBeNull()
  })
})

describe('trackStats', () => {
  it('walks each segment, never the gap between two', () => {
    const a = { lat: 0, lng: 0 }
    const b = { lat: 0, lng: 0.01 }
    const far = { lat: 10, lng: 10 }
    const one = distanceMeters(a, b)
    expect(one).toBeGreaterThan(1100)
    expect(one).toBeLessThan(1120)
    expect(trackStats([[a, b], [far, far]]).distanceMeters).toBeCloseTo(one, 6)
  })

  it('times from the first to the last timestamp, and leaves the time out where the file has none', () => {
    const t0 = Date.parse('2026-06-01T08:00:00Z')
    const stats = trackStats([[{ lat: 0, lng: 0, time: t0 }, { lat: 0, lng: 0.01 }, { lat: 0, lng: 0.02, time: t0 + 3_600_000 }]])
    expect(stats.durationMs).toBe(3_600_000)
    expect(stats.startedAt).toBe(t0)
    expect(trackStats([[{ lat: 0, lng: 0 }, { lat: 0, lng: 1 }]]).durationMs).toBeUndefined()
  })

  it('counts a climb and a descent only once the elevation moved by three metres', () => {
    const at = (ele: number) => ({ lat: 0, lng: 0, ele })
    // a wobble of two metres around 100 is flat; then 100 -> 110 up and 110 -> 104 down
    const stats = trackStats([[at(100), at(102), at(100), at(101), at(110), at(104)]])
    expect(stats.ascentMeters).toBe(10)
    expect(stats.descentMeters).toBe(6)
    expect(trackStats([[{ lat: 0, lng: 0 }, { lat: 0, lng: 1 }]]).ascentMeters).toBeUndefined()
  })
})

describe('simplifyLine', () => {
  it('keeps a short line whole and picks at most max points of a long one, its ends kept', () => {
    expect(simplifyLine([1, 2, 3], 5)).toEqual([1, 2, 3])
    const long = Array.from({ length: 10_001 }, (_, i) => i)
    const drawn = simplifyLine(long, 2000)
    expect(drawn).toHaveLength(2000)
    expect(drawn[0]).toBe(0)
    expect(drawn[drawn.length - 1]).toBe(10_000)
    expect([...drawn].sort((x, y) => x - y)).toEqual(drawn)
  })
})

describe('trackColor', () => {
  it('cycles through the palette by the place in the list', () => {
    expect(trackColor(0)).toBe(TRACK_COLORS[0])
    expect(trackColor(TRACK_COLORS.length)).toBe(TRACK_COLORS[0])
    expect(trackColor(1)).not.toBe(trackColor(0))
  })
})

describe('checkGpxFile', () => {
  const file = (text: string, size = text.length) => ({ size, text: async () => text })
  const good = gpx(`<trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk>`)

  it('takes a GPX track the node has room for', async () => {
    const verdict = await checkGpxFile(file(good), TRACKS_PER_MEMORY - 1)
    expect('track' in verdict && verdict.track.segments).toHaveLength(1)
  })

  it('refuses a full node, a file too large, no GPX and too few points - each by its own reason', async () => {
    expect(await checkGpxFile(file(good), TRACKS_PER_MEMORY)).toEqual({ problem: 'tooMany' })
    expect(await checkGpxFile(file(good, TRACK_MAX_BYTES + 1), 0)).toEqual({ problem: 'tooLarge' })
    expect(await checkGpxFile(file(good, TRACK_MAX_BYTES), 0)).not.toHaveProperty('problem')
    expect(await checkGpxFile(file('<html/>'), 0)).toEqual({ problem: 'invalid' })
    expect(await checkGpxFile(file(gpx(`<trk><trkseg>${pt(1, 2)}</trkseg></trk>`)), 0)).toEqual({ problem: 'tooShort' })
  })
})
