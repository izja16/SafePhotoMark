import Foundation

/// Codable snapshot of user-facing settings (not bookmarks).
struct SettingsSnapshot: Codable, Equatable, Sendable {
    var sizePercent: Double
    var position: WatermarkPosition
    var watermarkMode: WatermarkMode
    var opacity: Double
    var outputFormat: OutputFormat
    var quality: Double
    var playCompletionSound: Bool
    var stripMetadata: Bool
    var randomRename: Bool

    static let defaultValue = SettingsSnapshot(
        sizePercent: WatermarkCompositor.defaultSizePercent,
        position: .bottomRight,
        watermarkMode: .random,
        opacity: WatermarkCompositor.defaultOpacity,
        outputFormat: .sameAsSource,
        quality: 0.95,
        playCompletionSound: true,
        stripMetadata: true,
        randomRename: false
    )
}

/// Centralizes UserDefaults keys, the remember-settings flag, the settings
/// snapshot, and security-scoped bookmarks for the output folder and watermark.
@MainActor
final class SettingsStore {
    static let rememberSettingsKey = "SafePhotoMark.rememberSettings"
    static let snapshotKey = "SafePhotoMark.settingsSnapshot"
    static let outputFolderBookmarkKey = "SafePhotoMark.outputFolderBookmark"
    static let watermarkBookmarkKey = "SafePhotoMark.watermarkBookmark"

    // Legacy 1.0 key names, read when no snapshot has been written yet.
    private static let legacyKeys: [String] = [
        "SafePhotoMark.sizePercent",
        "SafePhotoMark.position",
        "SafePhotoMark.randomPlacement",
        "SafePhotoMark.watermarkMode",
        "SafePhotoMark.opacity",
        "SafePhotoMark.outputFormat",
        "SafePhotoMark.quality",
        "SafePhotoMark.playCompletionSound",
        "SafePhotoMark.stripMetadata",
        "SafePhotoMark.randomRename"
    ]

    var rememberSettings: Bool {
        didSet {
            UserDefaults.standard.set(rememberSettings, forKey: Self.rememberSettingsKey)
        }
    }

    init() {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: Self.rememberSettingsKey) != nil {
            rememberSettings = defaults.bool(forKey: Self.rememberSettingsKey)
        } else {
            rememberSettings = true
        }
    }

    func loadSnapshot() -> SettingsSnapshot {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: Self.snapshotKey),
           let snapshot = try? JSONDecoder().decode(SettingsSnapshot.self, from: data) {
            return validated(snapshot)
        }
        return validated(loadLegacySnapshot())
    }

    func saveSnapshot(_ snapshot: SettingsSnapshot) {
        guard rememberSettings else { return }
        if let data = try? JSONEncoder().encode(snapshot) {
            UserDefaults.standard.set(data, forKey: Self.snapshotKey)
        }
    }

    func clearPersistedSettings() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Self.snapshotKey)
        for key in Self.legacyKeys {
            defaults.removeObject(forKey: key)
        }
        defaults.removeObject(forKey: Self.outputFolderBookmarkKey)
        defaults.removeObject(forKey: Self.watermarkBookmarkKey)
    }

    func saveOutputFolderBookmark(_ url: URL) {
        guard rememberSettings else { return }
        BookmarkStore.saveSecurityScopedBookmark(url, key: Self.outputFolderBookmarkKey)
    }

    func saveWatermarkBookmark(_ url: URL) {
        guard rememberSettings else { return }
        BookmarkStore.saveSecurityScopedBookmark(url, key: Self.watermarkBookmarkKey)
    }

    func resolveOutputFolder() -> (url: URL, access: SecurityScopedAccess)? {
        BookmarkStore.resolveSecurityScopedBookmark(key: Self.outputFolderBookmarkKey)
    }

    func resolveWatermark() -> (url: URL, access: SecurityScopedAccess)? {
        BookmarkStore.resolveSecurityScopedBookmark(key: Self.watermarkBookmarkKey)
    }

    private func validated(_ snapshot: SettingsSnapshot) -> SettingsSnapshot {
        var value = snapshot
        if !WatermarkCompositor.sizePercentRange.contains(value.sizePercent) {
            value.sizePercent = SettingsSnapshot.defaultValue.sizePercent
        }
        if !(0 ... 1).contains(value.opacity) {
            value.opacity = SettingsSnapshot.defaultValue.opacity
        }
        if !(0.5 ... 1.0).contains(value.quality) {
            value.quality = SettingsSnapshot.defaultValue.quality
        }
        return value
    }

    private func loadLegacySnapshot() -> SettingsSnapshot {
        var snapshot = SettingsSnapshot.defaultValue
        let defaults = UserDefaults.standard
        if let storedSize = defaults.object(forKey: "SafePhotoMark.sizePercent") as? Double,
           WatermarkCompositor.sizePercentRange.contains(storedSize) {
            snapshot.sizePercent = storedSize
        }
        if let raw = defaults.string(forKey: "SafePhotoMark.position") {
            if raw == "randomCenter" {
                snapshot.watermarkMode = .random
                snapshot.position = .bottomRight
            } else if let value = WatermarkPosition(rawValue: raw) {
                snapshot.position = value
            }
        }
        if let raw = defaults.string(forKey: "SafePhotoMark.watermarkMode"),
           let value = WatermarkMode(rawValue: raw) {
            snapshot.watermarkMode = value
        } else if defaults.object(forKey: "SafePhotoMark.randomPlacement") != nil {
            snapshot.watermarkMode = defaults.bool(forKey: "SafePhotoMark.randomPlacement") ? .random : .fixed
        }
        if let storedOpacity = defaults.object(forKey: "SafePhotoMark.opacity") as? Double,
           (0 ... 1).contains(storedOpacity) {
            snapshot.opacity = storedOpacity
        }
        if let raw = defaults.string(forKey: "SafePhotoMark.outputFormat"),
           let value = OutputFormat(rawValue: raw) {
            snapshot.outputFormat = value
        }
        if let storedQuality = defaults.object(forKey: "SafePhotoMark.quality") as? Double,
           (0.5 ... 1.0).contains(storedQuality) {
            snapshot.quality = storedQuality
        }
        if defaults.object(forKey: "SafePhotoMark.playCompletionSound") != nil {
            snapshot.playCompletionSound = defaults.bool(forKey: "SafePhotoMark.playCompletionSound")
        }
        if defaults.object(forKey: "SafePhotoMark.stripMetadata") != nil {
            snapshot.stripMetadata = defaults.bool(forKey: "SafePhotoMark.stripMetadata")
        }
        if defaults.object(forKey: "SafePhotoMark.randomRename") != nil {
            snapshot.randomRename = defaults.bool(forKey: "SafePhotoMark.randomRename")
        }
        return snapshot
    }
}
