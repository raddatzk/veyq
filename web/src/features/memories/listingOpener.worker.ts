// The listing opener Worker (ADR 0105 decision 3, inbox #489): opens a gallery's listed seals and thumbnail frames off
// the page's main thread. Everything it does is `listingOpenerCore.ts`'s handler; this file only wires it to the
// Worker's messages. The page side, with its fall-back to the page thread, is `listingOpener.ts`.
import { createListingOpener, type OpenerRequest, type OpenerResponse } from './listingOpenerCore'

const opener = createListingOpener()
const ctx = self as unknown as { postMessage(message: OpenerResponse, transfer?: Transferable[]): void }

self.addEventListener('message', (event: MessageEvent<OpenerRequest>) => {
  const { response, transfer } = opener.handle(event.data)
  ctx.postMessage(response, transfer)
})
