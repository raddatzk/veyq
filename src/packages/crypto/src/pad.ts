// The one fixed-length pad of a sealed plaintext: `u32be(len) ‖ bytes ‖ zeros`, so a ciphertext's length says which
// constant (or which size class) its plaintext was padded to and nothing more. A space icon and a member's picture pad
// to one constant (`src/web/src/features/spaces/spaceIcon.ts`, ADR 0027 and 0084); an Expenses revision pads to the
// smallest of its size classes that fits (ADR 0108 decision 4: 512 B, 2 KiB, 8 KiB, 16 KiB), so an itemized bill does
// not stand out from a taxi ride. One copy, because two pads that disagreed on a byte would each open only their own.
// Pinned by `contracts/crypto/vectors/v1.json#field_seal` (`expense-entry`'s plaintext is a padded revision).

/** The u32be length header's width: a pad at length `n` carries at most `n - PAD_HEADER_LEN` bytes. */
export const PAD_HEADER_LEN = 4

/** A pad that cannot be made (`pad_too_large`) or a padded plaintext that is not one (`pad_malformed`). */
export class PadInvalid extends Error {
  constructor(readonly code: 'pad_too_large' | 'pad_malformed') {
    super(code)
    this.name = 'PadInvalid'
  }
}

/** Pad `bytes` to exactly `paddedLen`: `u32be(bytes.length) ‖ bytes ‖ zero-fill`. Throws `pad_too_large` when they do not fit. */
export function padTo(bytes: Uint8Array, paddedLen: number): Uint8Array {
  if (bytes.length > paddedLen - PAD_HEADER_LEN) throw new PadInvalid('pad_too_large')
  const out = new Uint8Array(paddedLen)
  new DataView(out.buffer).setUint32(0, bytes.length, false)
  out.set(bytes, PAD_HEADER_LEN)
  return out
}

/**
 * Pad `bytes` to the smallest of `classes` (padded lengths, ascending) that carries them. Throws `pad_too_large` when
 * not even the largest does — the caller says the plaintext is too large before anything is sealed.
 */
export function padToClass(bytes: Uint8Array, classes: readonly number[]): Uint8Array {
  // No index bound to get wrong: each class is compared with the one before it, and the first with none.
  if (classes.slice(1).some((c, i) => c <= classes[i])) throw new RangeError('size classes are not ascending')
  const fit = classes.find((c) => bytes.length <= c - PAD_HEADER_LEN)
  if (fit === undefined) throw new PadInvalid('pad_too_large')
  return padTo(bytes, fit)
}

/**
 * The bytes a padded plaintext carries. Throws `pad_malformed` on a header that overflows the pad and on a fill that is
 * not all zeros: a pad has one encoding, so two plaintexts that read alike are the same bytes.
 */
export function unpad(padded: Uint8Array): Uint8Array {
  if (padded.length < PAD_HEADER_LEN) throw new PadInvalid('pad_malformed')
  const len = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getUint32(0, false)
  if (len > padded.length - PAD_HEADER_LEN) throw new PadInvalid('pad_malformed')
  const end = PAD_HEADER_LEN + len
  for (let i = end; i < padded.length; i++) {
    if (padded[i] !== 0) throw new PadInvalid('pad_malformed')
  }
  return padded.slice(PAD_HEADER_LEN, end)
}
