import CoreGraphics
import Foundation

enum WatermarkCompositor {
    static let sizePercentRange: ClosedRange<Double> = 5 ... 80
    static let defaultSizePercent: Double = 20
    static let defaultOpacity: Double = 1.0

    /// The central fraction of the image that random placement must avoid
    /// (30% × 30%, i.e. 35%–65% on each axis).
    static let excludedCenterFraction: CGFloat = 0.30

    static func markSize(canvas: CGSize, percent: Double, aspect: CGFloat) -> CGSize {
        let shorter = min(canvas.width, canvas.height)
        let clamped = min(max(percent, sizePercentRange.lowerBound), sizePercentRange.upperBound)
        var width = shorter * CGFloat(clamped / 100.0)
        var height = width / max(aspect, 0.0001)

        // The mark must stay fully inside the canvas, even at high percentages.
        if width > canvas.width {
            let scale = canvas.width / width
            width *= scale
            height *= scale
        }
        if height > canvas.height {
            let scale = canvas.height / height
            width *= scale
            height *= scale
        }
        return CGSize(width: max(1, width), height: max(1, height))
    }

    /// Rect in **top-left** image coordinates (y grows downward, as in a photo).
    static func placementRect(
        canvas: CGSize,
        mark: CGSize,
        position: WatermarkPosition,
        marginRatio: CGFloat = 0.04
    ) -> CGRect {
        let canvasWidth = canvas.width
        let canvasHeight = canvas.height
        let markWidth = min(mark.width, canvasWidth)
        let markHeight = min(mark.height, canvasHeight)
        let maxMargin = min(canvasWidth - markWidth, canvasHeight - markHeight) / 2
        let margin = max(0, min(maxMargin, min(canvasWidth, canvasHeight) * marginRatio))

        func aligned(xAlign: CGFloat, yAlign: CGFloat) -> CGRect {
            let availableWidth = canvasWidth - markWidth - margin * 2
            let availableHeight = canvasHeight - markHeight - margin * 2
            let x = margin + max(0, availableWidth) * xAlign
            let y = margin + max(0, availableHeight) * yAlign
            return CGRect(x: x, y: y, width: markWidth, height: markHeight)
        }

        switch position {
        case .topLeft: return aligned(xAlign: 0, yAlign: 0)
        case .topCenter: return aligned(xAlign: 0.5, yAlign: 0)
        case .topRight: return aligned(xAlign: 1, yAlign: 0)
        case .middleLeft: return aligned(xAlign: 0, yAlign: 0.5)
        case .center: return aligned(xAlign: 0.5, yAlign: 0.5)
        case .middleRight: return aligned(xAlign: 1, yAlign: 0.5)
        case .bottomLeft: return aligned(xAlign: 0, yAlign: 1)
        case .bottomCenter: return aligned(xAlign: 0.5, yAlign: 1)
        case .bottomRight: return aligned(xAlign: 1, yAlign: 1)
        }
    }

    /// Resolves a watermark rectangle and the placement that produced it.
    /// If `existing` is set, its center is reused (and clamped so the mark stays inside).
    /// Otherwise a random position avoiding the central 30%, or a preset, is used.
    static func resolvePlacement(
        canvas: CGSize,
        mark: CGSize,
        position: WatermarkPosition,
        random: Bool,
        existing: WatermarkPlacement?,
        rng: inout some RandomNumberGenerator
    ) -> (rect: CGRect, placement: WatermarkPlacement) {
        if let existing {
            let rect = rect(from: existing, canvas: canvas, mark: mark)
            let placement = WatermarkPlacement(
                normalizedCenter: CGPoint(
                    x: rect.midX / max(canvas.width, 1),
                    y: rect.midY / max(canvas.height, 1)
                ),
                isManual: existing.isManual
            )
            return (rect, placement)
        }
        if random {
            let rect = randomRectAvoidingCenter(canvas: canvas, mark: mark, rng: &rng)
            let placement = WatermarkPlacement(
                normalizedCenter: CGPoint(
                    x: rect.midX / max(canvas.width, 1),
                    y: rect.midY / max(canvas.height, 1)
                ),
                isManual: false
            )
            return (rect, placement)
        }
        let rect = placementRect(canvas: canvas, mark: mark, position: position)
        let placement = WatermarkPlacement(
            normalizedCenter: CGPoint(
                x: rect.midX / max(canvas.width, 1),
                y: rect.midY / max(canvas.height, 1)
            ),
            isManual: false
        )
        return (rect, placement)
    }

