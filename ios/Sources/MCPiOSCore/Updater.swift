import Foundation

/// Updater iOS in 3 fasi esplicite come Android (Controlla → Scarica → Installa),
/// ma SENZA auto-installazione silenziosa: su iOS stock un IPA non può
/// installarsi da solo. L'installazione passa da TestFlight/App Store o,
/// per build ad-hoc, da download manuale. Qui solo check + artefatto verificato.
public enum Updater {
    public static let owner = "JackoPeru"
    public static let repo = "mcp-android"
    public static let assetPrefix = "mcp-ios"

    public static func checkLocal() -> [String: Any] {
        ["updateChannel": "github-stable", "platform": "ios",
         "installedVersion": RpcDispatcher.version,
         "note": "Check the latest stable release in-app; installation uses TestFlight/App Store, never silent install",
         "releasesUrl": "https://api.github.com/repos/\(owner)/\(repo)/releases/latest"]
    }

    /// Verifica nome asset atteso: mcp-ios-X.Y.Z.ipa + .sha256, tag vX.Y.Z.
    public static func isValidAsset(name: String, version: String) -> Bool {
        name == "\(assetPrefix)-\(version).ipa" || name == "\(assetPrefix)-\(version).ipa.sha256"
    }

    public static func isValidTag(_ tag: String) -> Bool {
        tag.range(of: #"^v\d+\.\d+\.\d+$"#, options: .regularExpression) != nil
    }
}
