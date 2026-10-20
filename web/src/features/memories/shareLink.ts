// The copyable link moved to core with the share management (cr-node-key-hierarchy 5b.2): `core/sharing/shareLink.ts`
// owns it and says why it is split from the data layer. This path stays so the Memories share dialogs — and the
// `vi.mock('../shareLink')` of their tests, which resolves by path — keep the module they import.
export { buildShareLink, recoverShareLink } from '../../core/sharing/shareLink'
