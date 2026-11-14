import Foundation

enum ImportService {
    static let photoLimit = 2000
    static let thumbnailConcurrency = 4

    struct ImportedPhoto: Sendable {
        let url: URL
        let filename: String
        let thumbnail: SendableCGImage?
    }

    struct Outcome: Sendable {
        var items: [ImportedPhoto]
        var truncated: Bool
        /// Unique new images discovered in this import, including ones past the cap.
        var discoveredNew: Int
    }

    /// Enumerate roots (files or folders) and decode thumbnails off the main
    /// actor. Only the caller should retain security-scoped access on `roots`.
    static func importPhotos(
        roots: [URL],
        existing: Set<URL>,
        remainingCapacity: Int
    ) async -> Outcome {
        let collected: (urls: [URL], truncated: Bool, discoveredNew: Int) = await Task.detached(priority: .userInitiated) {
            collectImageURLs(from: roots, existing: existing, remainingCapacity: remainingCapacity)
        }.value

        guard remainingCapacity > 0, !collected.urls.isEmpty else {
            return Outcome(
                items: [],
                truncated: collected.truncated || (remainingCapacity <= 0 && collected.discoveredNew > 0),
                discoveredNew: collected.discoveredNew
            )
        }

        let urls = collected.urls
        var items: [ImportedPhoto] = []
        items.reserveCapacity(urls.count)

        await withTaskGroup(of: (Int, ImportedPhoto).self) { group in
            var nextIndex = 0
            var inFlight = 0

            func enqueue() {
                while inFlight < thumbnailConcurrency, nextIndex < urls.count {
                    let index = nextIndex
                    let url = urls[index]
                    nextIndex += 1
                    inFlight += 1
                    group.addTask(priority: .utility) {
                        let thumb = ThumbnailLoader.loadCGImage(url: url).map { SendableCGImage(image: $0) }
                        return (index, ImportedPhoto(url: url, filename: url.lastPathComponent, thumbnail: thumb))
                    }
                }
            }

            enqueue()
            var buffer: [Int: ImportedPhoto] = [:]
            var written = 0
            for await (index, item) in group {
                inFlight -= 1
                buffer[index] = item
                while let ready = buffer.removeValue(forKey: written) {
                    items.append(ready)
                    written += 1
                }
                enqueue()
            }
        }

        return Outcome(items: items, truncated: collected.truncated, discoveredNew: collected.discoveredNew)
    }

    nonisolated static func collectImageURLs(
        from roots: [URL],
        existing: Set<URL>,
        remainingCapacity: Int
    ) -> (urls: [URL], truncated: Bool, discoveredNew: Int) {
        var results: [URL] = []
        var seen = existing
        var truncated = false
        var discoveredNew = 0
        let cap = max(0, remainingCapacity)

        func consider(_ url: URL) {
            let standardized = url.standardizedFileURL
            guard seen.insert(standardized).inserted else { return }
            discoveredNew += 1
            if results.count < cap {
                results.append(url)
            } else {
                truncated = true
            }
        }

        for root in roots {
            for url in images(in: root) {
                consider(url)
            }
        }
        return (results, truncated, discoveredNew)
    }

    /// Walk a single dropped/chosen URL. Does not start security-scoped access
    /// on child files; the parent (or file) must already be accessible.
    /// Every supported image is yielded so the caller can count the total found.
    nonisolated private static func images(in url: URL) -> [URL] {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else {
            return []
        }
        if !isDirectory.boolValue {
            return SupportedTypes.isSupportedImage(url) ? [url] : []
        }
        var results: [URL] = []
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey]
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else {
            return []
        }
        for case let file as URL in enumerator {
            if SupportedTypes.isSupportedImage(file) {
                results.append(file)
            }
        }
        return results
    }
}
