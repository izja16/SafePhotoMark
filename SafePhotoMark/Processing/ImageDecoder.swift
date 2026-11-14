import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ImageDecoder {
    static func kind(for uti: String, filename: String? = nil) -> SourceImageKind {
        let lowered = uti.lowercased()
        if let type = UTType(uti) {
            if type.conforms(to: .jpeg) { return .jpeg }
            if type.conforms(to: .png) { return .png }
            if type.conforms(to: .heic) || type.conforms(to: .heif) { return .heif }
            if type.conforms(to: .tiff) { return .tiff }
            if type.conforms(to: .gif) { return .gif }
            if type.conforms(to: .bmp) { return .bmp }
            if type == .webP { return .webp }
        }
        if lowered.contains("heic") || lowered.contains("heif") { return .heif }
        if lowered.contains("webp") { return .webp }
        if let filename {
            switch URL(fileURLWithPath: filename).pathExtension.lowercased() {
            case "jpg", "jpeg", "jpe", "jfif": return .jpeg
            case "png": return .png
            case "heic", "heif", "hif": return .heif
            case "tif", "tiff": return .tiff
            case "gif": return .gif
            case "bmp", "dib": return .bmp
            case "webp": return .webp
            default: break
            }
        }
        return .other
    }

    /// Best-effort source kind without decoding pixels. Prefers ImageIO's type,
    /// then falls back to the filename extension.
    static func sourceKind(of url: URL) -> SourceImageKind {
        let options: [CFString: Any] = [kCGImageSourceShouldCache: false]
        if let source = CGImageSourceCreateWithURL(url as CFURL, options as CFDictionary),
           let uti = CGImageSourceGetType(source) as String? {
            return kind(for: uti, filename: url.lastPathComponent)
        }
        return kind(for: "", filename: url.lastPathComponent)
    }

    /// Pixel width × height from ImageIO properties, ignoring orientation
    /// (area is unchanged when width and height are swapped).
    static func pixelArea(of url: URL) -> Int? {
        let options: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options as CFDictionary),
              CGImageSourceGetCount(source) > 0,
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
        else {
            return nil
        }
        let width = intProperty(props, kCGImagePropertyPixelWidth)
        let height = intProperty(props, kCGImagePropertyPixelHeight)
        guard width > 0, height > 0 else { return nil }
        return width * height
    }

    /// Decode the first frame, bake EXIF/TIFF orientation into upright pixels,
    /// then redraw into a fresh sRGB bitmap so no source metadata or camera ICC
    /// travels with the `CGImage`. Used for previews and watermark loading.
    static func decode(url: URL, maxPixelSize: Int? = nil) throws -> DecodedImage {
        let raw = try decodeRaw(url: url, maxPixelSize: maxPixelSize)
        let clean = try flattenToSRGB(raw.cgImage)
        return DecodedImage(
            cgImage: clean,
            kind: raw.kind,
            sourceUTI: raw.sourceUTI
        )
    }

    /// Decode the first frame and bake orientation, without an extra sRGB flatten.
    /// Used on the export path, where `WatermarkCompositor.compose` already redraws
    /// the base into a fresh sRGB context.
    ///
    /// Full-size export (`maxPixelSize == nil`) always uses
    /// `CGImageSourceCreateImageAtIndex` and bakes orientation into the pixels.
    /// The ImageIO thumbnail API is used only for previews and thumbnails.
    static func decodeRaw(url: URL, maxPixelSize: Int? = nil) throws -> DecodedImage {
        let sourceOptions: [CFString: Any] = [
            kCGImageSourceShouldCache: false,
            kCGImageSourceShouldAllowFloat: false
        ]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions as CFDictionary) else {
            throw ImagePipelineError.cannotOpenSource
        }
        guard CGImageSourceGetCount(source) > 0 else {
            throw ImagePipelineError.emptySource
        }

        // Always take frame 0: animated GIF / HEICS become a still of the first frame.
        let index = 0
        let uti = (CGImageSourceGetType(source) as String?) ?? "public.image"
        let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [String: Any]
        let pixelWidth = intProperty(props, kCGImagePropertyPixelWidth)
        let pixelHeight = intProperty(props, kCGImagePropertyPixelHeight)
        let hasValidSize = props != nil && pixelWidth > 0 && pixelHeight > 0

        let raw: CGImage
        if let maxPixelSize {
            if hasValidSize {
                let longEdge = max(pixelWidth, pixelHeight)
                let cap = min(max(maxPixelSize, 1), longEdge)
                // Preview / thumbnail path only. `CreateThumbnailWithTransform`
                // applies the orientation tag so the resulting pixels are upright.
                let thumbOptions: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: cap,
                    kCGImageSourceShouldCacheImmediately: true
                ]
                guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, thumbOptions as CFDictionary) else {
                    throw ImagePipelineError.cannotCreateImage
                }
                raw = image
            } else {
                // Missing or zero pixel size: never take the thumbnail path (it would
                // cap to 1 and can emit a 1×1 image). Load the full frame and bake
                // orientation, then optionally downsample.
                guard let image = CGImageSourceCreateImageAtIndex(source, index, sourceOptions as CFDictionary) else {
                    throw ImagePipelineError.cannotCreateImage
                }
                var upright = try applyingOrientation(image, orientationProperty(props))
                if max(upright.width, upright.height) > maxPixelSize {
                    upright = try downsample(upright, maxPixelSize: maxPixelSize)
                }
                raw = upright
            }
        } else {
            guard let image = CGImageSourceCreateImageAtIndex(source, index, sourceOptions as CFDictionary) else {
                throw ImagePipelineError.cannotCreateImage
            }
            raw = try applyingOrientation(image, orientationProperty(props))
        }

        return DecodedImage(
            cgImage: raw,
            kind: kind(for: uti, filename: url.lastPathComponent),
            sourceUTI: uti
        )
    }

    /// Draw into a brand-new sRGB bitmap. The output `CGImage` has no attached
    /// metadata dictionaries and does not keep a camera-specific ICC profile.
    static func flattenToSRGB(_ image: CGImage) throws -> CGImage {
        let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else {
            throw ImagePipelineError.cannotCreateImage
        }

        // Always 8-bit premultiplied RGBA. Opaque photos simply get alpha = 1,
        // which is discarded later for JPEG.
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        guard let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: srgb,
            bitmapInfo: bitmapInfo
        ) else {
            throw ImagePipelineError.cannotConvertColorSpace
        }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let out = ctx.makeImage() else {
            throw ImagePipelineError.cannotConvertColorSpace
        }
        return out
    }

    /// Composite onto an opaque white background (used for JPEG, which has no alpha).
    /// The normal JPEG export path flattens inside `WatermarkCompositor.compose`
    /// instead; this remains for abnormal / fallback cases.
    static func flattenOpaqueWhite(_ image: CGImage) throws -> CGImage {
        let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
        let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue
        let width = image.width
        let height = image.height
        let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: srgb,
            bitmapInfo: bitmapInfo
        ) ?? CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: srgb,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        guard let ctx else {
            throw ImagePipelineError.cannotConvertColorSpace
        }
        ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let out = ctx.makeImage() else {
            throw ImagePipelineError.cannotConvertColorSpace
        }
        return out
    }

    static func downsample(_ image: CGImage, maxPixelSize: Int) throws -> CGImage {
        let longEdge = max(image.width, image.height)
        guard longEdge > maxPixelSize, maxPixelSize > 0 else { return image }
        let scale = CGFloat(maxPixelSize) / CGFloat(longEdge)
        let width = max(1, Int((CGFloat(image.width) * scale).rounded(.down)))
        let height = max(1, Int((CGFloat(image.height) * scale).rounded(.down)))
        return try resample(image, width: width, height: height)
    }

    static func resample(_ image: CGImage, width: Int, height: Int) throws -> CGImage {
        let width = max(1, width)
        let height = max(1, height)
        if image.width == width, image.height == height { return image }
        let srgb = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
        let opaque = ImageAlpha.isOpaque(image)
        let bitmapInfo = opaque
            ? CGImageAlphaInfo.noneSkipLast.rawValue
            : CGImageAlphaInfo.premultipliedLast.rawValue
        let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: srgb,
            bitmapInfo: bitmapInfo
        ) ?? CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        guard let ctx else {
            throw ImagePipelineError.cannotCreateImage
        }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let out = ctx.makeImage() else {
            throw ImagePipelineError.cannotCreateImage
        }
        return out
    }

    static func applyingOrientation(
        _ image: CGImage,
        _ orientation: CGImagePropertyOrientation
    ) throws -> CGImage {
        guard orientation != .up else { return image }
        let ciImage = CIImage(cgImage: image).oriented(orientation)
        let context = CIContext(options: [
            .cacheIntermediates: false,
            .workingColorSpace: NSNull()
        ])
        let colorSpace = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
        guard let out = context.createCGImage(ciImage, from: ciImage.extent, format: .RGBA8, colorSpace: colorSpace) else {
            throw ImagePipelineError.cannotCreateImage
        }
        return out
    }

    static func orientationProperty(_ props: [String: Any]?) -> CGImagePropertyOrientation {
        let raw = intProperty(props, kCGImagePropertyOrientation)
        return CGImagePropertyOrientation(rawValue: UInt32(raw)) ?? .up
    }

    static func intProperty(_ props: [String: Any]?, _ key: CFString) -> Int {
        (props?[key as String] as? NSNumber)?.intValue ?? 0
    }
}

struct DecodedImage: Sendable {
    let image: SendableCGImage
    let kind: SourceImageKind
    let sourceUTI: String

    var cgImage: CGImage { image.image }

    init(cgImage: CGImage, kind: SourceImageKind, sourceUTI: String) {
        self.image = SendableCGImage(image: cgImage)
        self.kind = kind
        self.sourceUTI = sourceUTI
    }
}
