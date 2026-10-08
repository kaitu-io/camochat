import Foundation

/// One-shot cleanup of App Group residue left by the retired system-input-method /
/// neural-voice form factor (2026-09-24). Idempotent: guarded by a marker key.
public enum LegacyResidueCleaner {
    public static let residueKeys = [
        "cc.sticky.v1",
        "cc.pendingWire.v1",
        "cc.captureState.v1",
        "cc.voiceHeartbeat.v1",
        "cc.voice.provisioning.v1",
        "cc.voice.returnHintShown.v1",
    ]
    static let markerKey = "cc.legacyResidueCleaned.v1"

    public static func runOnce(
        defaults: UserDefaults? = UserDefaults(suiteName: SharedAppGroupDefaults.suiteName),
        containerURL: URL? = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: SharedAppGroupDefaults.suiteName),
        fileManager: FileManager = .default
    ) {
        guard let defaults, !defaults.bool(forKey: markerKey) else { return }
        residueKeys.forEach { defaults.removeObject(forKey: $0) }
        if let dir = containerURL,
           let items = try? fileManager.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for url in items where url.pathExtension == "mlmodelc" {
                try? fileManager.removeItem(at: url)
            }
        }
        defaults.set(true, forKey: markerKey)
    }
}