    static func rect(from placement: WatermarkPlacement, canvas: CGSize, mark: CGSize) -> CGRect {
        let center = clampedCenter(canvas: canvas, mark: mark, normalized: placement.normalizedCenter)
        return CGRect(
            x: center.x * canvas.width - mark.width / 2,
            y: center.y * canvas.height - mark.height / 2,
            width: mark.width,
            height: mark.height
        )
    }

    static func clampedCenter(canvas: CGSize, mark: CGSize, normalized: CGPoint) -> CGPoint {
        let width = max(canvas.width, 1)
        let height = max(canvas.height, 1)
        let halfX = (mark.width / 2) / width
        let halfY = (mark.height / 2) / height
        let minX = min(halfX, 1 - halfX)
        let maxX = max(halfX, 1 - halfX)
        let minY = min(halfY, 1 - halfY)
        let maxY = max(halfY, 1 - halfY)
        return CGPoint(
            x: min(max(normalized.x, minX), maxX),
            y: min(max(normalized.y, minY), maxY)
        )
    }

    /// The watermark stays fully inside the canvas. Its center is randomized
    /// anywhere except the central 30% × 30% region. If that constraint leaves
    /// no room (a very large watermark), a valid in-canvas position is used.
    static func randomRectAvoidingCenter(
        canvas: CGSize,
        mark: CGSize,
        rng: inout some RandomNumberGenerator
    ) -> CGRect {
        let markWidth = min(mark.width, canvas.width)
        let markHeight = min(mark.height, canvas.height)
        let halfW = markWidth / 2
        let halfH = markHeight / 2

        let validMinX = halfW
        let validMaxX = canvas.width - halfW
        let validMinY = halfH
        let validMaxY = canvas.height - halfH

        func rectAt(centerX: CGFloat, centerY: CGFloat) -> CGRect {
            CGRect(x: centerX - halfW, y: centerY - halfH, width: markWidth, height: markHeight)
        }

        func randomInValidBox() -> CGRect {
            let cx: CGFloat
            let cy: CGFloat
            if validMinX <= validMaxX {
                cx = CGFloat.random(in: validMinX ... validMaxX, using: &rng)
            } else {
                cx = canvas.width / 2
            }
            if validMinY <= validMaxY {
                cy = CGFloat.random(in: validMinY ... validMaxY, using: &rng)
            } else {
                cy = canvas.height / 2
            }
            return rectAt(centerX: cx, centerY: cy)
        }

        guard validMinX <= validMaxX, validMinY <= validMaxY else {
            return rectAt(centerX: canvas.width / 2, centerY: canvas.height / 2)
        }

        let inset = excludedCenterFraction / 2
        let forbMinX = canvas.width * (0.5 - inset)
        let forbMaxX = canvas.width * (0.5 + inset)
        let forbMinY = canvas.height * (0.5 - inset)
        let forbMaxY = canvas.height * (0.5 + inset)

        var regions: [CGRect] = []

        func appendRegion(x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
            // Axes are judged independently: a zero-height but non-zero-width
            // region (a full-height watermark) must still be sampled, and vice versa.
            guard width >= 0, height >= 0, width > 0 || height > 0 else { return }
            regions.append(CGRect(x: x, y: y, width: width, height: height))
        }

        func measure(_ region: CGRect) -> CGFloat {
            let width = max(0, region.width)
            let height = max(0, region.height)
            if width > 0, height > 0 { return width * height }
            if width > 0 { return width }
            if height > 0 { return height }
            return 0
        }

        // Top and bottom strips span the full valid width.
        let topMaxY = min(validMaxY, forbMinY)
        appendRegion(x: validMinX, y: validMinY, width: validMaxX - validMinX, height: topMaxY - validMinY)
        let bottomMinY = max(validMinY, forbMaxY)
        appendRegion(x: validMinX, y: bottomMinY, width: validMaxX - validMinX, height: validMaxY - bottomMinY)

        // Left and right regions only cover the vertical band of the forbidden box,
        // so corners are not counted twice. Include a zero-height mid band so a
        // full-height watermark can still avoid the center on X.
        let midMinY = max(validMinY, forbMinY)
        let midMaxY = min(validMaxY, forbMaxY)
        if midMaxY >= midMinY {
            let leftMaxX = min(validMaxX, forbMinX)
            appendRegion(x: validMinX, y: midMinY, width: leftMaxX - validMinX, height: midMaxY - midMinY)
            let rightMinX = max(validMinX, forbMaxX)
            appendRegion(x: rightMinX, y: midMinY, width: validMaxX - rightMinX, height: midMaxY - midMinY)
        }

        let weights = regions.map(measure)
        let total = weights.reduce(0, +)
        guard total > 0 else {
            return randomInValidBox()
        }

        var pick = CGFloat.random(in: 0 ..< total, using: &rng)
        for (index, region) in regions.enumerated() {
            let weight = weights[index]
            if pick < weight || index == regions.count - 1 {
                let cx = region.width <= 0 ? region.midX : CGFloat.random(in: region.minX ... region.maxX, using: &rng)
                let cy = region.height <= 0 ? region.midY : CGFloat.random(in: region.minY ... region.maxY, using: &rng)
                return rectAt(centerX: cx, centerY: cy)
            }
            pick -= weight
        }
        return randomInValidBox()
    }

