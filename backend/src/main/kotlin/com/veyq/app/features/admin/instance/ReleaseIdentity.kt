package com.veyq.app.features.admin.instance

import com.veyq.app.shared.config.VeyqConfig
import jakarta.enterprise.context.ApplicationScoped

/**
 * The release this instance is running (ADR 0057), read once from `veyq.release.version` — which
 * `application.properties` binds to the image's `VEYQ_VERSION`, stamped by the publish from the tree's
 * `VERSION` file.
 *
 * It answers the **operator's** question, *"which bits are running, and what do I roll back to?"*, and is
 * deliberately never merged with the pre-auth `version: { major, build }` on `GET /api/setup`, which answers
 * the **client's** question, *"can these two sides talk to each other?"* (ADR 0057 §4).
 *
 * **Absence is a first-class answer.** A build the release pipeline never stamped — a local `docker build`, a
 * `quarkusDev` run, the test profile — leaves `VEYQ_VERSION` unset, which the property expression resolves to
 * an empty string. That is normalised to `null` here rather than reported as `""`: every consumer of this
 * value uses it to *choose an image to start*, so a blank-looking version is the same trap as a synthesised
 * one (uc.admin.identify-instance-release A1). It holds no per-user state — a release version is a property of
 * the software.
 */
@ApplicationScoped
class ReleaseIdentity(
    config: VeyqConfig,
) {
    /** `<major>.<minor>` for a published image; `null` for a build that carries no release stamp. */
    val version: String? = config.release().version().orElse(null)?.trim()?.takeIf { it.isNotEmpty() }
}
