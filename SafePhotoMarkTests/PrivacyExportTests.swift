import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import SafePhotoMark

final class PrivacyExportTests: XCTestCase {
    func testJPEGExportStripsPrivateMetadataAndForcesUprightOrientation() throws {
        try assertPrivacyExport(format: .jpeg, embedThumbnail: true, requireSensitiveSourceMetadata: true)
    }

    func testPNGExportStripsPrivateMetadataAndForcesUprightOrientation() throws {
        try assertPrivacyExport(format: .png, embedThumbnail: false, requireSensitiveSourceMetadata: false)
    }

    func testPreservingMetadataStillDropsThumbnailsAndForcesOrientation() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let source = try writeFixture(
            in: folder,
            name: "source.jpg",
            uti: UTType.jpeg,
            orientation: .right,
            storedWidth: 40,
            storedHeight: 20,
            embedThumbnail: true,
            colorSpace: CGColorSpace(name: CGColorSpace.displayP3)!
        )
        try assertFixtureHasSensitiveMetadata(source, expectThumbnail: true, required: true)

        let output = try export(source: source, folder: folder, format: .jpeg, stripMetadata: false)
        let props = try properties(of: output)

        XCTAssertEqual(props[kCGImagePropertyOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)
        assertNoThumbnailKeys(props)

        XCTAssertNotNil(props[kCGImagePropertyGPSDictionary as String], "GPS should remain when stripMetadata is off")
        if let tiff = props[kCGImagePropertyTIFFDictionary as String] as? [String: Any] {
            XCTAssertEqual(tiff[kCGImagePropertyTIFFArtist as String] as? String, "SafePhotoMark Tester")
            XCTAssertEqual(tiff[kCGImagePropertyTIFFOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)
        }
        if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            XCTAssertEqual(exif[kCGImagePropertyExifDateTimeOriginal as String] as? String, "2024:01:02 03:04:05")
        }
    }

    func testExportPixelSizeMatchesOrientedSourceSize() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let source = try writeFixture(
            in: folder,
            name: "rotated.jpg",
            uti: UTType.jpeg,
            orientation: .right,
            storedWidth: 40,
            storedHeight: 20,
            embedThumbnail: true,
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!
        )

        let sourceProps = try properties(of: source)
        let storedWidth = sourceProps[kCGImagePropertyPixelWidth as String] as? Int ?? 0
        let storedHeight = sourceProps[kCGImagePropertyPixelHeight as String] as? Int ?? 0
        XCTAssertEqual(storedWidth, 40)
        XCTAssertEqual(storedHeight, 20)
        XCTAssertEqual(
            sourceProps[kCGImagePropertyOrientation as String] as? UInt32,
            CGImagePropertyOrientation.right.rawValue
        )

        let decoded = try ImageDecoder.decodeRaw(url: source)
        XCTAssertEqual(decoded.cgImage.width, 20, "right-oriented 40×20 should bake to 20×40")
        XCTAssertEqual(decoded.cgImage.height, 40)

        let output = try export(source: source, folder: folder, format: .jpeg, stripMetadata: true)
        let outProps = try properties(of: output)
        XCTAssertEqual(outProps[kCGImagePropertyPixelWidth as String] as? Int, 20)
        XCTAssertEqual(outProps[kCGImagePropertyPixelHeight as String] as? Int, 40)
        XCTAssertEqual(outProps[kCGImagePropertyOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)

        let exportedPixels = try ImageDecoder.decodeRaw(url: output)
        XCTAssertEqual(exportedPixels.cgImage.width, 20)
        XCTAssertEqual(exportedPixels.cgImage.height, 40)
    }

    func testSamePathSVGReplaceDoesNotReuseStaleDocument() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let url = folder.appendingPathComponent("mark.svg")
        try svg(width: 100, height: 50, fill: "red").write(to: url, atomically: true, encoding: .utf8)
        let first = try WatermarkLoader.load(url: url)
        XCTAssertEqual(first.intrinsicSize.width, 100, accuracy: 0.01)
        XCTAssertEqual(first.intrinsicSize.height, 50, accuracy: 0.01)

