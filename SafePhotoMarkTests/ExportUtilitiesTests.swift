import ImageIO
import XCTest
@testable import SafePhotoMark

final class OutputPathTests: XCTestCase {
    func testPreferredNameUsesSafePhotoMarkSuffix() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let original = URL(fileURLWithPath: "/tmp/vacation.jpg")
        let url = OutputPath.uniqueURL(original: original, folder: folder, ext: "jpg", randomRename: false)
        XCTAssertEqual(url.lastPathComponent, "vacation_safephotomark.jpg")
    }

    func testUniquifyAppendsNumericSuffix() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let first = folder.appendingPathComponent("vacation_safephotomark.jpg")
        FileManager.default.createFile(atPath: first.path, contents: Data(), attributes: nil)

        let original = URL(fileURLWithPath: "/tmp/vacation.jpg")
        let url = OutputPath.uniqueURL(original: original, folder: folder, ext: "jpg", randomRename: false)
        XCTAssertEqual(url.lastPathComponent, "vacation_safephotomark-2.jpg")

        FileManager.default.createFile(atPath: url.path, contents: Data(), attributes: nil)
        let third = OutputPath.uniqueURL(original: original, folder: folder, ext: "jpg", randomRename: false)
        XCTAssertEqual(third.lastPathComponent, "vacation_safephotomark-3.jpg")
    }

    func testRandomRenameDoesNotUseOriginalStem() throws {
        let folder = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let original = URL(fileURLWithPath: "/tmp/vacation-photo.jpg")
        let url = OutputPath.uniqueURL(original: original, folder: folder, ext: "png", randomRename: true)
        let stem = url.deletingPathExtension().lastPathComponent
        XCTAssertEqual(url.pathExtension, "png")
        XCTAssertEqual(stem.count, 12)
        XCTAssertFalse(stem.contains("vacation"))
        let allowed = Set("abcdefghijkmnopqrstuvwxyz23456789")
        XCTAssertTrue(Set(stem).isSubset(of: allowed))
    }

    private func temporaryFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("SafePhotoMarkTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

final class PreservedMetadataTests: XCTestCase {
    func testFilteredMetadataDropsGeometryThumbsAndForcesUprightOrientation() {
        let raw: [String: Any] = [
            kCGImagePropertyPixelWidth as String: 4032,
            kCGImagePropertyPixelHeight as String: 3024,
            kCGImagePropertyDepth as String: 16,
            kCGImagePropertyHasAlpha as String: false,
            kCGImagePropertyOrientation as String: CGImagePropertyOrientation.right.rawValue,
            kCGImagePropertyProfileName as String: "Display P3",
            "ThumbnailDictionary": ["Width": 160],
            "MakerNotesThumb": "nope",
            "GIFDictionary": ["Loop": 0],
            "HEICSDictionary": ["Foo": 1],
            kCGImagePropertyGPSDictionary as String: ["Latitude": 31.2],
            kCGImagePropertyTIFFDictionary as String: [
                kCGImagePropertyTIFFOrientation as String: CGImagePropertyOrientation.right.rawValue,
                kCGImagePropertyTIFFArtist as String: "tester"
            ]
        ]

        let filtered = ImageExporter.filteredMetadata(from: raw)

        XCTAssertNil(filtered[kCGImagePropertyPixelWidth as String])
        XCTAssertNil(filtered[kCGImagePropertyPixelHeight as String])
        XCTAssertNil(filtered[kCGImagePropertyDepth as String])
        XCTAssertNil(filtered[kCGImagePropertyHasAlpha as String])
        XCTAssertNil(filtered[kCGImagePropertyProfileName as String])
        XCTAssertNil(filtered["ThumbnailDictionary"])
        XCTAssertNil(filtered["MakerNotesThumb"])
        XCTAssertNil(filtered["GIFDictionary"])
        XCTAssertNil(filtered["HEICSDictionary"])
        XCTAssertEqual(filtered[kCGImagePropertyOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)
        XCTAssertNotNil(filtered[kCGImagePropertyGPSDictionary as String])

        let tiff = filtered[kCGImagePropertyTIFFDictionary as String] as? [String: Any]
        XCTAssertEqual(tiff?[kCGImagePropertyTIFFOrientation as String] as? UInt32, CGImagePropertyOrientation.up.rawValue)
        XCTAssertEqual(tiff?[kCGImagePropertyTIFFArtist as String] as? String, "tester")
    }
}
