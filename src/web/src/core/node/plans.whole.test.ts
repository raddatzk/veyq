// The adapter draws a plan whole (`plans.ts`, ADR 0154 decision 2): an offered action the core's plan leaves out, or an id
// the plan names that no action offers, throws - so a plan the web would draw in part is a red test, never a lost action.
// The core is stood in for here: its real plan places every action it knows (`plans.vectors.test.ts`).
import { describe, it, expect, vi } from 'vitest'

const plan = vi.hoisted(() => ({ buttons: [] as string[], menu: [] as string[], last: [] as string[] }))
vi.mock('@veyq/core-js/navigation', () => ({ headerPlan: () => plan, rowPlan: () => plan }))

const { headerPlan, rowPlan } = await import('./plans')
const a = (id: string) => ({ id, label: id, icon: 'times' as const, shown: true, onClick: () => {} })

describe('a plan drawn in part', () => {
  it('an offered action the plan leaves out throws, naming it', () => {
    Object.assign(plan, { buttons: ['share'], menu: [], last: [] })
    expect(() => headerPlan([a('share'), a('download')])).toThrow(/download/)
    expect(() => rowPlan([a('share'), a('download')])).toThrow(/download/)
  })

  it('an entry no action offers throws, naming it', () => {
    Object.assign(plan, { buttons: ['share', 'upload'], menu: [], last: [] })
    expect(() => headerPlan([a('share')])).toThrow(/upload/)
  })

  it('drawn whole: each offered action once, in the plan\'s groups', () => {
    Object.assign(plan, { buttons: ['download'], menu: ['share'], last: [] })
    const p = headerPlan([a('share'), a('download')])
    expect([p.buttons.map((x) => x.id), p.menu.map((x) => x.id)]).toEqual([['download'], ['share']])
  })
})
