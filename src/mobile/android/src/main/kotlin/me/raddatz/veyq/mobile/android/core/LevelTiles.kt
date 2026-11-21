package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.navigation.TileGrid
import me.raddatz.veyq.mobile.navigation.ViewMode

/**
 * How the levels are drawn here (ADR 0149 decision 8): the account's one choice, provided by the shell; a list beside an
 * open object (a wide window's list pane) stays rows.
 */
internal val LocalLevelView = androidx.compose.runtime.staticCompositionLocalOf { ViewMode.LIST }

/** The room between a level's tile grid and the window's edges, each side. */
internal val TILE_MARGIN = 16.dp

/** How many columns core's grid gives a level [width] wide (its margins taken off): two on a phone, more as it widens. */
internal fun tileColumns(width: Dp): Int = TileGrid.columns((width - TILE_MARGIN * 2).value.toDouble())

/**
 * [items] as core's tile grid (`TileGrid`, the platform's album grid): [columns] square tiles a row, core's gap across and
 * down, each row one item of the lazy list (so only the rows near the viewport are composed). [inset] is what the list's own
 * padding already keeps from the edges.
 */
internal fun <T> LazyListScope.tileRows(
    items: List<T>, columns: Int, key: (T) -> String, inset: Dp = 0.dp, tile: @Composable (T, Modifier) -> Unit,
) {
    val gap = TileGrid.GAP.dp
    items(items.chunked(columns).size, key = { "tiles:" + key(items[it * columns]) }) { i ->
        val chunk = items.subList(i * columns, minOf(items.size, (i + 1) * columns))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TILE_MARGIN - inset).padding(vertical = gap / 2),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (item in chunk) tile(item, Modifier.weight(1f))
            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * One tile of a level (ADR 0149 decision 8): its node's cover square — or its kind's symbol on the tile's ground —, the name
 * below and the row's second line under it (a Memory's date and place, a Pot's currency); a tile's tap is the row's, and
 * a pick's dimming (ADR 0138) applies as to a row. A screen reader hears the name and the line as one.
 */
@Composable
internal fun LevelTile(
    name: String, details: String?, cover: ImageBitmap?, icon: ImageVector, folder: Boolean, modifier: Modifier,
    enabled: Boolean = true, selected: Boolean = false,
    /** A long press's menu (a level row's, [LevelRowMenu]); null: none. */
    onMenu: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val menuLabel = androidx.compose.ui.res.stringResource(me.raddatz.veyq.mobile.android.R.string.node_more)
    Column(
        modifier.clip(MaterialTheme.shapes.medium).alpha(if (enabled) 1f else 0.38f)
            .let { if (selected) it.background(role(palette.accentSoft)) else it }
            .let { if (enabled) it.clickableRow(menuLabel, onMenu, onClick) else it },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                .background(role(if (folder) palette.infoSoft else palette.accentSoft)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(icon, null, Modifier.size(40.dp), tint = role(if (folder) palette.infoText else palette.accent))
        }
        Text(
            name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
        if (!details.isNullOrEmpty()) Text(
            details, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = role(palette.muted), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp),
        )
    }
}
