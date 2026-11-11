package me.raddatz.veyq.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The members' pictures this device opened, by user id (core's `AvatarsModel`, published by `AppModel.avatars`). */
internal val LocalAvatars = compositionLocalOf<Map<String, ByteArray>> { emptyMap() }

/**
 * One person's avatar (the web's `PersonAvatar.vue`, ADR 0084): a listed member's profile picture when this device holds one,
 * else the letter of the name on the web's avatar roles (`--app-line`, `--app-control-text`) — also for a picture that
 * does not decode (never a broken image). Decorative: the name is
 * always written beside it, so it carries no description.
 */
@Composable
internal fun PersonAvatar(name: String, userId: String?, size: Dp = 32.dp) {
    val bytes = userId?.let { LocalAvatars.current[it] }
    val picture = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    val palette = LocalPalette.current
    if (picture != null) {
        Image(picture.asImageBitmap(), null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(role(palette.line)), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = role(palette.controlText), fontSize = (size.value * 0.5f).sp)
        }
    }
}
