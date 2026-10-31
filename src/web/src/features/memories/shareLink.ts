// The copyable link is core's with the share management (ADR 0079): `core/sharing/shareLink.ts` owns it and says why it
// is split from the data layer. This path stays so the Memories share dialogs — and the `vi.mock('../shareLink')` of
// their tests, which resolves by path — keep the module they import.
export { buildShareLink, recoverShareLink } from '../../core/sharing/shareLink'
