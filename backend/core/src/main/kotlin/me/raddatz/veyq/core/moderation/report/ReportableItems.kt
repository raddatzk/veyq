package me.raddatz.veyq.core.moderation.report

/**
 * Where an application's item lies — the space and the node it sits on — so core can check a report's reach
 * (uc.moderation.report) without naming an application table (ADR 0079). One implementation per application that keeps
 * records inside nodes (Memories: a medium); [locate] answers null for an id that is not one of its items, or whose row
 * is gone. Core asks every implementation and takes the single answer; two that both claim an id are a defect, refused
 * as no item at all.
 */
interface ReportableItems {
    fun locate(itemId: String): ReportableItem?
}

/** An item's place: its space and the node it sits on. */
data class ReportableItem(val scopeId: String, val nodeId: String)
