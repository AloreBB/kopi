package dev.alorebb.kopi.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class MessagesTest {
    @ParameterizedTest
    @ValueSource(strings = ["hello", "pair", "paired", "clip_text", "clip_text_sensitive",
        "clip_offer", "clip_request", "clip_data", "clip_unavailable", "error_version_mismatch",
        "error_not_paired", "error_invalid_code"])
    fun fixtureRoundTrips(name: String) {
        val raw = File(System.getProperty("kopi.fixtures"), "$name.json").readText()
        val msg = KopiJson.decodeFromString<KopiMessage>(raw)
        assertEquals(
            KopiJson.parseToJsonElement(raw),
            KopiJson.parseToJsonElement(KopiJson.encodeToString(msg)),
        )
    }

    @Test
    fun unknownTypeFails() {
        assertThrows<SerializationException> {
            KopiJson.decodeFromString<KopiMessage>("""{"type":"nope"}""")
        }
    }
}
