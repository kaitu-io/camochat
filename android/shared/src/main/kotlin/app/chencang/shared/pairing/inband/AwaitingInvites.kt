package app.chencang.shared.pairing.inband

private const val AWAITING_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * 「待完成邀请」的唯一定义：还留在待办库里（完成的会被移除）、
 * 已经分享过或至少打开过分享面板（可能已经发出去了）、且创建于 7 天内。
 * 错粘提示、备注框显示、会话 tab 的等待行都调它。
 */
fun awaitingInvites(records: List<PendingPairingRecord>, nowMillis: Long): List<PendingPairingRecord> =
    records.filter {
        (it.lastSharedAtMillis != null || it.sheetPresentedAtMillis != null) &&
            nowMillis - it.createdAtMillis < AWAITING_WINDOW_MILLIS
    }
