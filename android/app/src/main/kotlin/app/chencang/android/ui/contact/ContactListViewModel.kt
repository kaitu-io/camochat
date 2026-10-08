package app.chencang.android.ui.contact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.chencang.shared.model.Contact
import app.chencang.shared.pairing.inband.PairingResponseRecord
import app.chencang.shared.pairing.inband.PendingItem
import app.chencang.shared.pairing.inband.PendingPairingRecord
import app.chencang.shared.pairing.inband.pendingBadgeCount
import app.chencang.shared.pairing.inband.pendingItems
import app.chencang.shared.pairing.inband.unsentResponseFingerprints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.util.Locale

/**
 * The Contacts tab's contact list. Reads contacts only, never the chat message store.
 *
 * Sorting follows [locale] (the system language): collation at SECONDARY strength, so case is
 * ignored and accents count; identical display names fall back to fingerprint ascending for a
 * stable order (spec three-tab-shell section 7.2, cross-platform case table S).
 *
 * Also derives the "pairing" section (table K) and the tab badge: a responder contact whose
 * pairing code was never sent back appears only under "pairing", not again in [rows].
 *
 * @param now reads the clock, to decide whether a row "may have expired" (created over 30 days ago).
 * @param locale the collation locale; defaults to the system language.
 */
class ContactListViewModel(
    contacts: Flow<List<Contact>>,
    invites: Flow<List<PendingPairingRecord>> = flowOf(emptyList()),
    responses: Flow<List<PairingResponseRecord>> = flowOf(emptyList()),
    now: () -> Long = System::currentTimeMillis,
    locale: Locale = Locale.getDefault(),
) : ViewModel() {

    // Collator is not thread-safe; combine's transform runs sequentially in one collecting coroutine.
    private val order: Comparator<Contact> = Collator.getInstance(locale)
        .apply { strength = Collator.SECONDARY }
        .let { collator ->
            Comparator<Contact> { a, b -> collator.compare(a.displayName, b.displayName) }
                .thenBy { it.fingerprintHex }
        }

    val rows: StateFlow<List<Contact>> = combine(contacts, responses) { cs, rs ->
        val hidden = unsentResponseFingerprints(rs, cs.map { it.fingerprintHex }.toSet())
        cs.filterNot { it.fingerprintHex in hidden }.sortedWith(order)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Pairing" rows in table K order; empty = the whole section is hidden. */
    val pending: StateFlow<List<PendingItem>> = combine(contacts, invites, responses) { cs, inv, rs ->
        pendingItems(inv, rs, cs.map { it.fingerprintHex }.toSet(), now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Badge on the Contacts tab icon = rows that need my action (0 = hidden). */
    val badge: StateFlow<Int> = pending.map(::pendingBadgeCount)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** All contacts by fingerprint: response rows need the contact's name and avatar. */
    val contactsByFingerprint: StateFlow<Map<String, Contact>> = contacts
        .map { cs -> cs.associateBy { it.fingerprintHex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
