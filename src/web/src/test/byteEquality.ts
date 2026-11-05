// Byte arrays compared as bytes in `toEqual` / `toStrictEqual`, registered for every test file by `setup.ts`.
//
// **Why.** Vitest's own equality walks a `Uint8Array` element by element through its generic, recursive `equals` (the
// array's iterator, every element a value of its own): about 1 µs a byte, so a 2.25 MiB round trip asserted with
// `toEqual(data)` cost 2.4 s on an Apple Silicon core (measured 2026-11-05) — the sealing and opening of the same bytes
// took 69 ms. Four suites that read an upload, an export or a ZIP back byte for byte spent most of their time in the
// assertion, and on GitHub's 4-vCPU runner, three workers sharing it, they passed vitest's 5 s test timeout (the
// first CI run, 2026-11-05: 7.8-11.6 s each). The assertion, not the code under test, was the cost.
//
// **What it changes.** Only how two byte arrays of the SAME constructor are found equal: same length and same bytes,
// read in one native pass. A pair of different constructors (a `Buffer` against a `Uint8Array`), any other typed array,
// and every other value fall through (`undefined`) to vitest's own rules, which decide exactly as before. A mismatch
// still fails with vitest's own diff. Proven by `byteEquality.test.ts`.
import { Buffer } from 'node:buffer'

const isBytes = (v: unknown): v is Uint8Array => ArrayBuffer.isView(v) && Object.prototype.toString.call(v) === '[object Uint8Array]'

export function byteArrayEquality(a: unknown, b: unknown): boolean | undefined {
  if (!isBytes(a) || !isBytes(b) || a.constructor !== b.constructor) return undefined
  if (a.byteLength !== b.byteLength) return false
  return Buffer.from(a.buffer, a.byteOffset, a.byteLength).equals(Buffer.from(b.buffer, b.byteOffset, b.byteLength))
}
