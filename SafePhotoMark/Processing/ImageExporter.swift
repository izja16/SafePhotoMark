import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ImageExporter {
    static func resolvedFormat(requested: OutputFormat, source: SourceImageKind) -> OutputFormat {
        requested == .sameAsSource ? source.preferredSameAsSourceFormat : requested
    }

    static func fileExtension(for format: OutputFormat) -> String {
        switch format {
        case .jpeg: return "jpg"
        case .png: return "png"
        case .heif: return "heic"
        case .tiff: return "tiff"
        case .sameAsSource:
            preconditionFailure("Output format must be resolved before asking for a file extension")
        }
    }

    static func uti(for format: OutputFormat) -> CFString {
        let identifier: String
        switch format {
        case .jpeg: identifier = UTType.jpeg.identifier
        case .png: identifier = UTType.png.identifier
        case .heif: identifier = UTType.heic.identifier
        case .tiff: identifier = UTType.tiff.identifier
        case .sameAsSource:
            preconditionFailure("Output format must be resolved before asking for a UTI")
        }
        return identifier as CFString
    }

    /// Write a brand-new file via `CGImageDestination`.
    ///
    /// This never calls `CGImageDestinationAddImageFromSource`. When
    /// `stripMetadata` is true, no source properties are copied: no EXIF,
    /// IPTC, XMP, GPS, MakerNote, thumbnails, or timed metadata. The only
    /// image-level key is orientation = 1 (up), because the pixels have
    /// already been baked upright. A standard sRGB profile may be embedded
    /// from the `CGImage` color space; camera ICC profiles are not copied
    /// (the pipeline converts to sRGB first).
    ///
    /// When `stripMetadata` is false, descriptive metadata is copied from
    /// `source`, but orientation is forced to “up”, camera ICC and pixel
    /// geometry are not reused, and embedded thumbnails are dropped so an
    /// unwatermarked preview cannot leak out.
    ///
    /// The file is written to a temporary name in the same directory and
    /// then moved into place, so a failed finalize cannot leave a half-written
    /// file that also consumes the uniquified destination name.
    ///
    /// Callers must pass a concrete format (`resolvedFormat`); `.sameAsSource`
    /// is not accepted here. For JPEG, the compositor already drew into an
    /// opaque white context, so this method does not flatten the full frame
    /// again on the normal path.
    static func export(
        image: CGImage,
        to url: URL,
        format: OutputFormat,
        quality: Double,
        stripMetadata: Bool,
        source: URL
    ) throws {
        precondition(format != .sameAsSource, "Output format must be resolved before export")

        var pixels = image
        if format == .jpeg, !ImageAlpha.isOpaque(image) {
            pixels = try ImageDecoder.flattenOpaqueWhite(image)
        }

        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        // Unique names are chosen before workers start; refuse to clobber just in case.
        if FileManager.default.fileExists(atPath: url.path) {
            throw ImagePipelineError.cannotWrite(L10n.string(.errorTargetExists))
        }

        let tempURL = directory.appendingPathComponent(
            ".\(url.lastPathComponent).tmp-\(UUID().uuidString)"
        )
        var keepTemp = false
        defer {
            if !keepTemp {
                try? FileManager.default.removeItem(at: tempURL)
            }
        }

        guard let destination = CGImageDestinationCreateWithURL(
            tempURL as CFURL,
            uti(for: format),
            1,
            nil
        ) else {
            throw ImagePipelineError.cannotCreateDestination
        }

        var destinationProperties: [CFString: Any] = [:]
        if format == .jpeg || format == .heif {
            destinationProperties[kCGImageDestinationLossyCompressionQuality] = min(max(quality, 0.5), 1.0)
        }
        CGImageDestinationSetProperties(destination, destinationProperties as CFDictionary)

        var imageProperties: [String: Any]
        if stripMetadata {
            imageProperties = [
                kCGImagePropertyOrientation as String: CGImagePropertyOrientation.up.rawValue
            ]
        } else {
            imageProperties = preservedMetadata(from: source)
        }
        CGImageDestinationAddImage(destination, pixels, imageProperties as CFDictionary)

        guard CGImageDestinationFinalize(destination) else {
            throw ImagePipelineError.finalizeFailed
        }

        if FileManager.default.fileExists(atPath: url.path) {
            throw ImagePipelineError.cannotWrite(L10n.string(.errorTargetExists))
        }

        do {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: tempURL)
            keepTemp = true
        } catch {
            try FileManager.default.moveItem(at: tempURL, to: url)
            keepTemp = true
        }
    }

    /// Copy descriptive metadata from the original file, then force orientation
    /// to upright and drop keys that would describe the pre-composited image
    /// or leak an unwatermarked thumbnail.
    static func preservedMetadata(from url: URL) -> [String: Any] {
        let options: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options as CFDictionary),
              let raw = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
        else {
            return [
                kCGImagePropertyOrientation as String: CGImagePropertyOrientation.up.rawValue
            ]
        }
        return filteredMetadata(from: raw)
    }

    /// Test-facing filter used by unit tests without touching the filesystem.
    static func filteredMetadata(from raw: [String: Any]) -> [String: Any] {
        var properties: [String: Any] = [
            kCGImagePropertyOrientation as String: CGImagePropertyOrientation.up.rawValue
        ]
        let dropped: Set<String> = [
            kCGImagePropertyPixelWidth as String,
            kCGImagePropertyPixelHeight as String,
            kCGImagePropertyDepth as String,
            kCGImagePropertyHasAlpha as String,
            kCGImagePropertyIsFloat as String,
            kCGImagePropertyColorModel as String,
            kCGImagePropertyProfileName as String,
            kCGImagePropertyOrientation as String
        ]
        for (key, value) in raw {
            let lowered = key.lowercased()
            if dropped.contains(key) { continue }
            if lowered.contains("thumb") { continue }
            if lowered.contains("heics") || lowered.contains("gif") { continue }
            properties[key] = value
        }
        properties[kCGImagePropertyOrientation as String] = CGImagePropertyOrientation.up.rawValue
        if var tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            tiff[kCGImagePropertyTIFFOrientation as String] = CGImagePropertyOrientation.up.rawValue
            properties[kCGImagePropertyTIFFDictionary as String] = tiff
        }
        return properties
    }
}

