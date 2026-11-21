package me.raddatz.veyq.mobile.navigation

/**
 * Marks an enum whose entries are a screen's structure (ADR 0154 decision 2): what a screen offers and in which order —
 * a header's actions, a level's tools, the drawer's sections, the Settings page's sections, the sign-in's entries, a
 * row's, a tile's or a link row's menu, New's entries. The core hands a UI a list of such entries (a plan); every
 * platform draws every entry of it, in its order, in its own control, and its drawing is exhaustive over the enum: no
 * fallback arm (`else ->`, `default:`, `_ =>`), no whitelist of entries, no filter or sort over a plan's list.
 * `scripts/validate_mobile_structure.py` reads the enums from this annotation (never from a list of its own) and holds
 * those rules; the bridge generates each into the desktops' Rust and C#, so an entry added here fails their builds until
 * it is drawn.
 *
 * A platform affordance that is no product structure (a Mac's *Open in New Window*, a system share sheet's own rows) is
 * the platform's, drawn beside a plan and named in that plan's docs; it is never an entry.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Plan
