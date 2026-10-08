package app.chencang.shared

import app.chencang.shared.contacts.ContactsStore
import app.chencang.shared.contacts.InMemoryContactsStore
import app.chencang.shared.model.Contact
import app.chencang.shared.model.ContactListSnapshot
import app.chencang.shared.model.PendingInvite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for everything the UI layer needs from the data layer:
 *
 *  - Contacts list (paired peers), keyed by [Contact.fingerprintHex]
 *  - Pending invites (created locally, not yet redeemed by the peer)
 *  - Session-state mutations (after each Double Ratchet encrypt/decrypt step)
 *
 * `:app` consumes this through [CcApp.instance.repository].
 */
class CcRepository(
    private val store: ContactsStore,
) {
    val contacts: Flow<List<Contact>> = store.data.map { it.contacts }
    val pendingInvites: Flow<List<PendingInvite>> = store.data.map { it.pendingInvites }

    /** Insert or replace a contact, deduping by [Contact.fingerprintHex]. */
    suspend fun upsertContact(contact: Contact) {
        store.updateData { snap ->
            val without = snap.contacts.filterNot { it.fingerprintHex == contact.fingerprintHex }
            snap.copy(contacts = without + contact)
        }
    }

    /**
     * Receiver-side local rename of a paired contact (the "备注"). The wire never
     * carries a usable display name — a self-asserted name would be spoofable —
     * so the person who just verified the emoji assigns a LOCAL label instead.
     *
     * No-op when [fingerprintHex] is unknown or [displayName] is blank (we never
     * overwrite a label with an empty string). The name is trimmed before store.
     */
    suspend fun renameContact(fingerprintHex: String, displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isBlank()) return
        store.updateData { snap ->
            snap.copy(
                contacts = snap.contacts.map {
                    if (it.fingerprintHex == fingerprintHex) it.copy(displayName = trimmed) else it
                },
            )
        }
    }

    suspend fun markVerified(fingerprintHex: String) {
        store.updateData { snap ->
            snap.copy(
                contacts = snap.contacts.map {
                    if (it.fingerprintHex == fingerprintHex) it.copy(verified = true) else it
                },
            )
        }
    }

    /**
     * Resolve a peer's synthetic device id (= fingerprint, set by in-band
     * pairing) from the contact list. Historical field — the in-band voice path
     * never issues a `GET /v1/blobs/...` call; tokens travel in the wire.
     * Returns null when the contact is unknown.
     */
    suspend fun deviceIdFor(fingerprintHex: String): String? =
        contacts.first().firstOrNull { it.fingerprintHex == fingerprintHex }?.deviceId

    suspend fun updateSessionState(fingerprintHex: String, state: ByteArray) {
        store.updateData { snap ->
            snap.copy(
                contacts = snap.contacts.map {
                    if (it.fingerprintHex == fingerprintHex) it.copy(sessionState = state) else it
                },
            )
        }
    }

    /** Add a locally-created invite, deduping by [PendingInvite.code]. */
    suspend fun addPendingInvite(invite: PendingInvite) {
        store.updateData { snap ->
            val without = snap.pendingInvites.filterNot { it.code == invite.code }
            snap.copy(pendingInvites = without + invite)
        }
    }

    suspend fun removePendingInvite(code: String) {
        store.updateData { snap ->
            snap.copy(pendingInvites = snap.pendingInvites.filterNot { it.code == code })
        }
    }

    suspend fun pendingInvitesSnapshot(): List<PendingInvite> = pendingInvites.first()

    /** Looks up a single in-flight invite by [code], null when absent. Used by
     *  [app.chencang.shared.pairing.PairingPoller] to consume the A-side label
     *  recorded at creation time. */
    suspend fun findPendingInvite(code: String): PendingInvite? =
        pendingInvitesSnapshot().firstOrNull { it.code == code }

    /** Account wipe: drop every contact and pending invite. */
    suspend fun clearAll() {
        store.updateData { ContactListSnapshot() }
    }

    /** 删除联系人(不一致/手动删除)。pendingInvites 不动。 */
    suspend fun removeContact(fingerprintHex: String) {
        store.updateData { snap ->
            snap.copy(
                contacts = snap.contacts.filterNot { it.fingerprintHex == fingerprintHex },
            )
        }
    }

    companion object {
        /** In-memory factory for unit tests. */
        fun forTest(): CcRepository = CcRepository(InMemoryContactsStore())
    }
}
