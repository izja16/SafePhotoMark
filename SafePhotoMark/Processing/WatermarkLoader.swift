import AppKit
import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import SwiftDraw
import UniformTypeIdentifiers

enum WatermarkKind: Sendable, Equatable {
    case raster
    case svg
}

/// Thread-safe payload used on the processing queue. Rasterizations are cached
/// by target width (small LRU). SVG rasterization is serialized because
/// SwiftDraw's `SVG` type is not documented as thread-safe.
final class WatermarkPayload: @unchecked Sendable {
    let kind: WatermarkKind
    let raster: CGImage?
    let rasterSourceURL: URL?
    let svg: SVG?
    let intrinsicSize: CGSize

    private let lock = NSLock()
    private var cache: [(width: Int, image: CGImage)] = []
    private let cacheLimit = 3

    init(kind: WatermarkKind, raster: CGImage?, rasterSourceURL: URL? = nil, svg: SVG?, intrinsicSize: CGSize) {
        self.kind = kind
        self.raster = raster
        self.rasterSourceURL = rasterSourceURL
        self.svg = svg
        self.intrinsicSize = intrinsicSize
    }

    var aspectRatio: CGFloat {
        let size = intrinsicSize
        guard size.height > 0 else { return 1 }
        return size.width / size.height
    }

    func cgImage(targetWidth: CGFloat) throws -> CGImage {
        let widthKey = max(1, Int(targetWidth.rounded()))
        lock.lock()
        if let index = cache.firstIndex(where: { $0.width == widthKey }) {
            let hit = cache.remove(at: index)
            cache.insert(hit, at: 0)
            let image = hit.image
            lock.unlock()
            return image
        }
        lock.unlock()

        let produced = try rasterize(targetWidth: CGFloat(widthKey))

        lock.lock()
        cache.removeAll { $0.width == widthKey }
        cache.insert((widthKey, produced), at: 0)
        if cache.count > cacheLimit {
            cache.removeLast()
        }
        lock.unlock()
        return produced
    }

    private func rasterize(targetWidth: CGFloat) throws -> CGImage {
        switch kind {
        case .raster:
            guard let raster else { throw ImagePipelineError.watermarkInvalid }
            let width = max(1, targetWidth)
            if width >= CGFloat(raster.width) {
                return raster
            }
            if let url = rasterSourceURL,
               let fromSource = Self.thumbnail(url: url, maxPixelSize: longEdge(width: width, aspect: aspectRatio)) {
                return fromSource
            }
            let height = max(1, Int((width / max(aspectRatio, 0.0001)).rounded()))
            return try ImageDecoder.resample(raster, width: max(1, Int(width.rounded())), height: height)
        case .svg:
            lock.lock()
            defer { lock.unlock() }
            // Re-check the cache under the SwiftDraw lock so concurrent export
            // tasks at the same width share one rasterization.
            let widthKey = max(1, Int(targetWidth.rounded()))
            if let hit = cache.first(where: { $0.width == widthKey }) {
                return hit.image
            }
            guard let svg else { throw ImagePipelineError.watermarkInvalid }
            let width = max(1, targetWidth)
            let height = max(1, width / max(aspectRatio, 0.0001))
            return try rasterizeSVG(svg, width: width, height: height)
        }
    }

    private func longEdge(width: CGFloat, aspect: CGFloat) -> Int {
        let height = width / max(aspect, 0.0001)
        return max(1, Int(max(width, height).rounded()))
    }

    private static func thumbnail(url: URL, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixelSize),
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              CGImageSourceGetCount(source) > 0,
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            return nil
        }
        return image
    }
}

struct LoadedWatermark {
    var url: URL
    var filename: String
    var payload: WatermarkPayload
    var preview: NSImage?

    var kind: WatermarkKind { payload.kind }
    var aspectRatio: CGFloat { payload.aspectRatio }
    var intrinsicSize: CGSize { payload.intrinsicSize }

    func image(forDisplayWidth points: CGFloat, scale: CGFloat) -> NSImage? {
        let pixels = max(1, points * max(scale, 1))
        let capped: CGFloat
        if kind == .raster {
            capped = min(CGFloat(payload.raster?.width ?? Int(pixels)), pixels)
        } else {
            capped = pixels
        }
        guard let cgImage = try? payload.cgImage(targetWidth: capped) else { return preview }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }
}

