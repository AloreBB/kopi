// Visible text, verbatim from spec §8 and the v1 plan (Task 11, amended for automatic pairing).
enum Copy {
    static func connected(to name: String) -> String { "Conectado a \(name)" }
    static let waiting = "Esperando a tu teléfono… Abre Kopi en tu Android."
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
    static let openAtLogin = "Abrir al iniciar sesión"
    static let showLog = "Ver registro"
    static let unpair = "Desvincular"
    static let quit = "Salir de Kopi"

    // Notification after automatic pairing.
    static func pairedTitle(_ name: String) -> String { "Kopi se vinculó con \(name)" }
    static let pairedBody = "Ya sincroniza lo que copias. ¿No es tu teléfono? Desvincúlalo."
    static let unpairAction = "Desvincular"
}
