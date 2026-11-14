import AppKit
import Foundation
import ImageIO

enum BatchProcessor {
    /// Process a single photo. Callers should wrap this in an autorelease pool
    /// and avoid running many huge HEIC files in parallel.
    ///
    /// Cancellation is checked before decode, before compose, and before write.
    /// A file that has already been written is not rolled back if the batch is
    /// cancelled afterwards.
    static func processOne(
        source: URL,
        destination: URL,
        format: OutputFormat,
        watermark: WatermarkPayload,
        settings: ExportSettings,
        placement: WatermarkPlacement?,
        isCancelled: @escaping () -> Bool = { Task.isCancelled }
    ) throws -> URL {
        func throwIfCancelled() throws {
            if Task.isCancelled || isCancelled() {
                throw ImagePipelineError.cancelled
            }
        }

        try throwIfCancelled()
        // Export path: skip flattenToSRGB; compose already redraws into sRGB.
        let decoded = try ImageDecoder.decodeRaw(url: source)

        try throwIfCancelled()
        let canvas = CGSize(width: decoded.cgImage.width, height: decoded.cgImage.height)
        let markSize = WatermarkCompositor.markSize(
            canvas: canvas,
            percent: settings.sizePercent,
            aspect: watermark.aspectRatio
        )
        let markImage = try watermark.cgImage(targetWidth: markSize.width)

        var rng = SystemRandomNumberGenerator()
        let rect = WatermarkCompositor.resolvePlacement(
            canvas: canvas,
            mark: markSize,
            position: settings.position,
            random: settings.watermarkMode.placesRandomly,
            existing: placement,
            rng: &rng
        ).rect

        try throwIfCancelled()
        let composed = try WatermarkCompositor.compose(
            base: decoded.cgImage,
            watermark: markImage,
            rectTopLeft: rect,
            opacity: settings.opacity,
            format: format
        )

        try throwIfCancelled()
        if destination.standardizedFileURL.resolvingSymlinksInPath()
            == source.standardizedFileURL.resolvingSymlinksInPath() {
            throw ImagePipelineError.cannotWrite(L10n.string(.refuseOverwrite))
        }

        try ImageExporter.export(
            image: composed,
            to: destination,
            format: format,
            quality: settings.quality,
            stripMetadata: settings.stripMetadata,
            source: source
        )
        return destination
    }
}

enum ThumbnailLoader {
    static let defaultMaxPixel = 96

    static func load(url: URL, maxPixel: Int = ThumbnailLoader.defaultMaxPixel) -> NSImage? {
        guard let cgImage = loadCGImage(url: url, maxPixel: maxPixel) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    static func loadCGImage(url: URL, maxPixel: Int = ThumbnailLoader.defaultMaxPixel) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              CGImageSourceGetCount(source) > 0,
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else {
            return nil
        }
        return cgImage
    }
}
