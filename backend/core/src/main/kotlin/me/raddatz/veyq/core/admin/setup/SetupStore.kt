package me.raddatz.veyq.core.admin.setup

import me.raddatz.veyq.core.admin.operator.OperatingModeHandler
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.WrapData
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ModerationOutcome
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Persistence for first-run setup. Reads the setup lock and stores the owner — the account itself is born
 *  the shared operator-blind way via [AccountStore]; setup adds only the single-owner lock. */
@ApplicationScoped
class SetupStore(
    private val accounts: AccountStore,
    private val moderation: ModerationHandler,
    private val modes: OperatingModeHandler,
) {

    fun isSetupComplete(): Boolean = ServerSetup.count() > 0

    @Transactional
    fun persistOwner(registration: OwnerRegistration): String {
        // The single-owner lock row FIRST, flushed: a second setup that read "not complete" before this one committed
        // waits on this row and then fails on its key, which the handler's replay question answers as the 409. Written
        // last, a read in between saw the first setup's commit and refused with its own answer instead — the moderation
        // epoch's "already enabled" became a 400 for the loser of two concurrent setups.
        ServerSetup().apply {
            id = ServerSetup.SINGLETON_ID
        }.persistAndFlush()

        val userId = accounts.createAccount(
            email = registration.email,
            serverRole = ServerRoles.SERVER_OWNER,
            suiteId = registration.suiteId,
            identityPublicKey = registration.identityPublicKey,
            wrappedIdentityPrivateKey = registration.wrappedIdentityPrivateKey,
            authPublicKey = registration.authPublicKey,
            secretKeyProofPublicKey = registration.secretKeyProofPublicKey,
            wraps = registration.wraps.map { WrapData(it.method, it.wrappedKey, it.kdfParams) },
        )

        // Epoch 1 of the moderation key, in the same transaction (ADR 0099 § 3; owner 2026-10-17): the setup client
        // minted it and sealed it to the identity born in this very request, so reporting is on from the first day and
        // the Server Owner is its first moderator. A bundle that is no bundle rolls the whole setup back.
        val epoch = registration.moderationEpoch
        val minted = moderation.enable(
            userId, epoch.kemPublicKey, epoch.signPublicKey, registration.identityPublicKey, epoch.sealedGrant,
        )
        if (minted !is ModerationOutcome.Ok) throw ModerationEpochInvalid()

        // The operating mode and its preset, in the same transaction (ADR 0093 § 14, ADR 0088 decision 14): no instance
        // completes setup without one, and a setup that rolls back leaves no mode and no preset switch behind.
        modes.apply(registration.operatingMode)

        return userId
    }
}
