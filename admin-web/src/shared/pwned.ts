// Have I Been Pwned (Pwned Passwords) k-anonymity check — a SOFT signal shown as a warning when a chosen
// master password appears in known breaches. It is NEVER a hard gate (operator-blind: choosing a weak password
// is the user's call). Privacy: the password never leaves the device — only the first 5 hex chars of its SHA-1
// are sent, directly to the HIBP range API (not via our server), which returns ~hundreds of suffixes; the
// suffix match is computed locally (k-anonymity). Fail-open: any error/network path returns 0 (no warning).

const HIBP_RANGE = 'https://api.pwnedpasswords.com/range/'

/** Uppercase hex SHA-1 of the UTF-8 string (HIBP keys passwords on SHA-1). */
export async function sha1HexUpper(s: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(s))
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('').toUpperCase()
}

/** Full uppercase SHA-1 + a HIBP range body ("SUFFIX:COUNT" per line) → the breach count (0 if absent). Pure. */
export function breachCountFromRange(hashHexUpper: string, body: string): number {
  const suffix = hashHexUpper.slice(5)
  for (const line of body.split('\n')) {
    const i = line.indexOf(':')
    if (i > 0 && line.slice(0, i).trim().toUpperCase() === suffix) return parseInt(line.slice(i + 1).trim(), 10) || 0
  }
  return 0
}

/** How many known breaches a password appears in (0 = none, or the check was unavailable). Fail-open. */
export async function pwnedCount(password: string): Promise<number> {
  if (!password) return 0
  try {
    const hash = await sha1HexUpper(password)
    const res = await fetch(HIBP_RANGE + hash.slice(0, 5), { headers: { 'Add-Padding': 'true' } })
    if (!res.ok) return 0
    return breachCountFromRange(hash, await res.text())
  } catch {
    return 0
  }
}
