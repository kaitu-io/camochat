import Foundation

/// MainActor-confined store that mirrors contacts into the App Group,
/// including each contact's pairing-time safety emoji (``safetyEmoji(for:)``).
@MainActor
open class ContactsStore: ObservableObject {
    public static let shared = ContactsStore()

    @Published public private(set) var contacts: [AppGroupContact] = []

    private let sync: AppGroupSync

    public init(sync: AppGroupSync = .shared) {
        self.sync = sync
        self.contacts = (try? sync.readContacts()) ?? []
    }

    /// Re-reads the App Group mirror. The pairing coordinator writes new contacts straight into the
    /// mirror (`AppGroupContactPersister`), behind this resident cache's back; every mutator below
    /// writes the WHOLE array back, so it must start from the mirror or it would drop those contacts.
    /// An unreadable mirror keeps the cache.
    public func reload() {
        if let fresh = try? sync.readContacts(), fresh != contacts { contacts = fresh }
    }

    public func add(_ contact: AppGroupContact) async throws {
        reload()
        var next = contacts.filter { $0.id != contact.id }
        next.append(contact)
        try sync.writeContacts(next)
        self.contacts = next
    }

    /// Wipes every contact, both in memory and from the App Group mirror.
    /// Used by the settings 危险区「删除账号」full-wipe orchestration — does
    /// NOT touch Keychain-held session secrets (that's `SessionStore`'s job).
    public func removeAll() async throws {
        contacts = []
        try sync.writeContacts([])
    }

    public func markVerified(contactId: String) async throws {
        reload()
        contacts = contacts.map { c in
            c.id == contactId
                ? AppGroupContact(
                    id: c.id,
                    displayName: c.displayName,
                    isVerified: true,
                    deviceId: c.deviceId,
                    emoji: c.emoji,
                    acceptedInviteDigest: c.acceptedInviteDigest,
                    pairedAt: c.pairedAt
                )
                : c
        }
        try sync.writeContacts(contacts)
    }

    /// Removes a single contact (both in memory and the App Group mirror).
    /// Used by BOTH `ContactView.deleteContact()` and the pairing wizard's
    /// mismatch-rejection path (`PairingWizardViewModel.rejectMismatch()`,
    /// M4 Task 8) — a possible MITM, or an explicit delete, means the contact
    /// must go, unlike ``removeAll()`` (settings' full-account wipe). Missing
    /// id is a no-op, same as Android's `CcRepository.removeContact` for an
    /// unknown fingerprint.
    ///
    /// The cache is replaced only after the mirror write succeeds: a failed write leaves memory and
    /// mirror in agreement (the contact still there), so a later `reload()` cannot resurrect it.
    /// `open` so a test can inject a failing delete.
    open func remove(contactId: String) async throws {
        reload()
        let next = contacts.filter { $0.id != contactId }
        try sync.writeContacts(next)
        contacts = next
    }

    /// Local rename (备注) of a paired contact — trims, and a blank/whitespace-only
    /// result is a no-op (same semantics as Android's `CcRepository.renameContact`).
    /// The wire never carries a self-asserted name; this is purely a local label.
    public func rename(contactId: String, to newName: String) async throws {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        reload()
        contacts = contacts.map { c in
            c.id == contactId
                ? AppGroupContact(
                    id: c.id,
                    displayName: trimmed,
                    isVerified: c.isVerified,
                    deviceId: c.deviceId,
                    emoji: c.emoji,
                    acceptedInviteDigest: c.acceptedInviteDigest,
                    pairedAt: c.pairedAt
                )
                : c
        }
        try sync.writeContacts(contacts)
    }

    /// Resolve a peer's synthetic device id (= fingerprint, set by in-band
    /// pairing) from the contact mirror. Historical note: pre-Plan-5 this was a
    /// server-issued id used for `GET /v1/blobs/...`. Field retained for
    /// persistence compat; no live server call consumes it.
    public func deviceId(for contactId: String) -> String? {
        contacts.first(where: { $0.id == contactId })?.deviceId
    }

    /// Returns the 8-emoji safety fingerprint for a contact — the exact same
    /// emoji rendered during pairing's 对印 act. Read directly off the
    /// contact record (`AppGroupContact.emoji`), where it was persisted the
    /// moment pairing completed (`PairedContact.emoji`, carried through by
    /// `AppGroupContactPersister.upsert`/`PairingWizardViewModel
    /// .persistLocally`, and preserved across later mutations by
    /// ``markVerified(contactId:)``/``rename(contactId:to:)`` above) — no
    /// re-derivation from any secret, unlike this method's previous
    /// (never-worked) Keychain-backed implementation.
    ///
    /// Empty only for a contact paired before this field existed: `Codable`'s
    /// synthesized decode treats a missing "emoji" key in old-format JSON as
    /// nil, and `EmojiSealGridView` already renders an empty array as its own
    /// "暂无安全码，请重新配对以生成" placeholder — there is nothing to recover
    /// for those contacts short of re-pairing.
    public func safetyEmoji(for contactId: String) async throws -> [String] {
        contacts.first(where: { $0.id == contactId })?.emoji ?? []
    }
}
