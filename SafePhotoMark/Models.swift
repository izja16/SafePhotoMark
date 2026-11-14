import AppKit
import Foundation
import UniformTypeIdentifiers

enum OutputFormat: String, CaseIterable, Identifiable, Sendable, Hashable, Codable {
    case jpeg
    case png
    case heif
    case tiff
    case sameAsSource

    var id: String { rawValue }

    var title: String {
        switch self {
        case .jpeg: return "JPEG"
        case .png: return "PNG"
        case .heif: return "HEIF"
        case .tiff: return "TIFF"
        case .sameAsSource: return L10n.string(.formatSameAsSource)
        }
    }

    /// Whether the quality slider may apply (including “same as source”,
    /// which can resolve to JPEG/HEIF per file).
    var usesLossyQuality: Bool {
        switch self {
        case .jpeg, .heif, .sameAsSource: return true
        case .png, .tiff: return false
        }
    }
}

enum WatermarkMode: String, CaseIterable, Identifiable, Sendable, Hashable, Codable {
    case manual
    case random
    case fixed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .manual: return L10n.string(.modeManual)
        case .random: return L10n.string(.modeRandom)
        case .fixed: return L10n.string(.modeFixed)
        }
    }

    var placesRandomly: Bool { self == .random }
}

enum WatermarkPosition: String, CaseIterable, Identifiable, Sendable, Hashable, Codable {
    case topLeft, topCenter, topRight
    case middleLeft, center, middleRight
    case bottomLeft, bottomCenter, bottomRight

    var id: String { rawValue }

    var title: String {
        switch self {
        case .topLeft: return L10n.string(.posTopLeft)
        case .topCenter: return L10n.string(.posTopCenter)
        case .topRight: return L10n.string(.posTopRight)
        case .middleLeft: return L10n.string(.posMiddleLeft)
        case .center: return L10n.string(.posCenter)
        case .middleRight: return L10n.string(.posMiddleRight)
        case .bottomLeft: return L10n.string(.posBottomLeft)
        case .bottomCenter: return L10n.string(.posBottomCenter)
        case .bottomRight: return L10n.string(.posBottomRight)
        }
    }
}

/// Per-photo watermark placement. Coordinates are normalized (0...1) in
/// top-left image space, with y growing downward. The stored point is the
/// watermark's center.
struct WatermarkPlacement: Equatable, Sendable {
    var centerX: Double
    var centerY: Double
    var isManual: Bool

    var normalizedCenter: CGPoint {
        CGPoint(x: centerX, y: centerY)
    }

    init(centerX: Double, centerY: Double, isManual: Bool) {
        self.centerX = centerX
        self.centerY = centerY
        self.isManual = isManual
    }

    init(normalizedCenter: CGPoint, isManual: Bool) {
        self.centerX = Double(normalizedCenter.x)
        self.centerY = Double(normalizedCenter.y)
        self.isManual = isManual
    }
}

enum PhotoStatus: Equatable, Sendable {
    case waiting
    case processing
    case succeeded(URL)
    case failed(String)

    /// Cached for the four fixed statuses so list rows do not look up the
    /// bundle on every body evaluation. Failure messages are still formatted.
    var label: String {
        switch self {
        case .waiting: return PhotoStatusLabelCache.waiting
        case .processing: return PhotoStatusLabelCache.processing
        case .succeeded: return PhotoStatusLabelCache.succeeded
        case .failed(let message): return L10n.format(.statusFailed, message)
        }
    }
}

/// Lazily resolved, then reused for the process lifetime.
enum PhotoStatusLabelCache {
    static let waiting = L10n.string(.statusWaiting)
    static let processing = L10n.string(.statusProcessing)
    static let succeeded = L10n.string(.statusSucceeded)
}

struct PhotoItem: Identifiable, Equatable {
    let id: UUID
    let url: URL
    let filename: String
    var status: PhotoStatus = .waiting
    var thumbnail: NSImageRef? = nil
    var placement: WatermarkPlacement? = nil
}

