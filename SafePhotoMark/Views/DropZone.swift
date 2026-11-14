import SwiftUI

struct DropZone: View {
    var isTargeted: Bool
    var compact: Bool = false
    var title: String
    var subtitle: String
    var actionTitle: String
    var action: () -> Void

    var body: some View {
        VStack(spacing: compact ? 8 : 14) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: compact ? 22 : 36, weight: .regular))
                .foregroundStyle(.secondary)
            VStack(spacing: 4) {
                Text(title)
                    .font(compact ? .callout : .body)
                    .fontWeight(.medium)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Button(actionTitle, action: action)
                .appButton()
        }
        .frame(maxWidth: .infinity)
        .padding(compact ? 12 : 24)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(
                    isTargeted ? Color.accentColor : Color(nsColor: .separatorColor),
                    style: StrokeStyle(lineWidth: isTargeted ? 2 : 1, dash: [7, 5])
                )
        )
    }
}
