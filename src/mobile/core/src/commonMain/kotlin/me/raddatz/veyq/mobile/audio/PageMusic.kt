package me.raddatz.veyq.mobile.audio

/**
 * What a page plays its music with (ADR 0142 decision 1): the account's one [player]; [untitled] names a track by its
 * number. Handed by the host to a Memory's gallery, a member's or a link guest's (`GuestAccess.music`).
 */
class PageMusic(val player: MusicPlayer, val untitled: (Int) -> String)
