package me.raddatz.veyq.mobile.media

/** The JVM is Android's core target, which links no libwebp: the Android host hands its own encoder (`Bitmap.compress`
 *  with `WEBP_LOSSY`) to `TenantSession.profile` (ADR 0148 decision 2). */
actual val coreWebpEncoder: WebpEncoder? = null
