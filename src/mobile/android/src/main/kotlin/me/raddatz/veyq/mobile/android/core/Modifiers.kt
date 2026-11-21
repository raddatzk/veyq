package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/**
 * A row's or a tile's tap; where it has a menu ([onLongClick]: a level row's, core's `ShellModel.rowPlan`), a long press
 * opens it — Material's gesture for an item's actions —, read out as [onLongClickLabel].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.clickableRow(onLongClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    if (onLongClick == null) clickable(onClick = onClick)
    else combinedClickable(onClick = onClick, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick)
