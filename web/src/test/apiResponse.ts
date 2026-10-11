// A generated client's `…Raw` answer for a test (ADR 0081: the conditional listings read the `ETag` header, which only
// the raw response carries): the parsed body and the headers the response held. Structurally the runtime's
// `ApiResponse<T>` of every generated package, so it types as the method's resolved value.

/** What `xApi().methodRaw(…)` resolves to: `raw` (the `Response`, its headers) and `value()` (the parsed body). */
export function apiResponse<T>(value: T, headers: Record<string, string> = {}): { raw: Response; value: () => Promise<T> } {
  return { raw: new Response(null, { headers }), value: async () => value }
}
