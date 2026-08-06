// Vitest global setup. Components now route transient feedback through `useToast` (cr-0157/ADR 0039), which
// injects PrimeVue's toast service. Install `ToastService` into the @vue/test-utils global config so every
// mounted component satisfies the `usePrimeToast()` injection without each test having to register it (tests
// that stub the shared `useToast` composable are unaffected).
import { config } from '@vue/test-utils'
import ToastService from 'primevue/toastservice'

config.global.plugins.push(ToastService)
