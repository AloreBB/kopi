package dev.alorebb.kopi.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewTest {
    @Test
    fun validCardsDetected() {
        assertTrue(isCardNumber("4242424242424242"))
        assertTrue(isCardNumber("4242 4242 4242 4242"))
        assertTrue(isCardNumber("5555-5555-5555-4444"))
        assertTrue(isCardNumber("378282246310005"))
    }

    @Test
    fun invalidCardsRejected() {
        assertFalse(isCardNumber("4242424242424241"))
        assertFalse(isCardNumber("424242424242"))
        assertFalse(isCardNumber("42424242424242424242"))
        assertFalse(isCardNumber("Tarjeta 4242424242424242"))
    }

    @Test
    fun previews() {
        assertEquals("tarjeta •••• 4242", preview(PreviewInput.Text("4242 4242 4242 4242", sensitive = false)))
        assertEquals("contenido oculto", preview(PreviewInput.Text("hunter2", sensitive = true)))
        assertEquals("tarjeta •••• 4242", preview(PreviewInput.Text("4242424242424242", sensitive = true)))
        assertEquals("imagen", preview(PreviewInput.Image))
        assertEquals("Nos vemos a las 5", preview(PreviewInput.Text("Nos vemos a las 5", sensitive = false)))
        assertEquals("a".repeat(40) + "…", preview(PreviewInput.Text("a".repeat(41), sensitive = false)))
        assertEquals("línea 1 línea 2", preview(PreviewInput.Text("  línea 1\n\nlínea 2 ", sensitive = false)))
    }

    @Test
    fun nonAsciiDigitsAreNotCards() {
        assertFalse(isCardNumber("½½½½½½½½½½½½½"))
        assertFalse(isCardNumber("٤٢٤٢٤٢٤٢٤٢٤٢٤٢٤٢"))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertTrue(isCardNumber("4242424242424242\n"))
        assertEquals("tarjeta •••• 4242", preview(PreviewInput.Text(" 4242 4242 4242 4242\n", sensitive = false)))
    }
}
