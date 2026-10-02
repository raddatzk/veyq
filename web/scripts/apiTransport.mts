// How a dev seeder reaches the backend: the generated `…Api` clients, configured here, and nothing else
// (cr-api-callers-typed-by-the-contract). A seeder that hand-writes a request body is outside the contract's net:
// when login verify gained `nonce`, the app's login changed with the spec and `bootstrap:user` kept posting the
// old body until it failed its own login with 400. A generated client turns that into a typecheck error.
//
// This is the ONE file under scripts/ that may speak HTTP by hand (lint: `veyq/seeders-use-the-generated-client`),
// because a tenant-scoped call needs a transport `fetch` cannot be: the backend resolves the active tenant from the
// request's `Host`, and fetch/undici drops a hand-set `Host` header. So the tenant channel hands the generated client
// a `fetchApi` over `node:http` that connects to the backend's port and presents the tenant's domain as `Host` —
// which works for any domain, resolvable or not, and keeps an existing `.run/.env` working unchanged.
import { request as httpRequest } from 'node:http'
import type { ConfigurationParameters, FetchAPI } from '../src/api/generated/runtime'

/** The public listener: everything a member does. */
export const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
/** The admin listener: the only door the Server-Owner operations answer on — the public port 404s them (ADR 0065).
 *  8090 is its dev default (`veyq.admin-channel.port`). */
export const ADMIN_BASE = process.env.VEYQ_ADMIN_BASE ?? 'http://127.0.0.1:8090'

const bearer = (token?: string): Partial<ConfigurationParameters> => (token ? { accessToken: () => token } : {})

/** Account-level calls on the public listener, where no tenant Host is needed (setup status, login, register). */
export function publicChannel(token?: string): ConfigurationParameters {
  return { basePath: BASE, ...bearer(token) }
}

/** Server-Owner calls on the admin listener. */
export function adminChannel(token?: string): ConfigurationParameters {
  return { basePath: ADMIN_BASE, ...bearer(token) }
}

/** Tenant-scoped calls: the public listener, addressed as `host`. */
export function tenantChannel(host: string, token: string): ConfigurationParameters {
  return { basePath: BASE, fetchApi: hostPinnedFetch(host), ...bearer(token) }
}

/** Statuses whose response may not carry a body (`new Response` throws on one that does). */
const NULL_BODY = new Set([101, 204, 205, 304])

function hostPinnedFetch(host: string): FetchAPI {
  return (input, init = {}) => {
    const url = new URL(input instanceof Request ? input.url : input)
    const body = init.body ?? undefined
    if (body !== undefined && typeof body !== 'string') {
      return Promise.reject(new Error('apiTransport: only JSON (string) bodies are supported on the tenant channel'))
    }
    const headers: Record<string, string | number> = { ...Object.fromEntries(new Headers(init.headers).entries()), host }
    if (body !== undefined) headers['content-length'] = Buffer.byteLength(body)
    return new Promise<Response>((resolve, reject) => {
      const req = httpRequest(
        { host: url.hostname, port: url.port || 80, path: url.pathname + url.search, method: init.method ?? 'GET', headers },
        (res) => {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            const status = res.statusCode ?? 0
            const responseHeaders = new Headers()
            for (const [k, v] of Object.entries(res.headers)) {
              if (v !== undefined) responseHeaders.set(k, Array.isArray(v) ? v.join(', ') : v)
            }
            const buf = Buffer.concat(chunks)
            resolve(new Response(NULL_BODY.has(status) || buf.length === 0 ? null : buf, { status, headers: responseHeaders }))
          })
        },
      )
      req.on('error', reject)
      if (body !== undefined) req.write(body)
      req.end()
    })
  }
}

/** A failed generated call, as one line: the status and the body the backend answered with, or the transport error. */
export async function describeFailure(e: unknown): Promise<string> {
  const response = (e as { response?: unknown } | null)?.response
  if (response instanceof Response) return `${response.status} ${await response.text().catch(() => '')}`
  return e instanceof Error ? `${e.message}${e.cause instanceof Error ? ` (${e.cause.message})` : ''}` : String(e)
}