enum WatermarkLoader {
    /// Our own SVG cache, keyed by path + mtime + size so overwriting a file
    /// at the same URL is not served from SwiftDraw's URL-only `SVGGCache`.
    /// Loaded via `SVG(data:)` to bypass the vendor file-URL cache entirely.
    fileprivate struct SVGSourceKey: Hashable, Sendable {
        let path: String
        let digest: String
    }

    static func load(url: URL) throws -> LoadedWatermark {
        let ext = url.pathExtension.lowercased()
        let type = UTType(filenameExtension: ext)
        let isSVG = ext == "svg" || type?.identifier == "public.svg-image"

        if isSVG {
            let svg = try loadSVG(from: url)
            let previewWidth: CGFloat = 256
            let previewHeight = previewWidth / (svg.size.width / svg.size.height)
            let previewCG = try rasterizeSVG(svg, width: previewWidth, height: previewHeight)
            let preview = NSImage(
                cgImage: previewCG,
                size: NSSize(width: previewCG.width, height: previewCG.height)
            )
            let payload = WatermarkPayload(
                kind: .svg,
                raster: nil,
                rasterSourceURL: nil,
                svg: svg,
                intrinsicSize: svg.size
            )
            return LoadedWatermark(
                url: url,
                filename: url.lastPathComponent,
                payload: payload,
                preview: preview
            )
        }

        let decoded = try ImageDecoder.decode(url: url)
        let image = decoded.cgImage
        let size = CGSize(width: image.width, height: image.height)
        let preview = NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
        let payload = WatermarkPayload(
            kind: .raster,
            raster: image,
            rasterSourceURL: url,
            svg: nil,
            intrinsicSize: size
        )
        return LoadedWatermark(
            url: url,
            filename: url.lastPathComponent,
            payload: payload,
            preview: preview
        )
    }
}

extension WatermarkLoader {
    /// Read the file as data and parse with `SVG(data:)`, never `SVG(fileURL:)`.
    /// The cache key is the path plus a SHA-256 of the bytes, so a same-path
    /// overwrite is detected even when the filesystem mtime has not changed yet.
    fileprivate static func loadSVG(from url: URL) throws -> SVG {
        let data = try Data(contentsOf: url)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let key = SVGSourceKey(path: url.standardizedFileURL.path, digest: digest)

        if let hit = SVGDocumentCache.shared.value(for: key) {
            return hit
        }

        guard let svg = SVG(data: data), svg.size.width > 0, svg.size.height > 0 else {
            throw ImagePipelineError.watermarkInvalid
        }

        SVGDocumentCache.shared.store(svg, for: key)
        return svg
    }
}

/// Lock-protected document cache. `@unchecked Sendable` because all access
/// goes through `NSLock`; this avoids a nonisolated mutable static dictionary.
private final class SVGDocumentCache: @unchecked Sendable {
    static let shared = SVGDocumentCache()
    private let lock = NSLock()
    private var items: [WatermarkLoader.SVGSourceKey: SVG] = [:]

    func value(for key: WatermarkLoader.SVGSourceKey) -> SVG? {
        lock.lock()
        defer { lock.unlock() }
        return items[key]
    }

    func store(_ svg: SVG, for key: WatermarkLoader.SVGSourceKey) {
        lock.lock()
        defer { lock.unlock() }
        items = items.filter { $0.key.path != key.path }
        items[key] = svg
        while items.count > 8, let stale = items.keys.first(where: { $0 != key }) {
            items.removeValue(forKey: stale)
        }
    }
}

/// Rasterize an SVG into a transparent sRGB bitmap at the requested pixel size.
/// SwiftDraw draws in a top-left coordinate system, so the context is flipped.
func rasterizeSVG(_ svg: SVG, width: CGFloat, height: CGFloat) throws -> CGImage {
    let pixelWidth = max(1, Int(ceil(width)))
    let pixelHeight = max(1, Int(ceil(height)))
    let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    guard let ctx = CGContext(
        data: nil,
        width: pixelWidth,
        height: pixelHeight,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: srgb,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
        throw ImagePipelineError.watermarkInvalid
    }
    ctx.interpolationQuality = .high
    ctx.translateBy(x: 0, y: CGFloat(pixelHeight))
    ctx.scaleBy(x: 1, y: -1)
    ctx.draw(svg, in: CGRect(x: 0, y: 0, width: CGFloat(pixelWidth), height: CGFloat(pixelHeight)))
    guard let image = ctx.makeImage() else {
        throw ImagePipelineError.watermarkInvalid
    }
    return image
}
