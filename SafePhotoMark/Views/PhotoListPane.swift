import AppKit
import SwiftUI

struct PhotoListPane: View {
    @EnvironmentObject private var model: AppModel
    @State private var isTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                if model.isImporting {
                    ProgressView()
                        .controlSize(.small)
                }
                Spacer(minLength: 0)
                Text(L10n.format(.photosCount, Int64(model.photos.count)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)
            .padding(.bottom, 8)

            DropZone(
                isTargeted: isTargeted,
                compact: !model.photos.isEmpty,
                title: model.photos.isEmpty ? L10n.string(.dropPhotosEmpty) : L10n.string(.dropPhotosMore),
                subtitle: L10n.string(.dropPhotosFormats),
                actionTitle: L10n.string(.choosePhotos),
                action: { model.choosePhotos() }
            )
            .padding(.horizontal, 12)
            .disabled(model.isProcessing)
            .onDrop(of: [.fileURL], isTargeted: $isTargeted, perform: model.handlePhotoDrop)
            .allowsHitTesting(!model.isProcessing)

            if model.photos.isEmpty {
                Spacer(minLength: 0)
            } else {
                List(selection: selectionBinding) {
                    ForEach(model.photos) { item in
                        PhotoRow(item: item)
                            .tag(item.id)
                            .contextMenu {
                                Button(L10n.string(.showInFinder)) {
                                    NSWorkspace.shared.activateFileViewerSelecting([item.url])
                                }
                                Button(L10n.string(.removeFromList), role: .destructive) {
                                    model.removePhoto(id: item.id)
                                }
                                .disabled(model.isProcessing)
                            }
                    }
                }
                .listStyle(.inset)

                HStack {
                    Button(L10n.string(.clearList)) {
                        model.clearPhotos()
                    }
                    .appButton(.small)
                    .disabled(model.isProcessing || model.photos.isEmpty)
                    Spacer()
                }
                .padding(12)
            }
        }
    }

    private var selectionBinding: Binding<PhotoItem.ID?> {
        Binding(
            get: { model.selectedPhotoID },
            set: { newValue in
                if let newValue {
                    model.selectPhoto(id: newValue)
                } else {
                    model.selectedPhotoID = nil
                }
            }
        )
    }
}

struct PhotoRow: View {
    let item: PhotoItem

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if let thumbnail = item.thumbnail?.image {
                    Image(nsImage: thumbnail)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Rectangle()
                        .fill(Color(nsColor: .quaternaryLabelColor))
                        .overlay {
                            Image(systemName: "photo")
                                .foregroundStyle(.secondary)
                        }
                }
            }
            .frame(width: 40, height: 40)
            .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(item.filename)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(item.status.label)
                    .font(.caption)
                    .foregroundStyle(statusColor)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }

    private var statusColor: Color {
        switch item.status {
        case .waiting:
            return Color.secondary
        case .processing:
            return Color.accentColor
        case .succeeded:
            return Color.green
        case .failed:
            return Color.red
        }
    }
}
