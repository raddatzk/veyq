// A Secret Key a host test types into its Secret-Key field. It must read as one: every screen greys its submit until the
// field holds a readable key (`shared/ui/secretKeyEntry.ts`), so a made-up string such as 'the-secret-key' never
// reaches the submit a host test is about. Its bytes open nothing — the derivations behind it are mocked.
import { formatSecretKey } from '@veyq/crypto/secretKey'

export const TYPED_SECRET_KEY = formatSecretKey(new Uint8Array(32).fill(0x22))