    /// Whether a legal in-canvas placement exists whose center is outside the
    /// central 30% × 30% exclusion. Used by tests and documentation of the fallback.
    static func canAvoidExcludedCenter(canvas: CGSize, mark: CGSize) -> Bool {
        let markWidth = min(mark.width, canvas.width)
        let markHeight = min(mark.height, canvas.height)
        let halfW = markWidth / 2
        let halfH = markHeight / 2
        let validMinX = halfW
        let validMaxX = canvas.width - halfW
        let validMinY = halfH
        let validMaxY = canvas.height - halfH
        guard validMinX <= validMaxX, validMinY <= validMaxY else { return false }

        let inset = excludedCenterFraction / 2
        let forbMinX = canvas.width * (0.5 - inset)
        let forbMaxX = canvas.width * (0.5 + inset)
        let forbMinY = canvas.height * (0.5 - inset)
        let forbMaxY = canvas.height * (0.5 + inset)
        let validXInsideForbidden = validMinX >= forbMinX && validMaxX <= forbMaxX
        let validYInsideForbidden = validMinY >= forbMinY && validMaxY <= forbMaxY
        return !(validXInsideForbidden && validYInsideForbidden)
    }

    /// Bitmap style used when flattening the base and drawing the watermark
    /// in a single pass. JPEG fills opaque white (`noneSkipLast`); PNG / HEIF /
    /// TIFF keep a premultiplied alpha channel.
    enum ComposeCanvasStyle: Sendable {
        case opaqueWhite
        case premultipliedLast

        static func forOutputFormat(_ format: OutputFormat) -> ComposeCanvasStyle {
            format == .jpeg ? .opaqueWhite : .premultipliedLast
        }
    }

    static func compose(
        base: CGImage,
        watermark: CGImage,
        rectTopLeft: CGRect,
        opacity: Double,
        format: OutputFormat
    ) throws -> CGImage {
        try compose(
            base: base,
            watermark: watermark,
            rectTopLeft: rectTopLeft,
            opacity: opacity,
            canvasStyle: .forOutputFormat(format)
        )
    }

    static func compose(
        base: CGImage,
        watermark: CGImage,
        rectTopLeft: CGRect,
        opacity: Double,
        canvasStyle: ComposeCanvasStyle
    ) throws -> CGImage {
        let width = base.width
        let height = base.height
        let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
        let bitmapInfo = canvasStyle == .opaqueWhite
            ? CGImageAlphaInfo.noneSkipLast.rawValue
            : CGImageAlphaInfo.premultipliedLast.rawValue
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
        if canvasStyle == .opaqueWhite {
            ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        ctx.draw(base, in: CGRect(x: 0, y: 0, width: width, height: height))

        // Convert top-left photo coordinates to a bottom-left bitmap context.
        let cgRect = CGRect(
            x: rectTopLeft.origin.x,
            y: CGFloat(height) - rectTopLeft.origin.y - rectTopLeft.height,
            width: rectTopLeft.width,
            height: rectTopLeft.height
        )
        ctx.saveGState()
        ctx.setAlpha(CGFloat(min(max(opacity, 0), 1)))
        ctx.setBlendMode(.normal)
        ctx.draw(watermark, in: cgRect)
        ctx.restoreGState()

        guard let out = ctx.makeImage() else {
            throw ImagePipelineError.cannotConvertColorSpace
        }
        return out
    }
}
