import Foundation

/// Aggregate outcome of a full-account wipe (settings 危险区「删除账号」).
/// `failedStepCount == 0` is the only case where it's safe to treat the wipe
/// as complete — anything else means some state may still be sitting around
/// (e.g. identity survived but contacts didn't), and the caller must not
/// silently claim success.
public struct AccountWipeResult: Equatable, Sendable {
    public let failedStepCount: Int
    public var allSucceeded: Bool { failedStepCount == 0 }

    public init(failedStepCount: Int) {
        self.failedStepCount = failedStepCount
    }
}

/// Full-account-wipe orchestration, injectable so it's testable here in
/// ChencangShared with the same fakes the rest of the suite uses.
///
/// Best-effort by design: a step failing does NOT stop the remaining steps
/// from running. Aborting partway through would only leave MORE inconsistent
/// state (e.g. identity gone but chat history still on disk) with no safety
/// upside — every step is independent, so there's nothing to roll back and
/// nothing gained by stopping early. Failures are counted, not swallowed:
/// the caller (`MixinSettingsView`) uses `AccountWipeResult` to decide
/// whether it's safe to reset navigation/onboarding state, or whether the
/// user needs to stay put and retry.
public struct AccountWiper {
    private let identityStore: IdentityStore
    private let contactsStore: ContactsStore
    private let chatStore: ChatStore
    private let mediaFiles: MediaFiles
    private let inbox: AppGroupInbox
    private let sessionStore: SessionStore
    private let clearPairingState: @MainActor () async throws -> Void
    private let profileDefaults: UserDefaults?

    public init(
        identityStore: IdentityStore,
        contactsStore: ContactsStore,
        chatStore: ChatStore,
        mediaFiles: MediaFiles,
        inbox: AppGroupInbox,
        sessionStore: SessionStore,
        clearPairingState: @escaping @MainActor () async throws -> Void,
        profileDefaults: UserDefaults?
    ) {
        self.identityStore = identityStore
        self.contactsStore = contactsStore
        self.chatStore = chatStore
        self.mediaFiles = mediaFiles
        self.inbox = inbox
        self.sessionStore = sessionStore
        self.clearPairingState = clearPairingState
        self.profileDefaults = profileDefaults
    }

    /// Runs every wipe step regardless of earlier failures and returns the
    /// aggregate result. `clearPairingState` (production: ``PairingCoordinator/clearAllPending()`` —
    /// both pairing tables, invite key material invalidated before each record goes) counts as a step.
    /// `inbox.clear()` and `sessionStore.invalidateCache()`
    /// don't throw (there is nothing for them to fail on; same for the profile keys), so only the
    /// throwing steps count toward `failedStepCount`.
    @MainActor
    @discardableResult
    public func wipeAll() async -> AccountWipeResult {
        var failed = 0

        // 配对状态最先清:它会等挂起中的配对操作收尾;晚于「删联系人」的话,收尾时会把联系人写回来。
        do { try await clearPairingState() } catch { failed += 1 }
        do { try await identityStore.deleteIdentity() } catch { failed += 1 }
        do { try await contactsStore.removeAll() } catch { failed += 1 }
        do { try chatStore.clearAll() } catch { failed += 1 }
        do { try mediaFiles.deleteAll() } catch { failed += 1 }
        inbox.clear()
        await sessionStore.invalidateCache()
        // 昵称与印章头像三键:不抛、不计入失败数。摘要隐私 / 封缄动作等偏好不动。
        MyProfileKeys.clear(defaults: profileDefaults)

        return AccountWipeResult(failedStepCount: failed)
    }
}
