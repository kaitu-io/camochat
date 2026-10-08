package app.chencang.android.ui.pairing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chencang.android.share.PairingShareReceiver
import app.chencang.shared.CcRepository
import app.chencang.shared.R
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.intake.IntakeFailure
import app.chencang.shared.intake.IntakeHandler
import app.chencang.shared.intake.IntakeKind
import app.chencang.shared.intake.IntakeOutcome
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.PairingCopy
import app.chencang.shared.pairing.inband.IncomingOutcome
import app.chencang.shared.pairing.inband.IncomingRejection
import app.chencang.shared.pairing.inband.PairingCoordinator
import app.chencang.shared.pairing.inband.PairingDriver
import app.chencang.shared.pairing.inband.PendingPairingRecord
import app.chencang.shared.pairing.inband.awaitingInvites
import app.chencang.shared.profile.clampMyNameInput
import app.chencang.shared.profile.normalizeMyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Three-step add-contact wizard (发配对码 / 填对方的配对码 / 核对), driven by a [WizardEntry] (规 W):
 *
 *  - **Initiator**: send(0, my code) → enter(1) → verify(2).
 *  - **Redeemer**: enter(0) → send(1, my reply) → verify(2).
 *  - **ResumeInvite**: an unshared invite with text resumes at send, anything else at enter (step 1).
 *  - **ResumeResponse**: send(the stored reply) → verify, the emoji coming from the saved contact.
 *
 * Hand-off moves on by itself: when the share sheet reports a chosen target for what is on screen
 * ([shareCompleted], marked by `PairingShareReceiver`), when the user copies it ([copied]) or taps
 * "They scanned it · Next" ([advanceFromShow]), an invite goes to "enter their code" and a reply goes
 * to verify.
 *
 * The enter step never reads the clipboard by itself: "Paste" ([pasteFromClipboard]) and a changed
 * input ([onInputChanged]) submit as soon as the text is recognized. Everything goes through
 * [IntakeHandler.classify]: an encrypted message is decrypted via [IntakeHandler.handle] and opens its
 * thread; a pairing code goes to [PairingDriver.handleIncoming] (an invite → send my reply, a reply →
 * verify); anything else stays here with a hint.
 *
 * Closing the wizard ([onCleared]) discards the invite it holds if it was never shared and its share
 * sheet was never opened ([shareSheetLaunched]), in [discardScope] (which outlives the view model); a
 * shared invite stays for the Contacts list.
 *
 * `sessionStore.remove` key choice: [RatchetSessionStore] is keyed by peer **username**, not
 * fingerprint — the coordinator writes `sessionStore.put(name = fp, ...)` where `fp` is also assigned
 * to `Contact.username`. [rejectMismatch] passes `contact.username` to [RatchetSessionStore.remove],
 * the same value [ChatRepository.clearThread] takes.
 */
