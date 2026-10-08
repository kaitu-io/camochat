package app.chencang.android.update

import app.chencang.shared.config.AndroidRelease
import app.chencang.shared.config.LatestApk

const val SNOOZE_MS = 86_400_000L

data class Snooze(val versionCode: Int, val at: Long)

sealed interface UpdateDecision {
    data object None : UpdateDecision
    data class Optional(val apk: LatestApk) : UpdateDecision
    data class Forced(val apk: LatestApk) : UpdateDecision
}

fun decideUpdate(
    current: Int,
    release: AndroidRelease?,
    snooze: Snooze?,
    now: Long,
    ignoreSnooze: Boolean = false,
): UpdateDecision {
    if (release == null) return UpdateDecision.None
    if (current < release.minVersionCode) return UpdateDecision.Forced(release.latest)
    if (current < release.latest.versionCode) {
        val snoozed = !ignoreSnooze && snooze?.versionCode == release.latest.versionCode &&
            now - snooze.at < SNOOZE_MS
        return if (snoozed) UpdateDecision.None else UpdateDecision.Optional(release.latest)
    }
    return UpdateDecision.None
}
