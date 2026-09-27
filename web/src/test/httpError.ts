// The one fake for a failed generated-client call (inbox #171).
//
// It is the shape `shared/httpError.ts` decodes: an `Error` whose `.response` is a real `Response`. The decoder
// reads `e.response.status`, and for the server's `code` it calls `res.clone().json()` — with no `instanceof`,
// because each generated package ships its own `ResponseError`. A hand-built `{ response: { status } }` has no
// `clone()`, so it can never carry a code: a test faking `409 stale_epoch` that way exercises the plain-409 path
// and says it tested the coded one. Tests that construct a generated `ResponseError` keep doing so; that is the
// real class.

/** A thrown HTTP failure with `status`, and — when `code` is given — a JSON body `{ "code": code }`. */
export function httpError(status: number, code?: string): Error {
  const body = code === undefined ? null : JSON.stringify({ code })
  const headers = code === undefined ? undefined : { 'content-type': 'application/json' }
  return Object.assign(new Error(`http ${status}${code === undefined ? '' : ` ${code}`}`), {
    response: new Response(body, { status, headers }),
  })
}