class PairingWizardViewModel(
    private val entry: WizardEntry,
    private val pairing: PairingDriver,
    private val intake: IntakeHandler,
    private val repository: CcRepository,
    private val chatRepository: ChatRepository,
    private val sessionStore: RatchetSessionStore,
    private val discardScope: CoroutineScope,
    shareCompleted: Flow<String>,
    pendingInvites: Flow<List<PendingPairingRecord>>,
    private val myDisplayName: () -> String,
    private val namePromptDone: () -> Boolean,
    private val saveName: (String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
) : ViewModel() {

    /** The just-accepted / just-resumed reply's verify data, so the hand-off can assemble it. */
    private var pendingConfirm: WizardStage.Confirm? = null

    /** Fingerprint of the reply currently on the send step. */
    private var shownResponseFp: String? = null

    /** The wire of the invite this wizard holds ([inviteId]); [backToShow] re-shows it. */
    private var inviteWire: String = ""

    /** The invite to discard on close: held by this wizard and never shared. */
    private var unsharedInviteId: String? = null

    /** The share sheet was opened for my reply; [wizardRefocused] counts it as sent after a [wizardStopped]. */
    private var responseSheetOpened = false

    /** [wizardStopped] came while [responseSheetOpened]. */
    private var stoppedSinceResponseSheet = false

    /** The last text [onInputChanged] / [pasteFromClipboard] submitted, so the same text is not resubmitted. */
    private var lastAutoSubmitted: String? = null

    /** Re-entrancy guards: a second tap while the first is running is ignored. */
    private var submitting = false
    private var rejecting = false
    private var deleting = false
    private var finishing = false

    /** Serialises note writes so the last keystroke is the last write. */
    private val noteLock = Mutex()

    /** Guards [start] against re-entrant replays (`LaunchedEffect(Unit) { vm.start() }` re-fires on
     *  scan-detour return and on Activity recreation). [retry] from Failed calls [restart] directly. */
    private var started = false

    private val _ui = MutableStateFlow(
        WizardUi(stepIndex = 0, stepTitles = initialTitles(entry), stage = WizardStage.Working),
    )
    val ui: StateFlow<WizardUi> = _ui.asStateFlow()

    // After process/Activity recreation a new view model returns to the same invite by this id.
    private val _inviteId = MutableStateFlow(savedState.get<String>(SAVED_INVITE_ID))

    /** The `pairingId` of the invite this wizard currently holds (mine, unanswered);
     *  null once the current object is a reply or the pairing is done. */
    val inviteId: StateFlow<String?> = _inviteId.asStateFlow()

    /** 其它在等对方回复的邀请数（不含这屏上的这条）；> 0 时出示幕才显示备注框，好分清各条邀请。 */
    val otherAwaitingInvites: StateFlow<Int> = combine(pendingInvites, _inviteId) { records, mine ->
        awaitingInvites(records, System.currentTimeMillis()).count { it.pairingId != mine }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _canResendInvite = MutableStateFlow(false)

    /** The held invite has stored text to show again (false for one migrated from the old single slot). */
    val canResendInvite: StateFlow<Boolean> = _canResendInvite.asStateFlow()

    private val _note = MutableStateFlow("")

    /** The held invite's local-only note. */
    val note: StateFlow<String> = _note.asStateFlow()

    private val _shareTarget = MutableStateFlow<Pair<String, String>?>(null)

    /** What the send step is showing, as (kind, id) for the share callback: kind is
     *  [PairingShareReceiver.KIND_INVITE] (id = pairingId) or [PairingShareReceiver.KIND_RESPONSE]
     *  (id = fingerprint); null on every other step. */
    val shareTarget: StateFlow<Pair<String, String>?> = _shareTarget.asStateFlow()

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One-shot: the wizard should close ([deleteInvite] finished). */
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    private val _errors = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-shot user-visible messages as string resources (e.g. a failed delete). */
    val errors: SharedFlow<Int> = _errors.asSharedFlow()

    private val _actionNotices = MutableSharedFlow<ReceiveNotice>(extraBufferCapacity = 1)

    /** One-shot notices that carry an action (互发邀请 on the send step); the receive step shows them inline. */
    val actionNotices: SharedFlow<ReceiveNotice> = _actionNotices.asSharedFlow()

    private val _openThread = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-shot navigation event: the peer's username whose thread the wizard hands off into. */
    val openThread: SharedFlow<String> = _openThread.asSharedFlow()

    init {
        viewModelScope.launch {
            shareCompleted.collect { id -> if (_shareTarget.value?.second == id) handOff(markHere = false, faceToFace = false) }
        }
    }

    /** Enter the wizard. Idempotent — see [started]. */
    fun start() {
        if (started) return
        started = true
        restart()
    }

    private fun restart() {
        pendingConfirm = null
        responseSheetOpened = false
        stoppedSinceResponseSheet = false
        heldForName = null
        shownResponseFp = null
        inviteWire = ""
        lastAutoSubmitted = null
        _canResendInvite.value = false
        _note.value = ""
        submitting = false
        rejecting = false
        finishing = false
        // The initiator entry continues with the invite it held before a recreation (see [_inviteId]).
        val remembered = _inviteId.value
        setInviteId(null)
        when (val e = entry) {
            WizardEntry.Initiator -> {
                setStage(WizardStage.Working, stepIndex = 0, titles = SHOW_FIRST)
                viewModelScope.launch {
                    try {
                        val resumed = remembered?.let { withContext(ioDispatcher) { pairing.pendingInvite(it) } }
                        if (resumed != null) {
                            showHeldInvite(resumed)
                            return@launch
                        }
                        if (needsName()) {
                            askName(held = null)
                            return@launch
                        }
                        mintInvite()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        setStage(WizardStage.Failed(START_ERROR), 0, SHOW_FIRST)
                    }
                }
            }
            WizardEntry.Redeemer -> setStage(receive(), 0, RECEIVE_FIRST)
            is WizardEntry.ResumeInvite -> {
                setStage(WizardStage.Working, stepIndex = 0, titles = SHOW_FIRST)
                viewModelScope.launch {
                    try {
                        val record = withContext(ioDispatcher) { pairing.pendingInvite(e.pairingId) }
                        if (record == null) {
                            setStage(WizardStage.Failed(INVITE_GONE, retryable = false), 0, SHOW_FIRST)
                            return@launch
                        }
                        showHeldInvite(record)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        setStage(WizardStage.Failed(START_ERROR), 0, SHOW_FIRST)
                    }
                }
            }
            is WizardEntry.ResumeResponse -> {
                setStage(WizardStage.Working, stepIndex = 1, titles = RECEIVE_FIRST)
                viewModelScope.launch {
                    try {
                        val record = withContext(ioDispatcher) { pairing.pendingResponse(e.fingerprintHex) }
                        val contact = repository.contacts.first().firstOrNull { it.fingerprintHex == e.fingerprintHex }
                        if (record == null || contact == null) {
                            setStage(WizardStage.Failed(INVITE_GONE, retryable = false), 1, RECEIVE_FIRST)
                            return@launch
                        }
                        shownResponseFp = contact.fingerprintHex
                        pendingConfirm = confirmStage(contact.safetyEmoji, contact)
                        setStage(WizardStage.Show(wire = record.responseWire, isResponse = true), 1, RECEIVE_FIRST)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        setStage(WizardStage.Failed(START_ERROR), 1, RECEIVE_FIRST)
                    }
                }
            }
        }
    }

    private suspend fun mintInvite() {
        val record = withContext(ioDispatcher) { pairing.startInvite(myDisplayName()) }
        holdInvite(record)
        setStage(WizardStage.Show(wire = record.inviteWire, isResponse = false), 0, SHOW_FIRST)
    }

    /** 卡片标题用的我的昵称（空表示匿名版）。 */
    fun myName(): String = myDisplayName()

    /** My name is blank and the prompt was never answered. */
    private fun needsName() = !namePromptDone() && myDisplayName().isEmpty()

    /** What [askName] interrupted: the screen to go back to and the invite wire to process on resume
     *  (null = mint my own invite). Not saved: after process death the wizard restarts and asks again. */
    private class HeldForName(val wire: String?, val stage: WizardStage, val stepIndex: Int, val titles: List<Int>)

    private var heldForName: HeldForName? = null

    private fun askName(held: String?) {
        heldForName = HeldForName(held, _ui.value.stage, _ui.value.stepIndex, _ui.value.stepTitles)
        setStage(WizardStage.AskName, _ui.value.stepIndex, _ui.value.stepTitles)
    }

    /** "Continue" on the name prompt: [text] is normalised; one that normalises to nothing counts as skip. */
    fun submitName(text: String) = finishAskName(normalizeMyName(clampMyNameInput(text))?.ifEmpty { null })

    /** "Skip" on the name prompt. */
    fun skipName() = finishAskName(null)

    private fun finishAskName(name: String?) {
        val held = heldForName ?: return
        if (_ui.value.stage != WizardStage.AskName) return
        heldForName = null
        saveName(name)
        if (held.wire == null) {
            setStage(WizardStage.Working, held.stepIndex, held.titles)
            viewModelScope.launch {
                try {
                    mintInvite()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    setStage(WizardStage.Failed(START_ERROR), 0, SHOW_FIRST)
                }
            }
        } else {
            // Back to the screen the invite came in on, so a rejection lands there as usual.
            setStage(held.stage, held.stepIndex, held.titles)
            submitPairing(held.wire)
        }
    }

    /** Hold [record] and go to its step: never shared and has text → send, otherwise → enter (step 2). */
    private fun showHeldInvite(record: PendingPairingRecord) {
        holdInvite(record)
        if (record.lastSharedAtMillis == null && record.inviteWire.isNotEmpty()) {
            setStage(WizardStage.Show(wire = record.inviteWire, isResponse = false), 0, SHOW_FIRST)
        } else {
            setStage(receive(), 1, SHOW_FIRST)
        }
    }

    private fun setInviteId(id: String?) {
        _inviteId.value = id
        savedState[SAVED_INVITE_ID] = id
    }

    private fun holdInvite(record: PendingPairingRecord) {
        inviteWire = record.inviteWire
        setInviteId(record.pairingId)
        unsharedInviteId = record.pairingId.takeIf { record.lastSharedAtMillis == null && record.sheetPresentedAtMillis == null }
        _canResendInvite.value = record.inviteWire.isNotEmpty()
        _note.value = record.note.orEmpty()
    }

    /** The enter step; "waiting for their code" while I hold a pairing code. */
    private fun receive(notice: ReceiveNotice? = null) =
        WizardStage.Receive(notice, waitingForPeer = _inviteId.value != null)

    private fun stayWith(notice: ReceiveNotice) = setStage(receive(notice), _ui.value.stepIndex, _ui.value.stepTitles)

    /** Input changed on the enter step: submit as soon as the text is recognized (once per text). */
    fun onInputChanged(text: String) {
        if (_ui.value.stage !is WizardStage.Receive || submitting) return
        if (text == lastAutoSubmitted) return
        if (!intake.classify(text).recognized) return
        lastAutoSubmitted = text
        submitWire(text)
    }

    /** "Paste" tapped: [text] is the clipboard's content. Submits it; an unrecognized text gets a hint. */
    fun pasteFromClipboard(text: String?) {
        if (!accepts(_ui.value.stage)) return
        if (text.isNullOrBlank()) {
            reject(ReceiveNotice(R.string.intake_error_clipboard_empty, isHint = true))
            return
        }
        lastAutoSubmitted = text
        submitWire(text)
    }

    /** Stages that take an incoming wire: the enter step, or the send step of my own invite (they
     *  may have sent theirs first). The send step of a reply does not. */
    private fun accepts(stage: WizardStage) =
        stage is WizardStage.Receive || (stage is WizardStage.Show && !stage.isResponse)

    /** A wire that did not work out: a notice on the enter step; on the send step a one-shot message
     *  (the send step stays as it is). */
    private fun reject(notice: ReceiveNotice) {
        when {
            _ui.value.stage is WizardStage.Receive -> stayWith(notice)
            notice.deleteInviteId != null -> _actionNotices.tryEmit(notice)
            else -> _errors.tryEmit(notice.textRes)
        }
    }

    /** 「已是联系人」的细分 = 互发邀请（对方就是我接受过其邀请、且已在用的人——为他留的回应已随他的第一条
     *  消息清掉），给「删掉这条邀请」：向导手握自己的邀请时删这份；没握（从粘贴条进来）时删对方回执对上的那份
     *  （[IncomingRejection.AlreadyPaired.matchedPairingId]）。回应还留着 = 还在互发邀请的收敛窗口里，我手上的
     *  邀请可能正是定下来要用的那份，不能劝删。其余情形保持原提示。 */
    private suspend fun rejectionNotice(reason: IncomingRejection): ReceiveNotice {
        val fp = reason.contactFingerprintHex
        if (reason is IncomingRejection.AlreadyPaired && fp != null) {
            val held = _inviteId.value
            val inWindow = held != null && withContext(ioDispatcher) { pairing.pendingResponse(fp) } != null
            if (held != null && !inWindow) {
                val peer = repository.contacts.first().firstOrNull { it.fingerprintHex == fp }
                if (peer?.acceptedInviteDigest != null) {
                    return ReceiveNotice(R.string.pairing_mutual_invite, isHint = true, deleteInviteId = held)
                }
            } else if (reason.matchedPairingId != null) {
                return ReceiveNotice(R.string.pairing_mutual_invite, isHint = true, deleteInviteId = reason.matchedPairingId)
            }
        }
        return ReceiveNotice(reason.messageRes, isHint = true, contactFingerprintHex = fp)
    }

    /** Incoming-wire submission (manual "Next", paste, recognized input, QR scan) on the enter step or
     *  the invite send step. A second call before the first returns is ignored. */
    fun submitWire(wireText: String) {
        if (!accepts(_ui.value.stage)) return
        if (submitting) return
        val raw = wireText.trim()
        if (raw.isEmpty()) {
            reject(ReceiveNotice(PairingCopy.NOT_PAIRING_WIRE, isHint = true))
            return
        }
        when (intake.classify(raw)) {
            is IntakeKind.Message -> submitMessage(raw)
            is IntakeKind.PairingInvite -> if (needsName()) askName(held = raw) else submitPairing(raw)
            is IntakeKind.PairingResponse -> submitPairing(raw)
            IntakeKind.Incomplete, IntakeKind.LinkOnly -> reject(ReceiveNotice(R.string.intake_error_incomplete, isHint = true))
            IntakeKind.NotOurs -> reject(ReceiveNotice(PairingCopy.NOT_PAIRING_WIRE, isHint = true))
        }
    }

    /** An encrypted message pasted into the code field: decrypt it and open its thread. */
    private fun submitMessage(raw: String) {
        submitting = true
        viewModelScope.launch {
            try {
                when (val out = withContext(ioDispatcher) { intake.handle(raw) }) {
                    is IntakeOutcome.OpenThread -> _openThread.emit(out.peerUsername)
                    is IntakeOutcome.AlreadyInThread -> _openThread.emit(out.peerUsername)
                    is IntakeOutcome.Failed -> reject(ReceiveNotice(out.failure.messageRes, isHint = true))
                    is IntakeOutcome.Pairing -> handlePairing(out.wire)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "intake failed: ${t.javaClass.simpleName}")
                reject(ReceiveNotice(IntakeFailure.SAVE_FAILED.messageRes, isHint = false))
            } finally {
                submitting = false
            }
        }
    }

    private fun submitPairing(raw: String) {
        submitting = true
        viewModelScope.launch {
            try {
                handlePairing(raw)
            } finally {
                submitting = false
            }
        }
    }

    /** A pairing code or reply: the coordinator accepts / completes / rejects it. */
    private suspend fun handlePairing(raw: String) {
        try {
            when (val out = withContext(ioDispatcher) { pairing.handleIncoming(raw, myDisplayName()) }) {
                is IncomingOutcome.Accepted -> {
                    val accepted = out.outcome
                    discardHeldUnsharedInvite()
                    setInviteId(null)
                    shownResponseFp = accepted.contact.fingerprintHex
                    pendingConfirm = confirmStage(accepted.emoji, accepted.contact)
                    setStage(WizardStage.Show(wire = accepted.headerWire, isResponse = true), 1, RECEIVE_FIRST)
                }
                is IncomingOutcome.Completed -> {
                    discardHeldUnsharedInvite()
                    setInviteId(null)
                    setStage(confirmStage(out.outcome.emoji, out.outcome.contact), 2, SHOW_FIRST)
                }
                is IncomingOutcome.Rejected -> {
                    val reason = out.reason
                    if (reason is IncomingRejection.MutualInvite) {
                        // 互发邀请、定下来用对方的：回执对上的那份我的邀请已被协调器删掉，向导不再持有它。
                        releaseInvite(reason.retiredPairingId)
                        val notice = rejectionNotice(reason)
                        if (_ui.value.stage is WizardStage.Receive) stayWith(notice) else setStage(receive(notice), 1, SHOW_FIRST)
                    } else {
                        reject(rejectionNotice(reason))
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            reject(ReceiveNotice(SUBMIT_ERROR, isHint = false))
        }
    }

    /** Their wire took over the screen while my invite was never shared (nor its sheet opened): the
     *  same rule as closing the wizard — drop it now. A shared / presented invite stays; the
     *  coordinator re-checks, so a record that has since been shared survives. */
    private suspend fun discardHeldUnsharedInvite() {
        val id = unsharedInviteId ?: return
        unsharedInviteId = null
        try {
            withContext(ioDispatcher) { pairing.discardUnsharedInvite(id) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "discardUnsharedInvite failed: ${t.javaClass.simpleName}")
        }
    }

    /** "They scanned it · Next": counts as handed over — mark it shared, then move on. */
    fun advanceFromShow() = handOff(markHere = true, faceToFace = true)

    /** "Copy" tapped (the screen wrote the clipboard): mark it shared, then move on. */
    fun copied() = handOff(markHere = true, faceToFace = false)

    /** ON_STOP: the wizard left the screen. While my reply's share sheet is open this means the user
     *  went on to the chosen app (a cancelled / dismissed chooser only pauses us). */
    fun wizardStopped() {
        if (responseSheetOpened) stoppedSinceResponseSheet = true
    }

    /**
     * ON_RESUME: the wizard is in the foreground again. If my reply's share sheet was opened and the
     * wizard was stopped since, the reply is taken as sent (an OEM chooser may never deliver the
     * chosen-target callback). A resume without a stop (the chooser was cancelled) only closes the
     * sheet: the reply stays on the send step.
     */
    fun wizardRefocused() {
        if (!responseSheetOpened) return
        val sent = stoppedSinceResponseSheet
        responseSheetOpened = false
        stoppedSinceResponseSheet = false
        if (sent) handOff(markHere = true, faceToFace = false)
    }

    /**
     * What is on the send step has been handed to the peer: an invite moves on to "enter their code",
     * a reply to verify. [markHere] = this view model marks it shared (copy / scanned); the share
     * sheet path was already marked by `PairingShareReceiver`. [faceToFace] = the peer scanned it in
     * person: a reply then goes to the emoji check; a reply sent remotely (share / copy / sheet) opens
     * the thread instead and leaves the contact unverified — the check is deferred to the thread banner.
     */
    private fun handOff(markHere: Boolean, faceToFace: Boolean) {
        val stage = _ui.value.stage as? WizardStage.Show ?: return
        val target = _shareTarget.value
        // A reply already handed off (pendingConfirm cleared) must not be marked / emitted again.
        val confirm = if (stage.isResponse) (pendingConfirm ?: return) else null
        if (!stage.isResponse) unsharedInviteId = null
        if (markHere && target != null) markShared(target)
        if (confirm == null) {
            setStage(receive(), 1, SHOW_FIRST)
        } else {
            if (faceToFace) {
                setStage(confirm, 2, _ui.value.stepTitles)
            } else {
                pendingConfirm = null
                _openThread.tryEmit(confirm.peerUsername)
            }
        }
    }

    /**
     * The share sheet was opened for what the send step shows. From now on the held invite is never
     * discarded on close: an OEM chooser (MIUI) may never deliver the chosen-target callback, yet the
     * user may well have sent it — discarding it would break the peer's reply. The record remembers this
     * ([PairingDriver.markInvitePresented]), so a later wizard — reopened, or rebuilt after the process
     * died while the user was in the chat app — neither discards nor reuses it. It is not marked shared
     * here, so it stays out of 「配对中」 until the callback (or copy / "they scanned it") marks it.
     */
    fun shareSheetLaunched() {
        val stage = _ui.value.stage as? WizardStage.Show ?: return
        if (stage.isResponse) {
            responseSheetOpened = true
            stoppedSinceResponseSheet = false
            return
        }
        unsharedInviteId = null
        val id = _inviteId.value ?: return
        discardScope.launch {
            try {
                withContext(ioDispatcher) { pairing.markInvitePresented(id) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "markInvitePresented failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Runs in [discardScope] so closing the wizard right after a copy does not cancel the mark. */
    private fun markShared(target: Pair<String, String>) {
        val (kind, id) = target
        discardScope.launch {
            try {
                withContext(ioDispatcher) {
                    if (kind == PairingShareReceiver.KIND_INVITE) pairing.markInviteShared(id) else pairing.markResponseShared(id)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "markShared failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** "Send my code again" on the enter step: back to the send step for the same invite. */
    fun backToShow() {
        if (_ui.value.stage !is WizardStage.Receive) return
        if (_inviteId.value == null || !_canResendInvite.value) return
        setStage(WizardStage.Show(wire = inviteWire, isResponse = false), 0, SHOW_FIRST)
    }

    /** Note input: clamped to the 24-byte limit while typing, then persisted. Input the
     *  coordinator would refuse (control characters) is ignored — nothing is stored. */
    fun setNote(text: String) {
        val id = _inviteId.value ?: return
        val clamped = clampMyNameInput(text)
        if (normalizeMyName(clamped) == null) return
        _note.value = clamped
        viewModelScope.launch {
            noteLock.withLock {
                try {
                    withContext(ioDispatcher) { pairing.updateNote(id, clamped) }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "setNote failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /** Invite [id] is no longer on record: the wizard stops holding it (and has nothing to discard on close). */
    private fun releaseInvite(id: String) {
        if (unsharedInviteId == id) unsharedInviteId = null
        if (_inviteId.value == id) setInviteId(null)
    }

    /** Top-bar "Delete" (the held invite) or the mutual-invite action (the held invite, or the one the
     *  pasted reply matched): drop invite [pairingId] and close the wizard. If the delete fails the
     *  record (and the wizard) stay. */
    fun deleteInvite(pairingId: String) {
        val id = pairingId
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { pairing.deleteInvite(id) }
                releaseInvite(id)
                _closed.emit(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "deleteInvite failed: ${t.javaClass.simpleName}")
                _errors.emit(DELETE_FAILED)
            } finally {
                deleting = false
            }
        }
    }

    /** "They match": rename (when a name was typed), mark verified, open the thread. */
    fun confirmMatch(name: String) = finishVerify(name, verified = true)

    /** "Verify later": rename (when a name was typed), leave it unverified, open the thread. */
    fun verifyLater(name: String) = finishVerify(name, verified = false)

    private fun finishVerify(name: String, verified: Boolean) {
        val confirm = _ui.value.stage as? WizardStage.Confirm ?: return
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            try {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) repository.renameContact(confirm.fingerprintHex, trimmed)
                if (verified) repository.markVerified(confirm.fingerprintHex)
                _openThread.emit(confirm.peerUsername)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "finishVerify failed: ${t.javaClass.simpleName}")
                _errors.emit(R.string.common_action_failed)
            } finally {
                finishing = false
            }
        }
    }

    /** "They don't match" (after the confirmation dialog): a possible MITM — scrub every trace of this
     *  pairing (contact, ratchet session, chat thread) and drop into a terminal failure. */
    fun rejectMismatch() {
        val confirm = _ui.value.stage as? WizardStage.Confirm ?: return
        if (rejecting) return
        rejecting = true
        viewModelScope.launch {
            try {
                repository.removeContact(confirm.fingerprintHex)
                sessionStore.remove(confirm.peerUsername)
                // The reply record goes too (the contact's invite digest went with removeContact): a
                // mismatched peer must be able to pair again. A failed forgetPeer is only logged — a
                // leftover reply without its contact is never used again.
                runCatching { pairing.forgetPeer(confirm.fingerprintHex) }
                    .onFailure { android.util.Log.w(TAG, "rejectMismatch: forgetPeer failed: ${it.javaClass.simpleName}") }
                // Best-effort, same as AccountWiper/ContactViewModel: a leftover media dir must not block
                // reaching the terminal Failed stage on a possible-MITM path.
                runCatching { chatRepository.clearThread(confirm.peerUsername) }
                    .onFailure { android.util.Log.w(TAG, "rejectMismatch: partial media cleanup: ${it.javaClass.simpleName}") }
                setStage(WizardStage.Failed(MISMATCH_MESSAGE), _ui.value.stepIndex, _ui.value.stepTitles)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Failed midway: reset the guard so the next tap retries.
                rejecting = false
                android.util.Log.w(TAG, "rejectMismatch failed: ${t.javaClass.simpleName}")
                _errors.emit(DELETE_FAILED)
            }
        }
    }

    /** From a recoverable enter-step error: clear it and stay. From a terminal [WizardStage.Failed]:
     *  restart the whole wizard. */
    fun retry() {
        when (val stage = _ui.value.stage) {
            is WizardStage.Receive -> if (stage.error != null) {
                setStage(receive(), _ui.value.stepIndex, _ui.value.stepTitles)
            }
            is WizardStage.Failed -> restart()
            else -> Unit
        }
    }

    /** Leaving the wizard: an invite it holds that was never shared is discarded (not left as garbage).
     *  [viewModelScope] is already cancelled here, hence [discardScope]. */
    override fun onCleared() {
        val id = unsharedInviteId ?: return
        unsharedInviteId = null
        discardScope.launch {
            try {
                withContext(ioDispatcher) { pairing.discardUnsharedInvite(id) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "discardUnsharedInvite failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun confirmStage(emoji: List<String>, contact: Contact): WizardStage.Confirm =
        WizardStage.Confirm(
            emoji = emoji,
            fingerprintHex = contact.fingerprintHex,
            peerUsername = contact.username,
            placeholderName = contact.displayName,
        )

    private fun setStage(stage: WizardStage, stepIndex: Int, titles: List<Int>) {
        _shareTarget.value = when {
            stage !is WizardStage.Show -> null
            stage.isResponse -> shownResponseFp?.let { PairingShareReceiver.KIND_RESPONSE to it }
            else -> _inviteId.value?.let { PairingShareReceiver.KIND_INVITE to it }
        }
        _ui.value = WizardUi(stepIndex = stepIndex, stepTitles = titles, stage = stage)
    }

    companion object {
        private const val TAG = "Chencang"
        @StringRes private val SUBMIT_ERROR = R.string.pairing_error_submit
        @StringRes private val START_ERROR = R.string.common_action_failed
        @StringRes private val DELETE_FAILED = R.string.common_delete_failed
        @StringRes private val INVITE_GONE = R.string.pairing_error_gone
        @StringRes private val MISMATCH_MESSAGE = R.string.pairing_mismatch_deleted
        private const val SAVED_INVITE_ID = "invite_id"

        internal val SHOW_FIRST = listOf(R.string.pairing_step_send, R.string.pairing_step_enter, R.string.pairing_step_verify)
        internal val RECEIVE_FIRST = listOf(R.string.pairing_step_enter, R.string.pairing_step_send, R.string.pairing_step_verify)

        private fun initialTitles(entry: WizardEntry): List<Int> = when (entry) {
            WizardEntry.Redeemer, is WizardEntry.ResumeResponse -> RECEIVE_FIRST
            else -> SHOW_FIRST
        }
    }
}
