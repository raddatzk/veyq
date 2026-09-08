import { ref, watch, onUnmounted, type Ref } from 'vue'
import { pwnedCount } from '../../shared/pwned'

/**
 * Reactive HIBP breach count for a master-password input — debounced, race-guarded, fail-open. Drives a SOFT
 * warning at password-set points; it NEVER blocks submission. The check runs client-side (see shared/pwned).
 */
export function usePwnedWarning(password: Ref<string>): { breachCount: Ref<number> } {
  const breachCount = ref(0)
  let timer: ReturnType<typeof setTimeout> | undefined
  let seq = 0
  watch(password, (pw) => {
    breachCount.value = 0
    if (timer) clearTimeout(timer)
    if (!pw) return
    const mine = ++seq
    timer = setTimeout(async () => {
      const c = await pwnedCount(pw)
      if (mine === seq) breachCount.value = c // ignore a stale in-flight result if the password changed again
    }, 600)
  })
  onUnmounted(() => {
    if (timer) clearTimeout(timer)
  })
  return { breachCount }
}
