// A media headers' vector case's bytes (contracts/platform/media/v1-vectors.json): a list of parts - hex, a byte repeated
// `count` times, or a hex run repeated `times` times - so a large padding stays small in the file.

/** A part of a case's bytes. */
export type Part = string | { fill: number; count: number } | { repeat: string; times: number }

/** The bytes a case's parts make. */
export function assemble(parts: Part[]): Uint8Array {
  const flat = parts.flatMap((p) => (typeof p === 'object' && 'repeat' in p ? new Array<Part>(p.times).fill(p.repeat) : [p]))
  const length = flat.reduce((n, p) => n + (typeof p === 'string' ? p.length / 2 : 'count' in p ? p.count : 0), 0)
  const out = new Uint8Array(length)
  let at = 0
  for (const p of flat) {
    if (typeof p === 'string') {
      for (let i = 0; i < p.length; i += 2) out[at++] = parseInt(p.slice(i, i + 2), 16)
    } else if ('count' in p) {
      out.fill(p.fill, at, at + p.count)
      at += p.count
    }
  }
  return out
}
