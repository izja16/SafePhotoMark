import Foundation

/// Keeps a security-scoped URL accessible for the lifetime of this object.
/// Used only on the main actor; not Sendable.
final class SecurityScopedAccess {
    let url: URL
    private let didStart: Bool

    init(url: URL) {
        self.url = url
        self.didStart = url.startAccessingSecurityScopedResource()
    }

    deinit {
        if didStart {
            url.stopAccessingSecurityScopedResource()
        }
    }
}

enum BookmarkStore {
    static func saveSecurityScopedBookmark(_ url: URL, key: String) {
        do {
            let data = try url.bookmarkData(
                options: .withSecurityScope,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            UserDefaults.standard.set(data, forKey: key)
        } catch {
            // Bookmarking is a convenience; failure is non-fatal.
        }
    }

    static func resolveSecurityScopedBookmark(key: String) -> (url: URL, access: SecurityScopedAccess)? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        do {
            var isStale = false
            let url = try URL(
                resolvingBookmarkData: data,
                options: [.withSecurityScope, .withoutUI],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
            let access = SecurityScopedAccess(url: url)
            if isStale {
                saveSecurityScopedBookmark(url, key: key)
            }
            return (url, access)
        } catch {
            return nil
        }
    }
}
