package me.raddatz.veyq.core.admin.setup

import me.raddatz.veyq.core.account.kitrecord.KitRecordStore
import me.raddatz.veyq.core.identity.AccountStore
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.WrapData
import me.raddatz.veyq.core.moderation.ModerationHandler
import me.raddatz.veyq.core.moderation.ModerationOutcome
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** Persistence for first-run setup. Reads the setup lock and stores the owner — the account itself is born
 *  the shared operator-blind way via [AccountStore]; setup adds only the single-owner lock. */
@ApplicationScoped
class SetupStore(
    private val accounts: AccountStore,
    private val moderation: ModerationHandler,
    private val kitRecords: KitRecordStore,
    private val switches: CapabilitySwitchStore,
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
        // The layout of the kit this setup printed, sealed under the new Master Key (ADR 0157 decision 4): stored with the
        // account it belongs to, or nothing when the client sent none.
        registration.kitRecord?.let { kitRecords.replace(userId, it) }
        // The applications the Server Owner left out (ADR 0088 decision 12): the instance switch the console writes,
        // in this transaction, never a preset beside it. No tenant exists yet, so no erasure row arises.
        registration.applicationsOff.forEach { switches.set(it, off = true) }

        return userId
    }
}
