import CoreGraphics
import Foundation

/// Count-capped, byte-budgeted LRU for decoded preview bitmaps.
@MainActor
final class ImageByteCache {
    private struct Entry {
        var image: CGImage
        var bytes: Int
    }

    private var store: [URL: Entry] = [:]
    private var order: [URL] = []
    private var totalBytes = 0

    var countLimit: Int
    var byteLimit: Int

    init(countLimit: Int = 48, byteLimit: Int = 256 * 1024 * 1024) {
        self.countLimit = countLimit
        self.byteLimit = byteLimit
    }

    func image(for url: URL) -> CGImage? {
        let key = url.standardizedFileURL
        guard let entry = store[key] else { return nil }
        touch(key)
        return entry.image
    }

    func insert(_ image: CGImage, for url: URL) {
        let key = url.standardizedFileURL
        let bytes = Self.byteSize(of: image)
        if let existing = store.removeValue(forKey: key) {
            totalBytes -= existing.bytes
            order.removeAll { $0 == key }
        }
        store[key] = Entry(image: image, bytes: bytes)
        order.insert(key, at: 0)
        totalBytes += bytes
        evictIfNeeded()
    }

    func remove(_ url: URL) {
        let key = url.standardizedFileURL
        guard let existing = store.removeValue(forKey: key) else { return }
        totalBytes -= existing.bytes
        order.removeAll { $0 == key }
    }

    func removeAll() {
        store.removeAll()
        order.removeAll()
        totalBytes = 0
    }

    private func touch(_ key: URL) {
        order.removeAll { $0 == key }
        order.insert(key, at: 0)
    }

    private func evictIfNeeded() {
        while totalBytes > byteLimit || store.count > countLimit, let oldest = order.last {
            if let existing = store.removeValue(forKey: oldest) {
                totalBytes -= existing.bytes
            }
            order.removeLast()
        }
    }

    private static func byteSize(of image: CGImage) -> Int {
        let row = image.bytesPerRow
        if row > 0 {
            return row * image.height
        }
        return max(1, image.width * image.height * 4)
    }
}
