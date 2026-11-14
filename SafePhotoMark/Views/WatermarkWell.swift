import SwiftUI

struct WatermarkWell: View {
    @EnvironmentObject private var model: AppModel
    @State private var isTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(nsColor: .controlBackgroundColor))
                CheckerboardBackground(cell: 6)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .opacity(0.85)

                if let preview = model.watermark?.preview {
                    Image(nsImage: preview)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .padding(10)
                } else {
                    VStack(spacing: 4) {
                        Image(systemName: "seal")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                        Text(L10n.string(.dropWatermark))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(8)
                }
            }
            .frame(height: 120)
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(
                        isTargeted ? Color.accentColor : Color(nsColor: .separatorColor),
                        style: StrokeStyle(lineWidth: isTargeted ? 2 : 1, dash: [6, 4])
                    )
            )
            .onDrop(of: [.fileURL], isTargeted: $isTargeted, perform: model.handleWatermarkDrop)
            .allowsHitTesting(!model.isProcessing)

            HStack {
                if let name = model.watermark?.filename {
                    Text(name)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if model.watermark?.kind == .svg {
                        Text("SVG")
                            .font(.caption2)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                    }
                } else {
                    Text(L10n.string(.noWatermark))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button(L10n.string(.chooseWatermark)) {
                    model.chooseWatermark()
                }
                .appButton(.small)
                .disabled(model.isProcessing)
            }
        }
    }
}
