import Testing
import KopiCore

@Test func validCardsDetected() {
    #expect(isCardNumber("4242424242424242"))
    #expect(isCardNumber("4242 4242 4242 4242"))
    #expect(isCardNumber("5555-5555-5555-4444"))
    #expect(isCardNumber("378282246310005"))
}

@Test func invalidCardsRejected() {
    #expect(!isCardNumber("4242424242424241"))
    #expect(!isCardNumber("424242424242"))
    #expect(!isCardNumber("42424242424242424242"))
    #expect(!isCardNumber("Tarjeta 4242424242424242"))
}

@Test func previews() {
    #expect(preview(.text("4242 4242 4242 4242", sensitive: false)) == "tarjeta •••• 4242")
    #expect(preview(.text("hunter2", sensitive: true)) == "contenido oculto")
    #expect(preview(.text("4242424242424242", sensitive: true)) == "tarjeta •••• 4242")
    #expect(preview(.image) == "imagen")
    #expect(preview(.text("Nos vemos a las 5", sensitive: false)) == "Nos vemos a las 5")
    #expect(preview(.text(String(repeating: "a", count: 41), sensitive: false))
        == String(repeating: "a", count: 40) + "…")
    #expect(preview(.text("  línea 1\n\nlínea 2 ", sensitive: false)) == "línea 1 línea 2")
}
