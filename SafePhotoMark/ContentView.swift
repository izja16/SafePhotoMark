import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HSplitView {
            PhotoListPane()
                .frame(minWidth: 220, idealWidth: 260, maxWidth: 380)

            PreviewPane()
                .frame(minWidth: 300)

            InspectorPane()
                .frame(minWidth: 260, idealWidth: 300, maxWidth: 380)
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}
