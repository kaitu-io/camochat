package app.chencang.shared.config

import kotlinx.serialization.Serializable

/** Signed distribution config (spec 2026-10-07 §4). Only ever built by [SignedConfigCodec.decode]. */
@Serializable
data class AppConfig(
    val schema: Int,
    val seq: Long,
    val sources: List<String>,
    val relays: List<String>,
    val shareSite: String,
    val android: AndroidRelease? = null,
)

@Serializable
data class AndroidRelease(val minVersionCode: Int, val latest: LatestApk)

@Serializable
data class LatestApk(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val mirrors: List<String>,
    val notes: Map<String, String> = emptyMap(),
)
