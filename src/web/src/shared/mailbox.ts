// An address as the contracts' `format: email` reads it (RFC 5321's `Mailbox`), checked before a form sends it — the
// server's `Mailbox` validator (`core/validation/Mailbox.kt`) is the authority and refuses what this lets through as
// `400 invalid_request`. This reads the common shapes the same way: a dot-string local part of at most 64 characters, `@`,
// and a domain of letter-digit-hyphen labels (each 1–63 characters, a letter or digit at both ends; at most 253 in all),
// ASCII only, 254 at most (the contracts' `maxLength`). A quoted local part or an address-literal domain is left to the
// server: this check never refuses an address the server would take for those shapes.

const MAX_ADDRESS = 254
const MAX_LOCAL = 64
const MAX_DOMAIN = 253
const ATOM = /^[A-Za-z0-9!#$%&'*+\-/=?^_`{|}~]+$/
const LABEL = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/

/** Whether [value] reads as a mailbox the contract would take (see the file header for what is left to the server). */
export function isMailbox(value: string): boolean {
  if (value.length === 0 || value.length > MAX_ADDRESS || !/^[\x20-\x7e]+$/.test(value)) return false
  if (value.startsWith('"')) return /^".*"@.+$/.test(value) // a quoted local part: the server's to read
  const at = value.indexOf('@')
  if (at <= 0 || at > MAX_LOCAL) return false
  const local = value.slice(0, at)
  const domain = value.slice(at + 1)
  if (!local.split('.').every((atom) => ATOM.test(atom))) return false
  if (domain.startsWith('[') && domain.endsWith(']') && domain.length > 2) return true // an address literal: the server's to read
  return domain.length >= 1 && domain.length <= MAX_DOMAIN && domain.split('.').every((label) => LABEL.test(label))
}
