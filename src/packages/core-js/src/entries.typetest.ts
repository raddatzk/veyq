// One entry per façade (ADR 0155 decision 5): the platform's search and tree share one module file, and a name of one
// façade does not resolve through the other's entry, so the web's direction guard, which reads an import's entry, judges
// every name by its owner. Checked by `pnpm run typecheck`: each `@ts-expect-error` below is itself an error the day the
// name resolves. The controls import through the owner.
// @ts-expect-error -- the tree's order is no name of the search's entry
import { orderLevel as notTheSearchs } from '@veyq/core-js/search'
// @ts-expect-error -- the search's fold is no name of the tree's entry
import { fold as notTheTrees } from '@veyq/core-js/tree'
import { orderLevel } from '@veyq/core-js/tree'
import { fold } from '@veyq/core-js/search'

export const crossEntryNames = [notTheSearchs, notTheTrees, orderLevel, fold]
