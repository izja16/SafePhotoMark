import AppKit
import SwiftUI

enum AppButtonKind {
    case regular
    case prominent
    case small
}

extension View {
    /// System button chrome that follows the macOS 26/27 control design:
    /// bordered (or prominent) style, capsule shape for regular and large
    /// buttons, and the compact rounded rectangle for small controls.
    func appButton(_ kind: AppButtonKind = .regular) -> some View {
        modifier(AppButtonModifier(kind: kind))
    }
}

private struct AppButtonModifier: ViewModifier {
    let kind: AppButtonKind

    func body(content: Content) -> some View {
        switch kind {
        case .regular:
            content
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .buttonBorderShape(.capsule)
        case .small:
            content
                .buttonStyle(.bordered)
                .controlSize(.small)
                .buttonBorderShape(.automatic)
        case .prominent:
            prominent(content)
        }
    }

    @ViewBuilder
    private func prominent(_ content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content
                .buttonStyle(.borderedProminent)
                .controlSize(.extraLarge)
                .buttonBorderShape(.capsule)
        } else {
            content
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .buttonBorderShape(.capsule)
        }
    }
}

/// Sliding tab-style segmented control with an explicit capsule bezel,
/// matching the macOS 27 Photos “年 / 月 / 所有照片” control, but with
/// slightly larger type than the default regular size.
struct WatermarkModeSegmentedControl: NSViewRepresentable {
    @Binding var selection: WatermarkMode

    func makeCoordinator() -> Coordinator {
        Coordinator(selection: $selection)
    }

    func makeNSView(context: Context) -> NSSegmentedControl {
        let control = NSSegmentedControl(
            labels: WatermarkMode.allCases.map(\.title),
            trackingMode: .selectOne,
            target: context.coordinator,
            action: #selector(Coordinator.changed(_:))
        )
        applyStyle(to: control)
        syncSelection(on: control)
        return control
    }

    func updateNSView(_ control: NSSegmentedControl, context: Context) {
        context.coordinator.selection = $selection
        applyStyle(to: control)
        for (index, mode) in WatermarkMode.allCases.enumerated() {
            if control.label(forSegment: index) != mode.title {
                control.setLabel(mode.title, forSegment: index)
            }
        }
        syncSelection(on: control)
        control.sizeToFit()
    }

    private func syncSelection(on control: NSSegmentedControl) {
        if let index = WatermarkMode.allCases.firstIndex(of: selection),
           control.selectedSegment != index {
            control.selectedSegment = index
        }
    }

    private func applyStyle(to control: NSSegmentedControl) {
        if #available(macOS 27.0, *) {
            control.role = .tabs
        }
        if #available(macOS 26.0, *) {
            control.borderShape = .capsule
            control.controlSize = .large
        } else {
            control.segmentStyle = .capsule
            control.controlSize = .large
        }
        control.segmentDistribution = .fit
        control.font = .systemFont(ofSize: 15, weight: .medium)
        control.setContentHuggingPriority(.required, for: .horizontal)
        control.setContentCompressionResistancePriority(.required, for: .horizontal)
    }

    @MainActor
    final class Coordinator: NSObject {
        var selection: Binding<WatermarkMode>

        init(selection: Binding<WatermarkMode>) {
            self.selection = selection
        }

        @objc func changed(_ sender: NSSegmentedControl) {
            let modes = WatermarkMode.allCases
            let index = sender.selectedSegment
            guard modes.indices.contains(index) else { return }
            selection.wrappedValue = modes[index]
        }
    }
}

extension View {
    /// Forces a Form row to take the full content width so its child can be centered.
    func centeredInForm() -> some View {
        ZStack {
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: 0)
            self
        }
        .frame(maxWidth: .infinity)
    }
}
