package me.raddatz.veyq.mobile.android

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.raddatz.veyq.mobile.navigation.ShareInbox

/**
 * Files dropped from another app onto this element (ADR 0138 decision 9: a drop is a share): the drag's read permission
 * asked of the activity, which holds it as a share's grant is held, and the clip's items read as a share's are
 * ([ShareIn.fromClip]) into [onDrop]. Offered for a drag of any type (decision 1: a level takes any file); where it lands
 * judges it, and a drag that carries no file (a text) is refused at the drop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.sharedFilesDrop(onDrop: (ShareInbox.Incoming) -> Unit): Modifier {
    val activity = androidx.activity.compose.LocalActivity.current
    val target = remember(activity, onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                val host = activity ?: return false
                host.requestDragAndDropPermissions(drag) ?: return false
                val incoming = ShareIn.fromClip(host, drag.clipData) ?: return false
                onDrop(incoming)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().isNotEmpty() },
        target = target,
    )
}
