import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// Guard the admin console's access log (ADR 0092 § 5, owner Q29): it keeps the time, the method, the path without its
// query, the status and the size — never who asked. Not the client's address, its User-Agent or X-Forwarded-For: where
// an operator exposes the console they would name a person with no time bound, because a quiet console's log rotates
// by size almost never. The template is read as nginx reads it, so a format added beside `veyq` is held too.

const template = readFileSync(fileURLToPath(new URL('../../../nginx.conf.template', import.meta.url)), 'utf8')

/** Every `log_format <name> '<part>' '<part>' …;` in the template, its quoted parts joined; comments stripped first. */
function logFormats(text: string): Map<string, string> {
  const code = text
    .split('\n')
    .map((line) => line.replace(/#.*$/, ''))
    .join('\n')
  const formats = new Map<string, string>()
  for (const match of code.matchAll(/\blog_format\s+(\S+)\s+([^;]*);/g)) {
    const parts = [...match[2].matchAll(/'([^']*)'|"([^"]*)"/g)].map((p) => p[1] ?? p[2])
    formats.set(match[1], parts.join(''))
  }
  return formats
}

// The variables that name the reader: its address (in every spelling nginx has), what its client said it is, and the
// chain of addresses a proxy forwarded.
const NAMES_THE_READER = [
  '$remote_addr',
  '$binary_remote_addr',
  '$realip_remote_addr',
  '$http_user_agent',
  '$http_x_forwarded_for',
  '$proxy_add_x_forwarded_for',
  '$http_x_real_ip',
]

describe('the admin console access log', () => {
  const formats = logFormats(template)

  it('is the veyq format, and that format is read — the control for the check below', () => {
    expect(template).toMatch(/access_log\s+\/dev\/stdout\s+veyq;/)
    expect(formats.get('veyq')).toContain('$veyq_request_path')
    expect(formats.get('veyq')).toContain('$status')
  })

  it('names nobody: no address, no User-Agent, no X-Forwarded-For in any format', () => {
    for (const [name, format] of formats) {
      for (const variable of NAMES_THE_READER) {
        expect(format.includes(variable), `log_format ${name} carries ${variable}`).toBe(false)
      }
    }
  })

  it('reads the whole format across its quoted parts', () => {
    const parsed = logFormats("log_format x '[$time_local] '\n  # '$remote_addr'\n  '$status';")
    expect(parsed.get('x')).toBe('[$time_local] $status')
  })
})
