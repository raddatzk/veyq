// Which command modifier the overview names (uc.platform.keyboard-shortcuts): ⌘ on macOS and iOS, Ctrl elsewhere. The
// handlers themselves take either (`AppTopbar`'s ⌘K is Ctrl+K too); only the name shown follows the platform. Read once,
// from `navigator.userAgentData.platform` where the browser has it, else `navigator.platform`, else the user agent.

/** The parts of `navigator` read: `userAgentData` is Chromium's (no type in the DOM lib yet). */
export interface PlatformNavigator {
  userAgentData?: { platform?: string }
  platform?: string
  userAgent?: string
}

/** Whether `nav` is an Apple platform (macOS, iOS, iPadOS - which reports itself as a Mac). */
export function isApplePlatform(nav: PlatformNavigator): boolean {
  const platform = nav.userAgentData?.platform || nav.platform || nav.userAgent || ''
  return /mac|iphone|ipad|ipod/i.test(platform)
}

let apple: boolean | undefined

/** Whether this device's command modifier is ⌘ (read once). */
export function commandIsApple(): boolean {
  apple ??= typeof navigator !== 'undefined' && isApplePlatform(navigator as PlatformNavigator)
  return apple
}

/** Forget what was read, so a test can present another platform. */
export function forgetPlatform(): void {
  apple = undefined
}
