package me.raddatz.veyq.mobile.android.core

/**
 * Core's own kinds' pages (the folder and the kept link) and the one lookup the shell asks: a kind's page as core or an
 * application registered it into the core UI's [Registry] (ADR 0154 decision 3), never a branch on the kind here.
 */
internal object KindPages {
    /** Core's folder: its ZIP and its copy with everything under it the build exports and copies (`NodeExport`, `SubtreeCopy`). */
    private val folder = KindPage(header = { node, model ->
        HeaderContribution(
            download = { out -> model.downloadNode(node, out) },
        )
    })

    /** Core's kept link (ADR 0150 decision 6): *Open on its own page* and *Report shared content*, the share shown in place. */
    private val bookmark = KindPage(header = { node, model -> bookmarkHeader(node, model) }, body = { ref, _, model -> BookmarkScreen(ref.at, model) })

    /** Core's kinds, registered by the composition root before the applications'. */
    fun register(registry: Registry) {
        registry.kind(bookmark, me.raddatz.veyq.mobile.AppKinds.BOOKMARK_KIND)
        registry.kind(folder, "FOLDER")
    }

    fun of(kind: String): KindPage? = Registry.page(kind)
}
