import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = AppModel()

    func application(_ application: NSApplication, open urls: [URL]) {
        model.handleOpenedURLs(urls)
    }

    func applicationWillTerminate(_ notification: Notification) {
        model.flushSettings()
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        model.flushSettings()
        return .terminateNow
    }
}

@main
struct SafePhotoMarkApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    private var model: AppModel { appDelegate.model }

    var body: some Scene {
        WindowGroup(L10n.string(.appName)) {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 840, minHeight: 560)
        }
        .defaultSize(width: 960, height: 640)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .appInfo) {
                Button(L10n.format(.aboutApp, L10n.string(.appName))) {
                    AboutPanel.present()
                }
            }
            CommandGroup(replacing: .newItem) {
                Button(L10n.string(.choosePhotos)) {
                    model.choosePhotos()
                }
                .keyboardShortcut("o", modifiers: .command)
                .disabled(model.isProcessing)

                Button(L10n.string(.chooseWatermark)) {
                    model.chooseWatermark()
                }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(model.isProcessing)

                Button(L10n.string(.chooseOutputFolder)) {
                    model.chooseOutputFolder()
                }
                .keyboardShortcut("d", modifiers: [.command, .shift])
                .disabled(model.isProcessing)

                Divider()

                Button(L10n.string(.startProcessing)) {
                    model.startProcessing()
                }
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(!model.canStartProcessing)
            }
        }
    }
}

@MainActor
enum AboutPanel {
    static let email = "izja16xyz@gmail.com"

    static func present() {
        let text = L10n.format(.aboutContact, email)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let credits = NSMutableAttributedString(
            string: text,
            attributes: [
                .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize),
                .foregroundColor: NSColor.labelColor,
                .paragraphStyle: paragraph
            ]
        )
        let range = (text as NSString).range(of: email)
        if range.location != NSNotFound {
            credits.addAttributes([
                .link: URL(string: "mailto:\(email)")!,
                .underlineStyle: NSUnderlineStyle.single.rawValue
            ], range: range)
        }
        NSApplication.shared.orderFrontStandardAboutPanel(options: [
            .credits: credits
        ])
    }
}