/// Thin wrapper so `PhotoItem` does not depend on AppKit at the type-declaration
/// surface of every file. The actual image is stored as `NSImage`.
struct NSImageRef: Equatable {
    let image: NSImage

    static func == (lhs: NSImageRef, rhs: NSImageRef) -> Bool {
        lhs.image === rhs.image
    }
}

/// Immutable `CGImage` crossing concurrency domains. The image itself is not
/// declared Sendable, but decoded bitmaps are treated as value-like after creation.
struct SendableCGImage: @unchecked Sendable {
    let image: CGImage
}

enum SourceImageKind: Sendable, Equatable {
    case jpeg, png, heif, tiff, gif, bmp, webp, other

    /// Mapping used by the “与原图相同” output option.
    /// GIF / BMP / unknown → JPEG; WebP → PNG（保留可能的透明通道，因本应用不写出 WebP）。
    var preferredSameAsSourceFormat: OutputFormat {
        switch self {
        case .jpeg: return .jpeg
        case .png: return .png
        case .heif: return .heif
        case .tiff: return .tiff
        case .webp: return .png
        case .gif, .bmp, .other: return .jpeg
        }
    }
}

enum ImagePipelineError: LocalizedError, Sendable {
    case cannotOpenSource
    case emptySource
    case cannotCreateImage
    case cannotConvertColorSpace
    case cannotCreateDestination
    case finalizeFailed
    case cannotWrite(String)
    case watermarkInvalid
    case cancelled

    var errorDescription: String? {
        switch self {
        case .cannotOpenSource: return L10n.string(.errorCannotOpenSource)
        case .emptySource: return L10n.string(.errorEmptySource)
        case .cannotCreateImage: return L10n.string(.errorCannotCreateImage)
        case .cannotConvertColorSpace: return L10n.string(.errorCannotConvertColorSpace)
        case .cannotCreateDestination: return L10n.string(.errorCannotCreateDestination)
        case .finalizeFailed: return L10n.string(.errorFinalizeFailed)
        case .cannotWrite(let message): return message
        case .watermarkInvalid: return L10n.string(.errorWatermarkInvalid)
        case .cancelled: return L10n.string(.errorCancelled)
        }
    }
}

struct ExportSettings: Sendable, Equatable {
    var sizePercent: Double
    var position: WatermarkPosition
    var watermarkMode: WatermarkMode
    var opacity: Double
    var outputFormat: OutputFormat
    var quality: Double
    var stripMetadata: Bool
    var randomRename: Bool
}

enum ImageAlpha {
    static func isOpaque(_ image: CGImage) -> Bool {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast:
            return true
        default:
            return false
        }
    }
}

enum SupportedTypes {
    static let imageExtensions: Set<String> = [
        "jpg", "jpeg", "jpe", "jfif",
        "png",
        "heic", "heif", "hif",
        "tif", "tiff",
        "gif",
        "bmp", "dib",
        "webp"
    ]

    static let watermarkExtensions: Set<String> = ["png", "svg"]

    static var photoContentTypes: [UTType] {
        var types: [UTType] = [.image, .jpeg, .png, .heic, .heif, .tiff, .gif, .bmp, .webP]
        if let hif = UTType(filenameExtension: "hif") {
            types.append(hif)
        }
        return types
    }

    static var watermarkContentTypes: [UTType] {
        var types: [UTType] = [.png]
        if let svg = UTType(filenameExtension: "svg") {
            types.append(svg)
        }
        if let svgImage = UTType("public.svg-image") {
            types.append(svgImage)
        }
        return types
    }

    static func isSupportedImage(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        if imageExtensions.contains(ext) { return true }
        if let type = UTType(filenameExtension: ext), type.conforms(to: .image), ext != "svg" {
            return true
        }
        return false
    }

    static func isWatermarkFile(_ url: URL) -> Bool {
        watermarkExtensions.contains(url.pathExtension.lowercased())
    }
}
