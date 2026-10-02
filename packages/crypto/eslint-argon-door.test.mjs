// The spellings `veyq/argon-through-the-worker` claims to refuse, and the ones it must leave alone. Which FILES it
// applies to is each config's `files`/`ignores` (bitten live against the real configs, not here).
import { RuleTester } from 'eslint'
import { describe, it } from 'vitest'

import { plugin } from './eslint-argon-door.js'

RuleTester.describe = describe
RuleTester.it = it
RuleTester.itOnly = it.only

const rule = plugin.rules['argon-through-the-worker']
// Each entry point's message names its own async twin — spelled out here, independent of the rule's own table.
const TWIN = {
  deriveAuthKeypair: 'deriveAuthKeypairAsync',
  kekPasswordSecret: 'unlockKekAsync',
  kekDevicePassword: 'deviceKekAsync',
  kekRecovery: 'recoveryKekAsync',
  sharesPwToken: 'sharesPwTokenAsync',
}
const direct = (name) => ({ messageId: 'direct', data: { name, twin: TWIN[name] } })
const tester = new RuleTester({ languageOptions: { ecmaVersion: 'latest', sourceType: 'module' } })

tester.run('argon-through-the-worker', rule, {
  valid: [
    // the door itself, and everything else the v1 module offers
    "import { deviceKekAsync } from '@veyq/crypto/argonWorker'; await deviceKekAsync(f, pw, salt)",
    "import * as v1 from '@veyq/crypto/v1'; v1.seal(ctx, key, data); v1.randomBytes(16); v1.ARGON",
    "import { randomBytes, encodeKdfParams } from '@veyq/crypto/v1'",
    // a same-named member of something that is NOT the v1 namespace
    "import * as other from './other'; other.kekRecovery(sk, salt)",
    'const v1 = makeSomething(); v1.kekRecovery(sk, salt)',
    // a different module that happens to end in v1-ish names
    "import { kekRecovery } from './v1Helpers'",
    "export { seal } from '@veyq/crypto/v1'",
  ],
  invalid: [
    { code: "import * as v1 from '@veyq/crypto/v1'; v1.kekDevicePassword(f, pw, salt)", errors: [direct('kekDevicePassword')] },
    { code: "import * as v1 from './v1'; v1.kekPasswordSecret(sk, pw, salt)", errors: [direct('kekPasswordSecret')] },
    { code: "import * as crypto from '../v1.ts'; crypto['sharesPwToken'](pw, salt)", errors: [direct('sharesPwToken')] },
    { code: "import * as v1 from '@veyq/crypto/v1'; const { kekRecovery } = v1", errors: [direct('kekRecovery')] },
    { code: "import { deriveAuthKeypair } from '@veyq/crypto/v1'", errors: [direct('deriveAuthKeypair')] },
    { code: "import { sharesPwToken as t, seal } from './v1'", errors: [direct('sharesPwToken')] },
    { code: "export { kekRecovery } from '@veyq/crypto/v1'", errors: [direct('kekRecovery')] },
    { code: "export * from '@veyq/crypto/v1'", errors: [{ messageId: 'reexport' }] },
  ],
})
