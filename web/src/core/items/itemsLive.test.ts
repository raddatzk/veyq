// @vitest-environment happy-dom
// The platform's live refresh of an item list (inbox #410, ADR 0033): the tick (a conditional read), the delta merge and the
// cursor, then the whole loop on a fake clock - an idle 304, a delta, the reconcile after a delete, a hidden tab, backoff
// and stop. A toy "task" list stands in for an application: core names none.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createLivePoller, type LivePoller } from '../../shared/state/livePoller'
import {
  ITEMS_POLL,
  itemsPollTick,
  newestItemsCursor,
  reconcileItems,
  type ItemsPollCursor,
  type ItemsPollPage,
} from './itemsLive'

interface Task {
  id: string
  title: string
  updatedAt?: Date
}
const task = (id: string, title = id, at?: number): Task => ({ id, title, updatedAt: at === undefined ? undefined : new Date(at) })

const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('reconcileItems', () => {
  const hooks = { idOf: (t: Task) => t.id }

  it('inserts a new item, keeps the held ones and their order', () => {
    const held = [task('a'), task('b')]
    expect(reconcileItems(held, [task('c')], null, hooks).map((t) => t.id)).toEqual(['a', 'b', 'c'])
  })

  it('leaves a held item untouched without a patch, and patches the HELD object in place with one', () => {
    const a = task('a', 'old')
    const untouched = reconcileItems([a], [task('a', 'new')], null, hooks)
    expect(untouched[0]).toBe(a)
    expect(a.title).toBe('old')

    const patched = reconcileItems([a], [task('a', 'new')], null, { ...hooks, patch: (held, incoming) => (held.title = incoming.title) })
    expect(patched[0]).toBe(a) // identity kept: a view holding it stays consistent
    expect(a.title).toBe('new')
  })

  it('drops nothing without an id set, and exactly what the id set no longer lists with one', () => {
    const held = [task('a'), task('b'), task('c')]
    expect(reconcileItems(held, [], null, hooks).map((t) => t.id)).toEqual(['a', 'b', 'c']) // a delta alone never removes
    const dropped: string[] = []
    const out = reconcileItems(held, [], ['a', 'c'], { ...hooks, onDrop: (id) => dropped.push(id) })
    expect(out.map((t) => t.id)).toEqual(['a', 'c'])
    expect(dropped).toEqual(['b'])
  })

  it('catches a net-zero delete+add: the set is compared, not a count', () => {
    // b was deleted and d added: the held list and the server's both hold three items
    const out = reconcileItems([task('a'), task('b'), task('c')], [task('d')], ['a', 'c', 'd'], hooks)
    expect(out.map((t) => t.id).sort()).toEqual(['a', 'c', 'd'])
  })

  it('does not re-add a skipped item, and tells onIds what the server lists before dropping', () => {
    const seen: string[][] = []
    const out = reconcileItems([task('a')], [task('z')], ['a'], {
      ...hooks,
      skip: (id) => id === 'z', // an optimistic removal the server has not confirmed
      onIds: (listed) => seen.push([...listed]),
    })
    expect(out.map((t) => t.id)).toEqual(['a'])
    expect(seen).toEqual([['a']])
  })
})

describe('newestItemsCursor', () => {
  const cursor = (items: Task[]) => newestItemsCursor(items, (t) => t.id, (t) => t.updatedAt)

  it('is undefined for an empty list or items without an updatedAt', () => {
    expect(cursor([])).toBeUndefined()
    expect(cursor([task('a')])).toBeUndefined()
  })

  it('is the newest updatedAt as an ISO instant with its id, the larger id on a tie', () => {
    expect(cursor([task('a', 'a', 1_000), task('b', 'b', 3_000), task('c', 'c', 2_000)])).toBe('1970-01-01T00:00:03.000Z,b')
    expect(cursor([task('a', 'a', 5_000), task('b', 'b', 5_000)])).toBe('1970-01-01T00:00:05.000Z,b')
  })
})

