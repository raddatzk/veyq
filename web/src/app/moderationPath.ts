// The moderation surface's one route (ADR 0099 § 6, uc.moderation.serve-as-moderator): on every tenant host, outside
// the member shell. `router.ts` registers it and `boot.ts` skips the remembered-vault restore on it, so both read the
// path from here.
export const MODERATION_PATH = '/moderation'

/** Whether `pathname` is the moderation surface's page. */
export const isModerationPath = (pathname: string): boolean =>
  pathname === MODERATION_PATH || pathname === `${MODERATION_PATH}/`
