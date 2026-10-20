import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: ServerModel
    var body: some View {
        NavigationStack {
            Form {
                Section("Controllo remoto") {
                    Text(model.maskedToken).font(.footnote.monospaced())
                    HStack {
                        Button("Copia token") { model.copy() }
                        Spacer()
                        Button("Ruota token") { model.rotate() }
                    }
                    TextField("LAN IPv4 (vuoto = auto)", text: $model.lanAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.body.monospaced())
                    TextField("Tailscale IPv4 100.x.y.z (vuoto = auto)", text: $model.tailscaleAddress)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.body.monospaced())
                    Toggle("Fallback Tailscale", isOn: $model.useTailscaleFallback)
                    if model.running {
                        Button("Ferma controllo remoto", role: .destructive) { model.stop() }
                    } else {
                        Button("Avvia controllo remoto") { model.start() }
                    }
                    if !model.lastError.isEmpty { Text(model.lastError).foregroundStyle(.red).font(.footnote) }
                }
                Section("Limiti iOS (stock, senza jailbreak)") {
                    Text("• Solo screenshot/app in primo piano, mai altre app")
                    Text("• File: sandbox + cartelle importate, mai tutto il telefono")
                    Text("• Automazione via Scorciatoie (shortcuts://), non tap di sistema")
                    Text("• Niente shell, logcat, force-stop, sblocco, notification listener")
                    Text("• In background iOS sospende il server: tieni l'app aperta")
                }
                Section("Aggiornamenti") {
                    Text("Controlla → Scarica → Installa via TestFlight/App Store. Nessuna installazione silenziosa.")
                }
            }
            .navigationTitle(model.running ? "MCP iOS • attivo" : "MCP iOS")
        }
    }
}
