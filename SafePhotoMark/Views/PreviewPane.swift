import AppKit
import SwiftUI

struct PreviewPane: View {
    @EnvironmentObject private var model: AppModel
    @State private var isDragging = false
    @State private var isHoveringMark = false
    @State private var dragStartCenter: CGPoint?
    @State private var dragCenter: CGPoint?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer(minLength: 0)
                if let name = model.selectedPhoto?.filename {
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)
            .padding(.bottom, 8)

            ZStack {
                CheckerboardBackground()
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                if let image = model.previewImage {
                    GeometryReader { geo in
                        let display = fittedRect(imageSize: image.size, in: geo.size)
                        ZStack(alignment: .topLeading) {
                            Image(nsImage: image)
                                .resizable()
                                .interpolation(.high)
                                .frame(width: display.width, height: display.height)
                                .position(x: display.midX, y: display.midY)

                            if let overlay = watermarkOverlay(in: display) {
                                Image(nsImage: overlay.image)
                                    .resizable()
                                    .interpolation(.high)
                                    .opacity(model.opacity)
                                    .frame(width: overlay.frame.width, height: overlay.frame.height)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                                            .stroke(
                                                Color.white.opacity(isHoveringMark || isDragging ? 0.95 : 0.55),
                                                style: StrokeStyle(
                                                    lineWidth: isHoveringMark || isDragging ? 1.5 : 1,
                                                    dash: [5, 4]
                                                )
                                            )
                                            .shadow(color: .black.opacity(0.35), radius: 1, y: 0.5)
                                    )
                                    .position(x: overlay.frame.midX, y: overlay.frame.midY)
                                    .onHover { hovering in
                                        isHoveringMark = hovering
                                        if hovering {
                                            NSCursor.openHand.push()
                                        } else if !isDragging {
                                            NSCursor.pop()
                                        }
                                    }
                                    .gesture(dragGesture(display: display, canvas: image.size, mark: overlay.mark))
                            }
                        }
                        .frame(width: geo.size.width, height: geo.size.height)
                    }
                    .padding(12)
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: "eye")
                            .font(.system(size: 28))
                            .foregroundStyle(.tertiary)
                        Text(model.previewNote ?? L10n.string(.previewSelectPhoto))
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 12)

            HStack(alignment: .top, spacing: 8) {
                if model.previewImage != nil, model.watermark != nil {
                    Text(model.previewNote ?? L10n.string(.previewDragHint))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else if let note = model.previewNote, model.previewImage != nil {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                if model.previewImage != nil, model.watermark != nil {
                    if model.selectedPlacementIsManual {
                        Button(L10n.string(.resetPosition)) {
                            model.resetSelectedPlacement()
                        }
                        .appButton(.small)
                        .disabled(model.isProcessing)
                    }
                    if model.watermarkMode == .random {
                        Button(L10n.string(.rerollPosition)) {
                            model.rerollSelectedPlacement()
                        }
                        .appButton(.small)
                        .disabled(model.isProcessing)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 36)
        }
    }

    private func fittedRect(imageSize: CGSize, in container: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0, container.width > 0, container.height > 0 else {
            return .zero
        }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let size = CGSize(width: imageSize.width * scale, height: imageSize.height * scale)
        let origin = CGPoint(x: (container.width - size.width) / 2, y: (container.height - size.height) / 2)
        return CGRect(origin: origin, size: size)
    }

    private func watermarkOverlay(in display: CGRect) -> (image: NSImage, frame: CGRect, mark: CGSize)? {
        guard let watermark = model.watermark else { return nil }
        let canvas = model.previewImage?.size ?? .zero
        guard canvas.width > 0, canvas.height > 0, display.width > 0 else { return nil }

        let mark: CGSize
        let rect: CGRect
        if let dragCenter {
            mark = WatermarkCompositor.markSize(
                canvas: canvas,
                percent: model.sizePercent,
                aspect: watermark.aspectRatio
            )
            let center = WatermarkCompositor.clampedCenter(canvas: canvas, mark: mark, normalized: dragCenter)
            rect = CGRect(
                x: center.x * canvas.width - mark.width / 2,
                y: center.y * canvas.height - mark.height / 2,
                width: mark.width,
                height: mark.height
            )
        } else {
            guard let layout = model.displayLayout(canvas: canvas) else { return nil }
            mark = layout.mark
            rect = layout.rect
        }

        let scale = display.width / canvas.width
        let frame = CGRect(
            x: display.minX + rect.minX * scale,
            y: display.minY + rect.minY * scale,
            width: rect.width * scale,
            height: rect.height * scale
        )
        let backing = NSScreen.main?.backingScaleFactor ?? 2
        let image = watermark.image(forDisplayWidth: frame.width, scale: backing) ?? watermark.preview
        guard let image else { return nil }
        return (image, frame, mark)
    }

    private func dragGesture(display: CGRect, canvas: CGSize, mark: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { value in
                guard !model.isProcessing else { return }
                if dragStartCenter == nil {
                    let start = dragCenter
                        ?? model.displayLayout(canvas: canvas)?.placement.normalizedCenter
                    dragStartCenter = start
                    isDragging = true
                    NSCursor.closedHand.set()
                }
                guard let start = dragStartCenter, display.width > 0, display.height > 0 else { return }
                let next = CGPoint(
                    x: start.x + value.translation.width / display.width,
                    y: start.y + value.translation.height / display.height
                )
                dragCenter = next
            }
            .onEnded { _ in
                if let current = dragCenter {
                    model.setManualCenter(current, canvas: canvas, mark: mark)
                }
                dragStartCenter = nil
                dragCenter = nil
                isDragging = false
                if isHoveringMark {
                    NSCursor.openHand.set()
                } else {
                    NSCursor.pop()
                }
            }
    }
}

struct CheckerboardBackground: View {
    var cell: CGFloat = 8

    var body: some View {
        Canvas { context, size in
            let light = Color(nsColor: .controlBackgroundColor)
            let dark = Color(nsColor: .separatorColor).opacity(0.45)
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(light))
            var y: CGFloat = 0
            var row = 0
            while y < size.height {
                var x: CGFloat = 0
                var col = 0
                while x < size.width {
                    if (row + col) % 2 == 0 {
                        context.fill(
                            Path(CGRect(x: x, y: y, width: cell, height: cell)),
                            with: .color(dark)
                        )
                    }
                    x += cell
                    col += 1
                }
                y += cell
                row += 1
            }
        }
    }
}
