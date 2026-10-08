package app.chencang.android.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chencang.shared.CcRepository
import app.chencang.shared.chat.ChatRepository
import app.chencang.shared.crypto.RatchetSessionStore
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Data layer for the contact page (spec 5.6). Resolves the persisted [Contact] by
 * [fingerprintHex] and carries four actions:
 *
 *  - [confirm] marks the contact verified (`repository.markVerified`). The page only shows
 *    the verify button while the contact is unverified; the call itself is idempotent.
 *  - [rename] changes the local nickname. It is a receiving-side label only and never goes online.
 *  - [clearMessages] clears the local thread of this one peer; the contact and session keys stay.
 *  - [deleteContact] is a destructive chain, in the same order as
 *    [app.chencang.android.ui.pairing.PairingWizardViewModel.rejectMismatch]: delete the contact
 *    from the repository, delete the session by username from the session store, forget any
 *    retained pairing response, then clear the local thread. [deleted] fires only after
 *    everything has been persisted. Here the user deletes from the contact page on purpose; it is
 *    not the pairing flow's mismatch branch, but the same cleanup logic runs.
 */
class ContactViewModel(
    private val fingerprintHex: String,
    private val repository: CcRepository,
    private val chatRepository: ChatRepository,
    private val sessionStore: RatchetSessionStore,
    /** 清掉为这位联系人留着的「可重发的回应暗号」（生产 = `PairingCoordinator.forgetPeer`）。 */
    private val forgetPeer: suspend (fingerprintHex: String) -> Unit = {},
    /** 回应记录表的订阅（生产 = `PairingResponseStore.records`）：记录被清掉时详情页随即更新。 */
    responses: Flow<List<PairingResponseRecord>> = flowOf(emptyList()),
) : ViewModel() {

    val contact: StateFlow<Contact?> = repository.contacts
        .map { list -> list.firstOrNull { it.fingerprintHex == fingerprintHex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 回应暗号：这位联系人有回应记录**且已分享过**时才有值（还没分享过的在「配对中」里处理）。
     * 详情页据此显示「再发一次回应暗号」。订阅记录表：收到对方第一条消息后记录被清掉，值随即变空。
     */
    val resendableResponse: StateFlow<String?> = combine(repository.contacts, responses) { list, records ->
        if (list.none { it.fingerprintHex == fingerprintHex }) {
            null
        } else {
            records.firstOrNull { it.fingerprintHex == fingerprintHex && it.lastSharedAtMillis != null }?.responseWire
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 一次性事件:[deleteContact] 的三连删全部落盘后才发,UI 收到即
     *  popBackStack 回会话列表——不提前发,以免联系人已从列表消失但会话密钥
     *  或本地线程还没清干净。 */
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    fun confirm() {
        viewModelScope.launch { repository.markVerified(fingerprintHex) }
    }

    fun rename(name: String) {
        viewModelScope.launch { repository.renameContact(fingerprintHex, name) }
    }

    fun clearMessages() {
        viewModelScope.launch {
            val peer = peerUsername() ?: return@launch
            // Best-effort, same as AccountWiper: the DB rows are already gone by
            // the time clearThread's per-message mediaFiles.deleteMessage() can
            // throw (e.g. a directory that wouldn't fully delete), so surfacing
            // that as a crash here would be worse than logging and moving on.
            runCatching { chatRepository.clearThread(peer) }
                .onFailure { android.util.Log.w("Chencang", "clearMessages: partial media cleanup", it) }
        }
    }

    fun deleteContact() {
        viewModelScope.launch {
            // Resolve the username BEFORE removeContact() drops the row —
            // afterwards peerUsername() would return null.
            val peer = peerUsername() ?: return@launch
            repository.removeContact(fingerprintHex)
            sessionStore.remove(peer)
            // 回应还没发出去就删了这位联系人：「配对中」不能残留这一条（Review Focus 3）。
            forgetPeer(fingerprintHex)
            // Best-effort, same rationale as clearMessages() above: a leftover
            // media directory must not block finishing the contact deletion.
            runCatching { chatRepository.clearThread(peer) }
                .onFailure { android.util.Log.w("Chencang", "deleteContact: partial media cleanup", it) }
            _deleted.emit(Unit)
        }
    }

    private suspend fun peerUsername(): String? =
        repository.contacts.first().firstOrNull { it.fingerprintHex == fingerprintHex }?.username
}