describe('itemsPollTick', () => {
  const cursor: ItemsPollCursor<string> = { key: 'node-1', revision: 3, deleteRevision: 1, since: 'S' }
  const changed = (items: Task[], ids: string[] | null = null): ItemsPollPage<Task> => ({
    notModified: false, items, revision: 4, deleteRevision: 1, ids,
  })
  const signal = () => new AbortController().signal

  it('skips, asking the server nothing, when there is no cursor', async () => {
    const fetch = vi.fn()
    const outcome = await itemsPollTick<Task, string>({ cursor: () => null, fetch, apply: vi.fn() })(signal())
    expect(outcome).toBe('skip')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('hands the fetch the cursor, and applies nothing for a 304', async () => {
    const fetch = vi.fn(async () => ({ notModified: true }) as ItemsPollPage<Task>)
    const apply = vi.fn()
    await itemsPollTick<Task, string>({ cursor: () => cursor, fetch, apply })(signal())
    expect(fetch).toHaveBeenCalledWith(cursor, expect.anything())
    expect(apply).not.toHaveBeenCalled()
  })

  it('applies a change with the cursor it was read from', async () => {
    const page = changed([task('x')])
    const apply = vi.fn()
    await itemsPollTick<Task, string>({ cursor: () => cursor, fetch: async () => page, apply })(signal())
    expect(apply).toHaveBeenCalledWith(page, cursor, expect.anything())
  })

  it('applies nothing when the run was stopped while the read was in flight', async () => {
    const run = new AbortController()
    const apply = vi.fn()
    const tick = itemsPollTick<Task, string>({
      cursor: () => cursor,
      fetch: async () => {
        run.abort()
        return changed([task('x')])
      },
      apply,
    })
    await tick(run.signal)
    expect(apply).not.toHaveBeenCalled()
  })

  it('applies nothing for a view that moved on, and does for the one still open', async () => {
    let open = 'node-1'
    const apply = vi.fn()
    const tick = itemsPollTick<Task, string>({
      cursor: () => cursor,
      fetch: async () => {
        open = 'node-2' // navigated away while the request was out
        return changed([task('x')])
      },
      isCurrent: (key) => key === open,
      apply,
    })
    await tick(signal())
    expect(apply).not.toHaveBeenCalled()
    open = 'node-1'
    const still = itemsPollTick<Task, string>({ cursor: () => cursor, fetch: async () => changed([]), isCurrent: (k) => k === open, apply })
    await still(signal())
    expect(apply).toHaveBeenCalledTimes(1)
  })

  it('lets a failed read reject, so the loop backs off', async () => {
    const tick = itemsPollTick<Task, string>({ cursor: () => cursor, fetch: async () => Promise.reject(new Error('down')), apply: vi.fn() })
    await expect(tick(signal())).rejects.toThrow('down')
  })
})

describe('an item list on the live loop', () => {
  // The server's side, as the toy application sees it: a revision per change, a delete revision per removal.
  let server: { revision: number; deleteRevision: number; rows: Task[] }
  let held: Task[]
  let revision: number
  let deleteRevision: number
  let reads: ItemsPollCursor<string>[]
  let poller: LivePoller
  const OPTIONS = { intervalMs: 1_000, maxBackoffMs: 8_000, jitter: 0, minGapMs: 500 }

  const read = async (c: ItemsPollCursor<string>): Promise<ItemsPollPage<Task>> => {
    reads.push(c)
    if (c.revision === server.revision) return { notModified: true } // the conditional GET: If-None-Match = the revision
    const after = c.since
    const delta = server.rows.filter((t) => !after || t.updatedAt!.toISOString() > after.split(',')[0])
    return {
      notModified: false,
      items: delta,
      revision: server.revision,
      deleteRevision: server.deleteRevision,
      ids: c.deleteRevision < server.deleteRevision ? server.rows.map((t) => t.id) : null, // only when a delete happened since
    }
  }
  const tick = itemsPollTick<Task, string>({
    cursor: () => ({
      key: 'node-1',
      revision,
      deleteRevision,
      since: newestItemsCursor(held, (t) => t.id, (t) => t.updatedAt),
    }),
    fetch: (c) => read(c),
    apply: (change) => {
      held = reconcileItems(held, change.items, change.ids, { idOf: (t) => t.id })
      revision = change.revision
      deleteRevision = change.deleteRevision
    },
  })

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    server = { revision: 2, deleteRevision: 0, rows: [task('a', 'a', 1_000), task('b', 'b', 2_000)] }
    held = [...server.rows]
    revision = 2
    deleteRevision = 0
    reads = []
    poller = createLivePoller(OPTIONS)
  })
  afterEach(() => {
    poller.stop()
    vi.useRealTimers()
  })

  it('an idle list is a 304 every interval and changes nothing', async () => {
    poller.start(tick)
    await vi.advanceTimersByTimeAsync(3_000)
    expect(reads).toHaveLength(3)
    expect(reads.every((c) => c.revision === 2)).toBe(true) // the revision it holds is what it echoes
    expect(held.map((t) => t.id)).toEqual(['a', 'b'])
  })

  it('an add arrives as a delta past the cursor, and the next poll is idle again', async () => {
    poller.start(tick)
    server.rows.push(task('c', 'c', 3_000))
    server.revision = 3
    await vi.advanceTimersByTimeAsync(1_000)
    expect(held.map((t) => t.id)).toEqual(['a', 'b', 'c'])
    expect(reads[0].since).toBe('1970-01-01T00:00:02.000Z,b') // it asked past the newest it held
    expect(revision).toBe(3)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(reads[1].revision).toBe(3) // 304 now
    expect(held).toHaveLength(3)
  })

  it('a delete is reconciled by the id set, and only the poll after a delete carries it', async () => {
    poller.start(tick)
    server.rows = [server.rows[1]] // a is removed: both counters move
    server.revision = 3
    server.deleteRevision = 1
    await vi.advanceTimersByTimeAsync(1_000)
    expect(held.map((t) => t.id)).toEqual(['b'])
    expect(deleteRevision).toBe(1) // the next poll echoes it, so no id set rides again
    server.rows.push(task('d', 'd', 4_000)) // a pure add afterwards
    server.revision = 4
    await vi.advanceTimersByTimeAsync(1_000)
    expect(reads[1].deleteRevision).toBe(1)
    expect(held.map((t) => t.id)).toEqual(['b', 'd'])
  })

  it('asks nothing while the tab is hidden and catches up at once when it shows again', async () => {
    poller.start(tick)
    setVisibility('hidden')
    server.rows.push(task('c', 'c', 3_000))
    server.revision = 3
    await vi.advanceTimersByTimeAsync(60_000)
    expect(reads).toHaveLength(0)
    setVisibility('visible')
    await vi.advanceTimersByTimeAsync(0)
    expect(reads).toHaveLength(1)
    expect(held.map((t) => t.id)).toEqual(['a', 'b', 'c'])
  })

  it('backs off after a failed read and is back to the interval after a good one', async () => {
    let failing = true
    const flaky = itemsPollTick<Task, string>({
      cursor: () => ({ key: 'node-1', revision, deleteRevision }),
      fetch: async (c) => {
        reads.push(c)
        if (failing) throw new Error('503')
        return { notModified: true }
      },
      apply: vi.fn(),
    })
    poller.start(flaky)
    await vi.advanceTimersByTimeAsync(1_000) // failure 1 -> the next in 2 s
    expect(reads).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1_999)
    expect(reads).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1) // failure 2 -> the next in 4 s
    expect(reads).toHaveLength(2)
    failing = false
    await vi.advanceTimersByTimeAsync(4_000)
    expect(reads).toHaveLength(3) // the good one
    await vi.advanceTimersByTimeAsync(1_000)
    expect(reads).toHaveLength(4) // plain interval again
  })

  it('stop() ends the loop, and a read still in flight applies nothing', async () => {
    const apply = vi.fn()
    let release: (p: ItemsPollPage<Task>) => void = () => {}
    const slow = itemsPollTick<Task, string>({
      cursor: () => ({ key: 'node-1', revision, deleteRevision }),
      fetch: () => new Promise((resolve) => (release = resolve)),
      apply,
    })
    poller.start(slow)
    await vi.advanceTimersByTimeAsync(1_000) // the read is out
    poller.stop()
    release({ notModified: false, items: [task('z')], revision: 9, deleteRevision: 0, ids: null })
    await vi.advanceTimersByTimeAsync(10_000)
    expect(apply).not.toHaveBeenCalled()
    expect(reads).toHaveLength(0)
  })

  it('runs at the platform cadence by default: 20 s, backing off to 2 min', () => {
    expect(ITEMS_POLL.intervalMs).toBe(20_000)
    expect(ITEMS_POLL.maxBackoffMs).toBe(120_000)
    expect(ITEMS_POLL.jitter).toBeGreaterThan(0)
  })
})
