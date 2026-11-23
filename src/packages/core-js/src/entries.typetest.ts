// One entry per façade (ADR 0155 decision 5): the platform's search, tree, share links, node plans, file facts, media headers and export archive share one module file, and a name of one
// façade does not resolve through the other's entry, so the web's direction guard, which reads an import's entry, judges
// every name by its owner. Checked by `pnpm run typecheck`: each `@ts-expect-error` below is itself an error the day the
// name resolves. The controls import through the owner.
// @ts-expect-error -- the tree's order is no name of the search's entry
import { orderLevel as notTheSearchs } from '@veyq/core-js/search'
// @ts-expect-error -- the search's fold is no name of the tree's entry
import { fold as notTheTrees } from '@veyq/core-js/tree'
// @ts-expect-error -- the share links' parse is no name of the tree's entry
import { parseShareLink as notTheTrees2 } from '@veyq/core-js/tree'
// @ts-expect-error -- the tree's order is no name of the share links' entry
import { orderLevel as notTheSharings } from '@veyq/core-js/sharing'
// @ts-expect-error -- the plans' header plan is no name of the tree's entry
import { headerPlan as notTheTrees3 } from '@veyq/core-js/tree'
// @ts-expect-error -- the tree's order is no name of the plans' entry
import { orderLevel as notTheNavigations } from '@veyq/core-js/navigation'
// @ts-expect-error -- the file facts' sniff is no name of the tree's entry
import { sniffImage as notTheTrees4 } from '@veyq/core-js/tree'
// @ts-expect-error -- the tree's order is no name of the file facts' entry
import { orderLevel as notTheFiles } from '@veyq/core-js/files'
// @ts-expect-error -- the media headers' image size is no name of the file facts' entry
import { imageSize as notTheFiles2 } from '@veyq/core-js/files'
// @ts-expect-error -- the file facts' sniff is no name of the media headers' entry
import { sniffImage as notTheMedias } from '@veyq/core-js/media'
// @ts-expect-error -- the export archive's writer is no name of the media headers' entry
import { zipWriter as notTheMedias2 } from '@veyq/core-js/media'
// @ts-expect-error -- the media headers' image size is no name of the export archive's entry
import { imageSize as notTheExports } from '@veyq/core-js/export'
import { zipWriter } from '@veyq/core-js/export'
import { sniffImage } from '@veyq/core-js/files'
import { imageSize } from '@veyq/core-js/media'
import { orderLevel } from '@veyq/core-js/tree'
import { headerPlan } from '@veyq/core-js/navigation'
import { fold } from '@veyq/core-js/search'
import { parseShareLink } from '@veyq/core-js/sharing'

export const crossEntryNames = [notTheSearchs, notTheTrees, notTheTrees2, notTheTrees3, notTheSharings, notTheNavigations, orderLevel, fold, parseShareLink, headerPlan, notTheTrees4, notTheFiles, sniffImage, notTheFiles2, notTheMedias, imageSize, notTheMedias2, notTheExports, zipWriter]
