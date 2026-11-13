package me.raddatz.veyq.core.appshell

/**
 * The music host the shell serves the music frame on (ADR 0131 decision 2), and the hosts that may frame it. The kernel
 * asks; the capability slice answers (`MusicFrameHosts`), so the shell names no register entry. Both are read on the
 * event loop, so [ancestors] is a snapshot its owner recomputes after every write that moves a tenant's domain.
 */
interface MusicFrameHost {
    /** The music host, lowercased; null while the deployment sets none — then nothing is served differently. */
    fun domain(): String?

    /** The host names whose pages may embed the frame: the instance's bootstrap domain and every tenant's. */
    fun ancestors(): List<String>

    companion object {
        /** The frame's page in the web build (`src/web/music-frame.html`), next to `index.html`. */
        const val PAGE = "music-frame.html"

        /** The frame's address on the music host; the Spotify callback is the same page under [CALLBACK_PATH], the
         *  redirect URI the Server Owner registers with Spotify. */
        const val PATH = "/music-frame"
        const val CALLBACK_PATH = "$PATH/spotify-callback"
    }
}
