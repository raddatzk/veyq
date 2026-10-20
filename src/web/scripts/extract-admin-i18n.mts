import { i18n } from '../src/i18n/index'
const WHOLE = ['common', 'tenant', 'mail', 'release', 'setup', 'terms']
const PARTIAL: Record<string, string[]> = {
  members: ['copied', 'copyLink', 'copyLinkIntro'],
  password: ['pwnedCheck', 'pwnedCheckNote', 'pwnedWarning', 'pwnedUnavailable'],
}
const out: any = {}
for (const loc of ['en', 'de']) {
  const src = (i18n.global as any).getLocaleMessage(loc)
  const o: any = {}
  for (const g of WHOLE) o[g] = src[g]
  for (const [g, keys] of Object.entries(PARTIAL)) {
    o[g] = {}
    for (const k of keys) o[g][k] = src[g][k]
  }
  out[loc] = o
}
console.log(JSON.stringify(out, null, 2))