        // Overwrite the same path with a differently sized SVG.
        try svg(width: 80, height: 80, fill: "blue").write(to: url, atomically: true, encoding: .utf8)
        let second = try WatermarkLoader.load(url: url)
        XCTAssertEqual(second.intrinsicSize.width, 80, accuracy: 0.01)
        XCTAssertEqual(second.intrinsicSize.height, 80, accuracy: 0.01)
        XCTAssertNotEqual(first.intrinsicSize, second.intrinsicSize)
    }

    // MARK: - Helpers

    private func assertPrivacyExport(format: OutputFormat, embedThumbnail: Bool, requireSensitiveSourceMetadata: Bool) throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let ext = ImageExporter.fileExtension(for: format)
        let uti: UTType = format == .png ? .png : .jpeg
        let source = try writeFixture(
            in: folder,
            name: "source.\(ext == "jpg" ? "jpg" : ext)",
            uti: uti,
            orientation: .right,
            storedWidth: 32,
            storedHeight: 16,
            embedThumbnail: embedThumbnail,
            colorSpace: CGColorSpace(name: CGColorSpace.displayP3)!
        )
        try assertFixtureHasSensitiveMetadata(
            source,
            expectThumbnail: embedThumbnail,
            required: requireSensitiveSourceMetadata
        )

        let sourceProfile = (try properties(of: source)[kCGImagePropertyProfileName as String] as? String) ?? ""

        let output = try export(source: source, folder: folder, format: format, stripMetadata: true)
        let props = try properties(of: output)

        XCTAssertNil(props[kCGImagePropertyGPSDictionary as String], "output must not contain {GPS}")
        if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            // ImageIO may synthesize a tiny Exif dict (color space). It must not
            // carry the planted camera tags or MakerNote.
            XCTAssertNil(exif[kCGImagePropertyExifDateTimeOriginal as String])
            XCTAssertNil(exif[kCGImagePropertyExifISOSpeedRatings as String])
            XCTAssertNil(exif[kCGImagePropertyExifMakerNote as String])
            XCTAssertFalse(exif.keys.contains { $0.lowercased().contains("makernote") })
        }
        assertNoMakerNote(props)
        assertNoThumbnailKeys(props)
        XCTAssertEqual(props[kCGImagePropertyOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)

        if let profile = props[kCGImagePropertyProfileName as String] as? String {
            XCTAssertFalse(profile.localizedCaseInsensitiveContains("Display P3"), "source profile leaked: \(profile)")
            if !sourceProfile.isEmpty {
                XCTAssertNotEqual(profile, sourceProfile)
            }
        }

        let pixels = try ImageDecoder.decodeRaw(url: output)
        XCTAssertEqual(pixels.cgImage.width, 16)
        XCTAssertEqual(pixels.cgImage.height, 32)
    }

    private func export(
        source: URL,
        folder: URL,
        format: OutputFormat,
        stripMetadata: Bool
    ) throws -> URL {
        let destination = folder.appendingPathComponent("out.\(ImageExporter.fileExtension(for: format))")
        let settings = ExportSettings(
            sizePercent: 20,
            position: .bottomRight,
            watermarkMode: .fixed,
            opacity: 1,
            outputFormat: format,
            quality: 0.95,
            stripMetadata: stripMetadata,
            randomRename: false
        )
        return try BatchProcessor.processOne(
            source: source,
            destination: destination,
            format: format,
            watermark: try makeWatermark(),
            settings: settings,
            placement: WatermarkPlacement(centerX: 0.8, centerY: 0.8, isManual: false)
        )
    }

    private func writeFixture(
        in folder: URL,
        name: String,
        uti: UTType,
        orientation: CGImagePropertyOrientation,
        storedWidth: Int,
        storedHeight: Int,
        embedThumbnail: Bool,
        colorSpace: CGColorSpace
    ) throws -> URL {
        let url = folder.appendingPathComponent(name)
        let image = try makeSwatch(width: storedWidth, height: storedHeight, colorSpace: colorSpace)
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, uti.identifier as CFString, 1, nil) else {
            XCTFail("could not create destination for \(name)")
            throw ImagePipelineError.cannotCreateDestination
        }
        var destProps: [CFString: Any] = [:]
        if embedThumbnail {
            destProps[kCGImageDestinationEmbedThumbnail] = true
        }
        if uti == .jpeg {
            destProps[kCGImageDestinationLossyCompressionQuality] = 0.95
        }
        CGImageDestinationSetProperties(destination, destProps as CFDictionary)

        let gps: [String: Any] = [
            kCGImagePropertyGPSLatitude as String: 31.2304,
            kCGImagePropertyGPSLongitude as String: 121.4737,
            kCGImagePropertyGPSLatitudeRef as String: "N",
            kCGImagePropertyGPSLongitudeRef as String: "E"
        ]
        let exif: [String: Any] = [
            kCGImagePropertyExifDateTimeOriginal as String: "2024:01:02 03:04:05",
            kCGImagePropertyExifISOSpeedRatings as String: [200],
            kCGImagePropertyExifMakerNote as String: Data("maker-secret".utf8)
        ]
        let tiff: [String: Any] = [
            kCGImagePropertyTIFFOrientation as String: orientation.rawValue,
            kCGImagePropertyTIFFArtist as String: "SafePhotoMark Tester",
            kCGImagePropertyTIFFMake as String: "UnitCam",
            kCGImagePropertyTIFFModel as String: "Fixture"
        ]
        let properties: [String: Any] = [
            kCGImagePropertyOrientation as String: orientation.rawValue,
            kCGImagePropertyGPSDictionary as String: gps,
            kCGImagePropertyExifDictionary as String: exif,
            kCGImagePropertyTIFFDictionary as String: tiff,
            kCGImagePropertyMakerAppleDictionary as String: ["TestNote": "secret"]
        ]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination), "failed to write fixture \(name)")
        return url
    }

    private func assertFixtureHasSensitiveMetadata(_ url: URL, expectThumbnail: Bool, required: Bool) throws {
        let props = try properties(of: url)
        let hasGPS = props[kCGImagePropertyGPSDictionary as String] != nil
        let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any]
        let hasDate = (exif?[kCGImagePropertyExifDateTimeOriginal as String] as? String) == "2024:01:02 03:04:05"
        if required {
            XCTAssertTrue(hasGPS, "fixture must contain GPS so the strip test is meaningful")
            XCTAssertTrue(hasDate, "fixture must contain planted Exif DateTimeOriginal")
        }
        _ = expectThumbnail
    }

    private func assertNoThumbnailKeys(_ props: [String: Any]) {
        let leftover = props.keys.filter { $0.lowercased().contains("thumb") }
        XCTAssertTrue(leftover.isEmpty, "output still has thumbnail keys: \(leftover)")
    }

    private func assertNoMakerNote(_ props: [String: Any]) {
        func walk(_ value: Any) -> Bool {
            if let dict = value as? [String: Any] {
                for (key, child) in dict {
                    if key.lowercased().contains("makernote") || key.lowercased().contains("makerapple") {
                        return true
                    }
                    if walk(child) { return true }
                }
            }
            return false
        }
        XCTAssertFalse(walk(props), "output still contains MakerNote / maker dictionary")
        XCTAssertNil(props[kCGImagePropertyMakerAppleDictionary as String])
    }

    private func properties(of url: URL) throws -> [String: Any] {
        let options: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any]
        else {
            throw ImagePipelineError.cannotOpenSource
        }
        return props
    }

    private func makeSwatch(width: Int, height: Int, colorSpace: CGColorSpace) throws -> CGImage {
        guard let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            throw ImagePipelineError.cannotCreateImage
        }
        ctx.setFillColor(CGColor(colorSpace: colorSpace, components: [0.2, 0.6, 0.3, 1])!)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.setFillColor(CGColor(colorSpace: colorSpace, components: [0.9, 0.1, 0.1, 1])!)
        ctx.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        guard let image = ctx.makeImage() else {
            throw ImagePipelineError.cannotCreateImage
        }
        return image
    }

    private func makeWatermark() throws -> WatermarkPayload {
        let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(
            data: nil,
            width: 8,
            height: 8,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: srgb,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            throw ImagePipelineError.watermarkInvalid
        }
        ctx.setFillColor(CGColor(srgbRed: 1, green: 0, blue: 0, alpha: 0.6))
        ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        guard let image = ctx.makeImage() else {
            throw ImagePipelineError.watermarkInvalid
        }
        return WatermarkPayload(kind: .raster, raster: image, svg: nil, intrinsicSize: CGSize(width: 8, height: 8))
    }

    private func svg(width: Int, height: Int, fill: String) -> String {
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="\(width)" height="\(height)" viewBox="0 0 \(width) \(height)">
          <rect x="0" y="0" width="\(width)" height="\(height)" fill="\(fill)"/>
        </svg>
        """
    }

    private func temporaryFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("SafePhotoMarkPrivacy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

final class OutputAllocationTests: XCTestCase {
    func testReservedSetPreventsDuplicateNamesWithoutTouchingDisk() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let original = URL(fileURLWithPath: "/tmp/vacation.jpg")
        var reserved = Set<String>()
        let first = OutputPath.allocateUniqueURL(
            original: original,
            folder: folder,
            ext: "jpg",
            randomRename: false,
            reserved: &reserved
        )
        XCTAssertEqual(first.lastPathComponent, "vacation_safephotomark.jpg")

        let second = OutputPath.allocateUniqueURL(
            original: original,
            folder: folder,
            ext: "jpg",
            randomRename: false,
            reserved: &reserved
        )
        XCTAssertEqual(second.lastPathComponent, "vacation_safephotomark-2.jpg")
        XCTAssertNotEqual(first.path, second.path)
        XCTAssertEqual(reserved.count, 2)
    }

    func testRecommendedConcurrencyIsConservativeOn16GBClassMachines() {
        let gig: UInt64 = 1024 * 1024 * 1024
        XCTAssertEqual(ExportCoordinator.recommendedConcurrency(physicalMemory: 16 * gig), 2)
        XCTAssertEqual(ExportCoordinator.recommendedConcurrency(physicalMemory: 8 * gig), 2)
        XCTAssertEqual(ExportCoordinator.recommendedConcurrency(physicalMemory: 24 * gig), 3)
        XCTAssertEqual(ExportCoordinator.recommendedConcurrency(physicalMemory: 32 * gig), 4)
        XCTAssertEqual(ExportCoordinator.recommendedConcurrency(physicalMemory: 64 * gig), 4)
    }

    func testCollectImageURLsReportsDiscoveredTotalPastTheCap() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        for index in 1...5 {
            FileManager.default.createFile(
                atPath: folder.appendingPathComponent("photo-\(index).jpg").path,
                contents: Data(),
                attributes: nil
            )
        }
        let result = ImportService.collectImageURLs(
            from: [folder],
            existing: [],
            remainingCapacity: 2
        )
        XCTAssertEqual(result.urls.count, 2)
        XCTAssertTrue(result.truncated)
        XCTAssertEqual(result.discoveredNew, 5)
    }

    private func temporaryFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("SafePhotoMarkAlloc-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
