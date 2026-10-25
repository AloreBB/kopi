// Visible text, verbatim from spec §8 and the v1 plan (Task 11).
enum Copy {
    static func connected(to name: String) -> String { "Conectado a \(name)" }
    static let waiting = "Esperando al teléfono…"
    static let disconnected = "Teléfono no conectado"
    static let phoneOutdated = "Kopi está desactualizado en tu teléfono. Actualízalo para seguir sincronizando."

    /// "Último: …, hace 5 s" (same line as the Android notification).
    static func last(_ preview: String, secondsAgo: Int) -> String {
        "Último: \(preview), \(age(secondsAgo))"
    }

    static func age(_ seconds: Int) -> String {
        let seconds = max(0, seconds)
        if seconds < 60 { return "hace \(seconds) s" }
        if seconds < 3600 { return "hace \(seconds / 60) min" }
        return "hace \(seconds / 3600) h"
    }

    // Menu.
    static let pairPhone = "Vincular teléfono…"
    static let openAtLogin = "Abrir al iniciar sesión"
    static let showLog = "Ver registro"
    static let unpair = "Desvincular"
    static let quit = "Salir de Kopi"

    // Pairing window.
    static let pairingTitle = "Vincular teléfono"
    static let pairingInstructions = "Escanea este código con Kopi en tu Android."
    static let codeExpired = "El código caducó."
    static let newCode = "Generar código nuevo"
}
