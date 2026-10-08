package app.chencang.android.ui.pairing

import androidx.annotation.StringRes

/**
 * How the wizard was entered (规 W). 接收幕不分角色，所以这里只决定「从哪一幕开始、读哪条记录」：
 * 发起 / 接受 / 恢复某份邀请 / 恢复某份回应。
 */
sealed interface WizardEntry {
    data object Initiator : WizardEntry
    data object Redeemer : WizardEntry
    data class ResumeInvite(val pairingId: String) : WizardEntry
    data class ResumeResponse(val fingerprintHex: String) : WizardEntry
}

/**
 * 接收幕上方的一行提示。[isHint] = 分类提示（贴错了东西，不是故障）；否则是握手失败。
 * [contactFingerprintHex] 非空 = 「已配对过」，界面据此显示「查看联系人」。
 * [deleteInviteId] 非空 = 互发邀请：界面给出「删掉这条邀请」，删的就是这一条（向导手上的那份，或
 * 向导没拿邀请时对方回执对上的那份），删完关闭向导。
 */
data class ReceiveNotice(
    @StringRes val textRes: Int,
    val isHint: Boolean,
    val contactFingerprintHex: String? = null,
    val deleteInviteId: String? = null,
)

/**
 * The current step within the three-act pairing wizard (出示/接收/对印), shared
 * by every [WizardEntry] — which concrete stage maps to which act differs by
 * entry (see [PairingWizardViewModel]'s doc), but the stage shapes themselves
 * are entry-agnostic.
 */
sealed interface WizardStage {
    /** Transient: awaiting a suspend call (e.g. minting the invite bundle). */
    data object Working : WizardStage

    /** 「你的名字」: asked once, before the first invite is minted or the first incoming invite is
     *  accepted, while my name is blank and the prompt was never answered. What was about to happen
     *  is held by the view model and resumes on continue / skip. */
    data object AskName : WizardStage

    /** 出示幕: a wire to paste/QR/share. [isResponse] distinguishes the
     *  Initiator's invite bundle from the Redeemer's response header — they
     *  render the same shape but different copy/QR content. */
    data class Show(val wire: String, val isResponse: Boolean) : WizardStage

    /** 接收幕: awaiting the peer's pasted/scanned wire. [notice] is non-null
     *  after a [PairingWizardViewModel.submitWire] attempt that stayed here.
     *  [waitingForPeer] = I hold a pairing code and am waiting for theirs to come back. */
    data class Receive(val notice: ReceiveNotice? = null, val waitingForPeer: Boolean = false) : WizardStage {
        /** The handshake-failure text, or null when there is none (a hint is not an error). */
        @get:StringRes val error: Int? get() = notice?.takeIf { !it.isHint }?.textRes
    }

    /** 对印幕: pairing succeeded cryptographically; awaiting the user's OOB
     *  emoji-fingerprint verification (一致/不一致). */
    data class Confirm(
        val emoji: List<String>,
        val fingerprintHex: String,
        val peerUsername: String,
        val placeholderName: String,
    ) : WizardStage

    /** Terminal failure — currently only reached via
     *  [PairingWizardViewModel.rejectMismatch]. */
    data class Failed(@StringRes val messageRes: Int, val retryable: Boolean = true) : WizardStage
}

/**
 * Everything the wizard UI needs to render a frame: the stepper header
 * (index + titles) and the current stage.
 */
data class WizardUi(
    val stepIndex: Int,
    val stepTitles: List<Int>,
    val stage: WizardStage,
)
