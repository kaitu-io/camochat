package app.chencang.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle of a peer relationship. 邀请中 (pending) lives in [PendingInvite],
 * not here — a [Contact] only exists once pairing has completed. The single
 * variant is intentional for V1; later states attach to the model when they
 * earn their keep.
 */
@Serializable
enum class PairingState { PAIRED }

/**
 * A paired peer. Persisted via [app.chencang.shared.contacts.ContactsDataStore].
 *
 * - [fingerprintHex] is the stable identity key — the hex of the peer's
 *   long-term identity fingerprint. All repository CRUD dedupes/looks up by
 *   this, not by [username].
 * - [username] is a legacy display alias kept for now; treat it as cosmetic
 *   like [displayName].
 * - [safetyEmoji] is the 8-emoji fingerprint shown for OOB verification. Empty
 *   until derived (a later task fills it).
 * - [sessionState] is the opaque chencang-core serialized Session state (Double
 *   Ratchet). Don't try to interpret it from Kotlin; treat it as bytes.
 * - [verified] is true after the user has confirmed the 8-emoji fingerprint OOB.
 * - [deviceId] is a synthetic value (= fingerprint hex) set by in-band pairing.
 *   Retained for persistence compatibility; no live server call consumes it.
 * - [acceptedInviteDigest] is the digest of the invite I accepted to create this
 *   contact (see `PairingTransport.inviteDigest`); it blocks a second handshake on
 *   the same invite for as long as the contact exists. Null for a contact created
 *   by completing my own invite, and for contacts stored by older builds.
 */
@Serializable
data class Contact(
    @SerialName("fingerprint_hex") val fingerprintHex: String,
    val username: String,
    val displayName: String,
    @SerialName("paired_at") val pairedAt: Long,
    @SerialName("pairing_state") val pairingState: PairingState = PairingState.PAIRED,
    @SerialName("safety_emoji") val safetyEmoji: List<String> = emptyList(),
    val verified: Boolean = false,
    @SerialName("session_state") val sessionState: ByteArray? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("accepted_invite_digest") val acceptedInviteDigest: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        if (fingerprintHex != other.fingerprintHex) return false
        if (username != other.username) return false
        if (displayName != other.displayName) return false
        if (pairedAt != other.pairedAt) return false
        if (pairingState != other.pairingState) return false
        if (safetyEmoji != other.safetyEmoji) return false
        if (verified != other.verified) return false
        if (sessionState != null) {
            if (other.sessionState == null) return false
            if (!sessionState.contentEquals(other.sessionState)) return false
        } else if (other.sessionState != null) return false
        if (deviceId != other.deviceId) return false
        if (acceptedInviteDigest != other.acceptedInviteDigest) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fingerprintHex.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + pairedAt.hashCode()
        result = 31 * result + pairingState.hashCode()
        result = 31 * result + safetyEmoji.hashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + (sessionState?.contentHashCode() ?: 0)
        result = 31 * result + (deviceId?.hashCode() ?: 0)
        result = 31 * result + (acceptedInviteDigest?.hashCode() ?: 0)
        return result
    }
}

/**
 * An in-flight pairing invite the local user created but the peer has not yet
 * redeemed. Tracked separately from [Contact] (which only exists post-pairing).
 *
 * - [code] is the short invite code surfaced to the user and polled against.
 * - [createdAt] / [expiresAt] are epoch millis.
 */
@Serializable
data class PendingInvite(
    val code: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    /** Label A typed for B at invite creation time. Threaded into PairingPoller
     *  on Paired → becomes Contact.displayName (priority: this > B's bobDisplayName
     *  > fingerprint fallback). Null/blank means "A didn't fill it"; UI falls back. */
    @SerialName("peer_label_for_a") val peerLabelForA: String? = null,
)

@Serializable
data class ContactListSnapshot(
    val contacts: List<Contact> = emptyList(),
    @SerialName("pending_invites") val pendingInvites: List<PendingInvite> = emptyList(),
)
