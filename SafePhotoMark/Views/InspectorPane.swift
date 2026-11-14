import SwiftUI

struct InspectorPane: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Form {
                Section(L10n.string(.sectionWatermark)) {
                    WatermarkWell()

                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(L10n.string(.size))
                            Spacer()
                            Text("\(Int(model.sizePercent.rounded()))%")
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        Slider(
                            value: $model.sizePercent,
                            in: WatermarkCompositor.sizePercentRange
                        ) { editing in
                            if !editing { model.flushSettings() }
                        }
                        SettingHint(key: .sizeHint)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(L10n.string(.opacity))
                            Spacer()
                            Text("\(Int((model.opacity * 100).rounded()))%")
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }
                        Slider(value: $model.opacity, in: 0 ... 1) { editing in
                            if !editing { model.flushSettings() }
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        WatermarkModeSegmentedControl(selection: $model.watermarkMode)
                            .fixedSize()
                            .centeredInForm()

                        switch model.watermarkMode {
                        case .manual:
                            SettingHint(key: .modeManualHint)
                        case .random:
                            SettingHint(key: .modeRandomHint)
                        case .fixed:
                            Picker(L10n.string(.position), selection: $model.position) {
                                ForEach(WatermarkPosition.allCases) { position in
                                    Text(position.title).tag(position)
                                }
                            }
                        }
                    }

                }
                .disabled(model.isProcessing)

                Section(L10n.string(.sectionOutput)) {
                    Picker(L10n.string(.format), selection: $model.outputFormat) {
                        ForEach(OutputFormat.allCases) { format in
                            Text(format.title).tag(format)
                        }
                    }

                    if model.outputFormat.usesLossyQuality {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(L10n.string(.quality))
                                Spacer()
                                Text("\(Int((model.quality * 100).rounded()))%")
                                    .foregroundStyle(.secondary)
                                    .monospacedDigit()
                            }
                            Slider(value: $model.quality, in: 0.5 ... 1.0) { editing in
                                if !editing { model.flushSettings() }
                            }
                            SettingHint(key: .qualityHint)
                        }
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(L10n.string(.stripMetadata), isOn: $model.stripMetadata)
                        SettingHint(key: .stripMetadataHint)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(L10n.string(.randomRename), isOn: $model.randomRename)
                        SettingHint(key: .randomRenameHint)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(L10n.string(.outputFolder))
                        Text(model.outputFolderDisplayName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .lineLimit(2)
                        Button(L10n.string(.chooseOutputFolder)) {
                            model.chooseOutputFolder()
                        }
                        .appButton()
                        SettingHint(key: .outputFolderHint)
                    }
                }
                .disabled(model.isProcessing)

                Section(L10n.string(.sectionProcessing)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(L10n.string(.rememberSettings), isOn: $model.rememberSettings)
                        SettingHint(key: .rememberSettingsHint)
                    }

                    Toggle(L10n.string(.completionSound), isOn: $model.playCompletionSound)

                    if model.isProcessing {
                        VStack(alignment: .leading, spacing: 8) {
                            ProgressView(
                                value: Double(model.processedCount),
                                total: Double(max(model.processingTotal, 1))
                            )
                            Text(L10n.format(.processingProgress, Int64(model.processedCount), Int64(model.processingTotal)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button(L10n.string(.cancel), role: .cancel) {
                                model.cancelProcessing()
                            }
                            .appButton()
                            .disabled(model.isCancelling)
                        }
                    } else {
                        Button(L10n.string(.startProcessing)) {
                            model.startProcessing()
                        }
                        .appButton(.prominent)
                        .keyboardShortcut(.defaultAction)
                        .disabled(!model.canStartProcessing)
                        .frame(maxWidth: .infinity, alignment: .leading)

                        if model.photos.isEmpty {
                            Text(L10n.string(.importPhotosFirst))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else if model.watermark == nil {
                            Text(L10n.string(.chooseWatermarkFirst))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        if model.successCount + model.failureCount > 0, !model.isProcessing {
                            Text(L10n.format(.finishedSummary, Int64(model.successCount), Int64(model.failureCount)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button(L10n.string(.showInFinder)) {
                                model.revealOutputInFinder()
                            }
                            .appButton()
                            .disabled(model.outputFolder == nil && model.lastRunSucceeded.isEmpty)
                        }
                    }

                    SettingHint(key: .privacyNote)
                }
            }
            .formStyle(.grouped)
            .padding(.top, 6)
            .onDisappear { model.flushSettings() }
        }
    }
}

private struct SettingHint: View {
    let key: L10nKey

    var body: some View {
        Text(L10n.string(key))
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.leading)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