enum OutputPath {
    static func uniqueURL(
        original: URL,
        folder: URL,
        ext: String,
        randomRename: Bool,
        suffix: String = "_safephotomark"
    ) -> URL {
        var reserved = Set<String>()
        return allocateUniqueURL(
            original: original,
            folder: folder,
            ext: ext,
            randomRename: randomRename,
            suffix: suffix,
            reserved: &reserved
        )
    }

    /// Choose a unique destination URL, recording it in `reserved` so concurrent
    /// jobs cannot both be assigned the same `-2` name. Call this on the
    /// cooperative/main side *before* starting workers. Names already present
    /// on disk or in `reserved` are skipped.
    static func allocateUniqueURL(
        original: URL,
        folder: URL,
        ext: String,
        randomRename: Bool,
        suffix: String = "_safephotomark",
        reserved: inout Set<String>
    ) -> URL {
        if randomRename {
            return uniqueRandomURL(folder: folder, ext: ext, reserved: &reserved)
        }
        let stem = original.deletingPathExtension().lastPathComponent
        let base = "\(stem)\(suffix)"
        return uniquify(
            folder.appendingPathComponent(base).appendingPathExtension(ext),
            folder: folder,
            stem: base,
            ext: ext,
            reserved: &reserved
        )
    }

    private static func uniqueRandomURL(folder: URL, ext: String, reserved: inout Set<String>) -> URL {
        let fileManager = FileManager.default
        for _ in 0..<32 {
            let stem = randomStem()
            let candidate = folder.appendingPathComponent(stem).appendingPathExtension(ext)
            let key = reservationKey(candidate)
            if !reserved.contains(key), !fileManager.fileExists(atPath: candidate.path) {
                reserved.insert(key)
                return candidate
            }
        }
        let fallback = randomStem()
        return uniquify(
            folder.appendingPathComponent(fallback).appendingPathExtension(ext),
            folder: folder,
            stem: fallback,
            ext: ext,
            reserved: &reserved
        )
    }

    private static func uniquify(
        _ initial: URL,
        folder: URL,
        stem: String,
        ext: String,
        reserved: inout Set<String>
    ) -> URL {
        var candidate = initial
        var n = 2
        let fileManager = FileManager.default
        while reserved.contains(reservationKey(candidate)) || fileManager.fileExists(atPath: candidate.path) {
            candidate = folder
                .appendingPathComponent("\(stem)-\(n)")
                .appendingPathExtension(ext)
            n += 1
        }
        reserved.insert(reservationKey(candidate))
        return candidate
    }

    static func reservationKey(_ url: URL) -> String {
        url.standardizedFileURL.path
    }

    static func randomStem(length: Int = 12) -> String {
        let alphabet = Array("abcdefghijkmnopqrstuvwxyz23456789")
        var generator = SystemRandomNumberGenerator()
        return String((0..<length).map { _ in alphabet.randomElement(using: &generator)! })
    }
}
