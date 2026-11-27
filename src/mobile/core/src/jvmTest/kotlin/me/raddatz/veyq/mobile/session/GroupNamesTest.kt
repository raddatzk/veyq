package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.IdentityPrivate
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unhex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A group's name as the web seals it (`scopes.ts` createGroup / renameGroup, `inviteName.ts`): a fixture the web's
 * `@veyq/crypto` wrote (a name under its value key, the key's wrap under a scope key at epoch 3, name epoch 2, and the key
 * sealed to an invitee) opens here, and what [GroupNames.seal] writes opens back. **Load-bearing:** a name opens only at its
 * own scope, name epoch and epoch — bitten by an address in the FIELD schema (red: nothing opens).
 */
class GroupNamesTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val scopeId = "c0ffee00-0000-4000-8000-0000000000a1"
    private val key = unhex("0707070707070707070707070707070707070707070707070707070707070707")
    private val encrypted = "AiODjRKoUI8n2vE3tFPQn+CYdBKelsV2P988CBGalbcpvVzOHqwmN6q4tXJr+wU="
    private val wrapped = "Aoix6eK+He0aQB6wyWsS1Xu7mtgfalOG9dyp76edCMBP1Wew94QXN8ooE6qYqPLtu4Me6kpM8DWeH2/t0w=="
    private val invitee = IdentityPrivate(unhex("a70de925263a6f6e3b30212a153c151747663151a456c4a44fc09a7c1709bb72"), unhex("53a2b36f9cf7249f790247d247dd7d79e0a90e383b5bcb1f648a5bf7b1c7f6ea"))
    private val sealedNameKey = "Ac8pFYTt+P68clCjIqNBr5A25XdIINPCivSrynn9MttUYlmUZ0Ym3LH+YtRUfKiXnGK9p2Eigws2fzDZRR6F7SjNr1apzMjfat7PpnvPBhXgNKwXjSkMdT+ooLNEal/0aRKUJkiiz9jrIt5QJXFeH/KRQlOnVwTUvlW5TjqtOSE44w7Z2G3dvPSF7VFZh3v8k4sKehVOQ4n6zlNKW+p+W1U7Dn/XuMtr8aP2sIFKXsu+63UzIqRV7LLED+SjwF328d3x5ysLl0lLzhATecn2E7VXOPeVNF1W25Rmxq9AVrSiirPdeVoLYME+DmedPxqnoD4AcV43rdYkn2mzJ6luONWvY2xSDqlz/6ctqHksku5mhKlX8/ff/V78xq3khmo33InBxY2yK//gPPIVXUHCKJadLNyhjguGL8eVsz/Gddre1DS0huPZdCcNHDF6WupfGPpG/SLktBZZQhaAN/pwRxK/D4WDg9rDcf5HvcMoWmPYaLuAOmKh4gmz32kAgcQPvI0Cvxo08y7IAW3bK5z3MY+rIk2cNZ7hGVOEXHqBA5QYMIpnGIv+hNuFMxAWeWcrn8FrpiFC/MfjKPdINwhdLZe8nCAfMsELiGUxMSTzIKByuw7lmgXmgErFY5aAwanUm2ArRqEy8JO0mqoO08do4PEh0u35jMsmPYckuVpm/sS5t3YFI3Y9GKxcQ9l9suiJT+C5/YYTTVPFoUX1pawgp8J1fZzWiQMKGC/G5iRa4an6pMZN8dauF9DDrD0P0c+qn9d0XWKOtIONn+uoej4kzYwxCVs7U6yIWtCC9sS6/ZCMU7PKvV9vb3JJdLseQcqRGDaeoF0az6viv7POqavSmmXwO9mkO5RNM4DbRkk6LeJCM2aO1bgh/Iy/vqAt3clzh5oJtzJOxSEjXHxlnxyZL0k4R++3WRDjnzKArWc5tWzx9AZYyjmFoUug7QXzukhznB9ta6ICMNLhb5fuYAwxc0pbyorPficYvF4rDLa3M9BEma2zcWmAG5pAASkz29NPd3er6YaVdei2JpJuXh8DfXHiUH1Eeojy6yeX3OyOYpQexgEsExFPNo8Bh1mnOPwAxzKT9zZz58TF2GGTzMeCh0f9lwAAdozi0NeqI3zV394bNgczxJ6SBngFJTlo372jsxY8puLjYrQ2W6HFWsRqabuNN3hH/Ph4iiXHOhKwjMroVxKWCeC/nHKbtWH/dE0LVgBzRGuPys2w35KZmST0vdKtlMU8a0XfLA6aMMcuTO3DWd1wPAnCTI8WoF8KieBqHrdwjMOCWvWK7o2sq7GNTo/FHJ+mYGyFPLH61ltCUXOHMCfH5b9MNdhb9/lOxo0SmgKHCleFBR3lIcN5Y02A1BmauqoJ0Ib+SLUKjJgHieykIHuioUIlbiRHPvnzOAll288gwN7RMht56fGjd/0B762lfT/0CGQLhsbLTD7D/h2xcHV8PQ3t8Coyv8YCw1VRbG5oNjKvSU2yCQ+THrU/63MCcCOgS7IRShru5HwbCWqA6HzXo7WGzSjfGkdf2kau45yl9KeU/5SM/Co0BGhFBI8vIqrdG92gnp0/JWY="

    @Test
    fun theWebsNameOpensAtItsOwnAddressOnly() {
        assertEquals("Gartenfreunde 🌱", GroupNames.openWith(suite, scopeId, 3, 2, key, wrapped, encrypted))
        assertNull(GroupNames.openWith(suite, scopeId, 4, 2, key, wrapped, encrypted), "another epoch")
        assertNull(GroupNames.openWith(suite, scopeId, 3, 1, key, wrapped, encrypted), "another name epoch")
        assertNull(GroupNames.openWith(suite, "c0ffee00-0000-4000-8000-0000000000a2", 3, 2, key, wrapped, encrypted), "another scope")
        assertNull(GroupNames.openWith(suite, scopeId, 3, 2, ByteArray(32) { 8 }, wrapped, encrypted), "another key")
    }

    @Test
    fun theWebsInviteeOpensTheSameName() {
        assertEquals("Gartenfreunde 🌱", GroupNames.openAsInvitee(suite, invitee, scopeId, 2, sealedNameKey, encrypted))
        assertNull(GroupNames.openAsInvitee(suite, invitee, scopeId, 1, sealedNameKey, encrypted))
        assertNull(GroupNames.openAsInvitee(suite, invitee, scopeId, 2, null, encrypted))
    }

    @Test
    fun aSealedNameOpensBackAndItsKeyGoesToAnInvitee() {
        val s = GroupNames.seal(suite, scopeId, 5, 5, key, "Chor")
        assertEquals("Chor", GroupNames.openWith(suite, scopeId, 5, 5, key, s.wrappedNameValueKey, s.encryptedName))
        val to = GroupNames.wrapToInvitee(suite, scopeId, 5, s.valueKey, b64(suite.identityPublic(invitee).serialize()))
        assertEquals("Chor", GroupNames.openAsInvitee(suite, invitee, scopeId, 5, to, s.encryptedName))
    }
}
