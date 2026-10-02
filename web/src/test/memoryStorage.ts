// An in-memory `Storage` (inbox #255). vitest runs these suites in node, which has no `window`, so no
// `sessionStorage` either; a suite that reaches the session vault or the sign-out notice installs this one —
// `vi.stubGlobal('sessionStorage', memoryStorage())` — instead of hand-rolling its own, as four suites did.
export function memoryStorage(): Storage {
  const mem = new Map<string, string>()
  return {
    getItem: (k: string) => mem.get(k) ?? null,
    setItem: (k: string, v: string) => void mem.set(k, String(v)),
    removeItem: (k: string) => void mem.delete(k),
    clear: () => mem.clear(),
    key: (i: number) => [...mem.keys()][i] ?? null,
    get length() {
      return mem.size
    },
  }
}
