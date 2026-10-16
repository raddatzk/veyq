package me.raddatz.veyq.core.admin.setup

import me.raddatz.veyq.core.platform.capabilities.CapabilityRegistry
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceAddresses
import me.raddatz.veyq.core.platform.capabilities.ServiceAddress
import me.raddatz.veyq.core.platform.capabilities.PlatformMajor
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.instance.InstanceStore
import me.raddatz.veyq.core.persistence.createOrConflict
import jakarta.enterprise.context.ApplicationScoped

/** Current setup state for the status endpoint. */
data class SetupState(
    val setupRequired: Boolean,
    val suites: List<String>,
    val instanceSalt: String,
    val versionMajor: Int,
    val versionBuild: Int,
    val capabilities: List<String>,
    /** The instance level's requirements, by name (ADR 0088 decision 8); a client reads them with each capability. */
    val requirements: Map<String, Int>,
    /** The configured external services' addresses (ADR 0088 decision 7), carried on their entries while active. */
    val services: Map<String, ServiceAddress>,
)

/** The owner registration payload, mapped from the API request at the resource boundary. */
data class OwnerRegistration(
    val email: String,
    val suiteId: String,
    val identityPublicKey: String,
    val wrappedIdentityPrivateKey: String,
    val authPublicKey: String,
    val secretKeyProofPublicKey: String,
    val wraps: List<WrapInput>,
)

data class WrapInput(val method: String, val wrappedKey: String, val kdfParams: String? = null)

/** Setup is already complete; a second owner cannot be bootstrapped. */
class SetupAlreadyComplete : RuntimeException("setup_already_complete")

/**
 * First-run setup use case: report status and create the first Server Owner.
 * Operator-blind — stores only ciphertext, public keys and metadata; performs no content crypto and
 * does not verify the password (there is none). See ADR 0003 and the setup-server use-case.
 */
@ApplicationScoped
class SetupHandler(
    private val store: SetupStore,
    private val instanceStore: InstanceStore,
    private val capabilities: CapabilityRegistry,
    private val services: ExternalServiceAddresses,
    config: VeyqConfig,
) {
    // The advertised version (ADR 0032). `major` is the ONLY hard gate and the platform's one major, generated from
    // the repository's VERSION (ADR 0085 decision 1) — never configured; `build` is a hand-kept diagnostic.
    private val versionMajor: Int = PlatformMajor.MAJOR
    private val versionBuild: Int = config.version().build()
    private val supportedSuites = listOf("v1")

    fun state(): SetupState = SetupState(
        setupRequired = !store.isSetupComplete(),
        suites = supportedSuites,
        instanceSalt = instanceStore.instanceSalt(),
        versionMajor = versionMajor,
        versionBuild = versionBuild,
        capabilities = capabilities.active,
        requirements = capabilities.requirements(null),
        services = services.configured(),
    )

    fun createOwner(registration: OwnerRegistration): String {
        // Field validation (non-blank, size, base64) is enforced at the boundary by @Valid on the
        // generated request model; here we only guard the single-owner invariant.
        if (store.isSetupComplete()) throw SetupAlreadyComplete()
        // Two setups that both read "not complete" both get here; the loser of the insert answers as a replay does.
        return createOrConflict(
            conflict = { if (store.isSetupComplete()) throw SetupAlreadyComplete() else null },
        ) { store.persistOwner(registration) }
    }
}
