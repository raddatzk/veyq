// The one way a unit test hands the code a generated `…Api` (inbox #195).
//
// admin-web's own copy of `web/src/test/apiMock.ts`, kept identical below this paragraph: the npm workspace exists
// for `@veyq/crypto` alone (ADR 0065), so a test helper is not shared between the two packages.
//
// A test that wrote `vi.mocked(xApi).mockReturnValue({ m: vi.fn().mockResolvedValue(p) } as unknown as
// ReturnType<typeof xApi>)` checked `p` against nothing: the untyped `vi.fn()` is the hole, and the cast is what lets
// a partial object in. So `ShareIdentity.sealedDisplayName` was removed from the contract and the fixture serving it
// stayed green. Here, every payload and every implementation is typed by the generated method it stands in for, and
// this file holds the only cast between a partial object and the generated class — the lint block
// `veyq/api-mocks-typed-by-the-contract` refuses that cast in a test file.
//
// Two entry points:
//   - `stubApi(xApi, { method: payload })` — each value is what the method resolves to. A literal written in place
//     gets both checks: a field the model lacks (TS2353) and a required field missing (TS2739/TS2741).
//   - `fakeApi(xApi, { method: impl })` — behaviour: a rejection, a sequence, a stateful fake. Each value is a
//     function typed by the method, or a typed `vi.fn<XApi['method']>()`. An untyped `vi.fn()` — whose `any` return
//     would switch every check off again — is refused.
// Both install the object as the factory's return value and hand back the methods as typed `Mock`s, so a later
// `.mockResolvedValueOnce(x)` is checked against the model too.
//
// LIMIT: a payload built in a variable or an un-annotated function loses the excess-property check (it is no longer
// a fresh literal); a fixture builder therefore annotates its return type with the model (styleguide §10).
import { vi, type Mock } from 'vitest'

// The generated classes' members are methods with typed parameters; `any[]` is what lets a method type match here.
/* eslint-disable @typescript-eslint/no-explicit-any */
type AnyFn = (...args: any[]) => any
type Methods<A> = { [K in keyof A as A[K] extends AnyFn ? K : never]: A[K] }
type IsAny<T> = 0 extends 1 & T ? true : false
/* eslint-enable @typescript-eslint/no-explicit-any */

/** What a generated method resolves to: the payload a stub stands in with. */
export type Resolved<M> = M extends (...args: never[]) => infer R ? Awaited<R> : never

/** The methods a mock was given, each as a `Mock` typed by the generated method. */
export type ApiMocks<A, K extends keyof Methods<A>> = { [P in K]: Mock<Extract<Methods<A>[P], AnyFn>> }

type ApiFactory = () => object

/** Make `factory()` return an object whose methods resolve the given payloads. */
export function stubApi<F extends ApiFactory, K extends keyof Methods<ReturnType<F>>>(
  factory: F,
  payloads: { [P in K]: Resolved<Methods<ReturnType<F>>[P]> },
): ApiMocks<ReturnType<F>, K> {
  const mocks = Object.fromEntries(
    Object.entries(payloads).map(([name, payload]) => [name, vi.fn().mockResolvedValue(payload)]),
  )
  return install(factory, mocks) as ApiMocks<ReturnType<F>, K>
}

/** A value `fakeApi` accepts for a method: a function of the method's type whose return type is not `any`. */
type FakeImpl<M, V> = V extends M ? (V extends (...args: never[]) => infer R ? (IsAny<R> extends true ? never : V) : never) : M

/** Make `factory()` return an object whose methods run the given implementations. */
export function fakeApi<
  F extends ApiFactory,
  I extends { [P in keyof I]: P extends keyof Methods<ReturnType<F>> ? unknown : never },
>(
  factory: F,
  impls: I & { [P in keyof I]: P extends keyof Methods<ReturnType<F>> ? FakeImpl<Methods<ReturnType<F>>[P], I[P]> : never },
): ApiMocks<ReturnType<F>, keyof I & keyof Methods<ReturnType<F>>> {
  const mocks = Object.fromEntries(
    Object.entries(impls as Record<string, (...args: never[]) => unknown>).map(([name, impl]) => [
      name,
      vi.isMockFunction(impl) ? impl : vi.fn(impl),
    ]),
  )
  return install(factory, mocks) as ApiMocks<ReturnType<F>, keyof I & keyof Methods<ReturnType<F>>>
}

function install<F extends ApiFactory>(factory: F, mocks: Record<string, unknown>): Record<string, unknown> {
  vi.mocked(factory).mockReturnValue(mocks as unknown as ReturnType<F>)
  return mocks
}
