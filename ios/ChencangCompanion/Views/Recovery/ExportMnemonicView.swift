import SwiftUI
import ChencangShared

struct ExportMnemonicView: View {
    @EnvironmentObject private var identityStore: IdentityStore
    @State private var revealed = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "key.fill")
                .font(.system(size: 56))
                .foregroundStyle(.orange)
            Text("Export recovery phrase")
                .font(.title2.weight(.semibold))
            Text("The recovery phrase is the only way to restore your identity. Write it down and keep it safe. Anyone who has it can impersonate you.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
            if revealed {
                Text(identityStore.mnemonicPreview ?? "(not generated yet)")
                    .font(.system(.body, design: .monospaced))
                    .textSelection(.enabled)
                    .padding()
                    .background(Color.secondary.opacity(0.1),
                                in: RoundedRectangle(cornerRadius: 8))
            } else {
                Button("Show recovery phrase") { revealed = true }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
        }
        .padding(24)
        .navigationTitle("Recovery phrase")
    }
}

struct ImportMnemonicView: View {
    @State private var mnemonic: String = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 16) {
            Text("Import from recovery phrase")
                .font(.title2.weight(.semibold))
            TextField("Enter or paste recovery phrase", text: $mnemonic, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(4...8)
            if let err = error {
                Text(err).foregroundStyle(.red).font(.caption)
            }
            Button("Import") {
                error = "Not implemented yet"
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(mnemonic.isEmpty)
        }
        .padding(24)
        .navigationTitle("Restore identity")
    }
}
