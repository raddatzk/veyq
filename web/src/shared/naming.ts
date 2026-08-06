// Pure slug/naming util (ADR 0045 §3 tier-2b, cr-0175). Lifted out of the mixed `features/memory/tree.ts` so
// StrykerJS — which mutates whole files — has a pure, scorable target instead of one drowned in `contentApi`
// wiring. No I/O, no crypto keys: deterministic string→string only. `slugify`'s output seeds the blinded-token
// derivation (`tree.ts` slugTokenFor → `v1.slugToken`), so every byte here is behaviour-load-bearing — a change
// shifts sharing/routing tokens silently (not an operator-blindness breach; tokens stay blinded). Kept pure so its
// assertions can be pinned at the mutation gate (`stryker.mutate.json`).
//
// The lift is behaviour-identical to tree.ts's original with ONE deliberate simplification the gate surfaced: the
// trim regex was `/^-+|-+$/g`, but the preceding non-alnum collapse guarantees a leading/trailing dash is always
// SINGLE, so the `+` quantifiers were redundant (`^-+`≡`^-`, `-+$`≡`-$`) — two provably-equivalent mutants. Dropped
// to `/^-|-$/g`: same output on every input, and the equivalent-mutant surface is gone (naming.ts scores 100%).

const GERMAN_FOLDING: Record<string, string> = { ä: 'ae', ö: 'oe', ü: 'ue', ß: 'ss' }

/**
 * URL-safe slug: deterministic, `[a-z0-9-]` only — the same name always yields the same slug (so the same
 * blinded `slugToken`) AND it's safe as a `#fragment` path segment (cr-0031). German folding + diacritic
 * stripping keep Latin names readable ("Müller" → "mueller", "Café" → "cafe"); a name that reduces to nothing
 * (e.g. emoji/CJK-only) falls back to a deterministic short token so siblings stay distinct and addressable.
 */
export function slugify(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[äöüß]/g, (c) => GERMAN_FOLDING[c])
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '') // drop combining diacritical marks (é → e)
    .replace(/[^a-z0-9]+/g, '-') // any run of non-alphanumerics → a single dash
    .replace(/^-|-$/g, '') // trim leading/trailing dashes
  return slug || `n${fnv1a(name)}`
}

/** Tiny deterministic 32-bit hash (FNV-1a) → base36, for the empty-slug fallback only. */
function fnv1a(value: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(36)
}
