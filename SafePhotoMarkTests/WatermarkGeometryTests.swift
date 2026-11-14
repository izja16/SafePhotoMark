import XCTest
@testable import SafePhotoMark

final class WatermarkGeometryTests: XCTestCase {
    func testTenThousandRandomPlacementsStayInCanvasAndAvoidCenter() {
        let cases: [(canvas: CGSize, percent: Double, aspect: CGFloat)] = [
            (CGSize(width: 4032, height: 3024), 20, 0.2), // full-height watermark from the review
            (CGSize(width: 4032, height: 3024), 20, 1.5),
            (CGSize(width: 4032, height: 3024), 5, 1),
            (CGSize(width: 4032, height: 3024), 50, 1),
            (CGSize(width: 8064, height: 6048), 20, 0.2),
            (CGSize(width: 1000, height: 1000), 20, 1),
            (CGSize(width: 1200, height: 800), 80, 3)
        ]

        for testCase in cases {
            assertPlacements(
                canvas: testCase.canvas,
                percent: testCase.percent,
                aspect: testCase.aspect,
                iterations: 10_000
            )
        }
    }

    func testFullHeightCaseCanAvoidCenterOnX() {
        let canvas = CGSize(width: 4032, height: 3024)
        let mark = WatermarkCompositor.markSize(canvas: canvas, percent: 20, aspect: 0.2)
        XCTAssertEqual(mark.height, canvas.height, accuracy: 0.01)
        XCTAssertTrue(WatermarkCompositor.canAvoidExcludedCenter(canvas: canvas, mark: mark))
    }

    func testNineGridMarginsCollapseWhenMarkFillsCanvas() {
        let canvas = CGSize(width: 1000, height: 1000)
        // 80% of 1000 = 800 still has margin; use a mark that almost fills the canvas.
        let huge = CGSize(width: 990, height: 990)
        for position in WatermarkPosition.allCases {
            let rect = WatermarkCompositor.placementRect(canvas: canvas, mark: huge, position: position)
            XCTAssertGreaterThanOrEqual(rect.minX, -0.001)
            XCTAssertGreaterThanOrEqual(rect.minY, -0.001)
            XCTAssertLessThanOrEqual(rect.maxX, canvas.width + 0.001)
            XCTAssertLessThanOrEqual(rect.maxY, canvas.height + 0.001)
        }
    }

    private func assertPlacements(
        canvas: CGSize,
        percent: Double,
        aspect: CGFloat,
        iterations: Int,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let mark = WatermarkCompositor.markSize(canvas: canvas, percent: percent, aspect: aspect)
        let canAvoid = WatermarkCompositor.canAvoidExcludedCenter(canvas: canvas, mark: mark)
        var rng = SystemRandomNumberGenerator()
        var insideForbidden = 0

        for _ in 0..<iterations {
            let rect = WatermarkCompositor.randomRectAvoidingCenter(canvas: canvas, mark: mark, rng: &rng)
            XCTAssertGreaterThanOrEqual(rect.minX, -0.001, "out of canvas minX", file: file, line: line)
            XCTAssertGreaterThanOrEqual(rect.minY, -0.001, "out of canvas minY", file: file, line: line)
            XCTAssertLessThanOrEqual(rect.maxX, canvas.width + 0.001, "out of canvas maxX", file: file, line: line)
            XCTAssertLessThanOrEqual(rect.maxY, canvas.height + 0.001, "out of canvas maxY", file: file, line: line)

            let nx = rect.midX / canvas.width
            let ny = rect.midY / canvas.height
            let lo = 0.35
            let hi = 0.65
            let inForbidden = nx > lo && nx < hi && ny > lo && ny < hi
            if inForbidden {
                insideForbidden += 1
            }
        }

        if canAvoid {
            XCTAssertEqual(
                insideForbidden,
                0,
                "center entered the excluded 30%×30% region \(insideForbidden) times for canvas \(canvas), percent \(percent), aspect \(aspect)",
                file: file,
                line: line
            )
        }
    }
}
