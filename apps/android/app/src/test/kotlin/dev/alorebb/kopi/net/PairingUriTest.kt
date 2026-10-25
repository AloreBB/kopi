package dev.alorebb.kopi.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PairingUriTest {
    private val fp = "a1".repeat(32)
    private val code = "AbCdEfGhIjKlMnOpQr_-01"

    private fun uri(
        scheme: String = "kopi",
        v: String = "1",
        host: String = "192.168.1.20",
        port: String = "47474",
        fp: String = this.fp,
        code: String = this.code,
    ) = "$scheme://pair?v=$v&host=$host&port=$port&fp=$fp&code=$code"

    @Test
    fun parsesValidUri() {
        assertEquals(PairingUri("192.168.1.20", 47474, fp, code), PairingUri.parse(uri()))
    }

    @Test
    fun rejectsWrongScheme() {
        assertNull(PairingUri.parse(uri(scheme = "http")))
        assertNull(PairingUri.parse("kopi://unpair?v=1&host=192.168.1.20&port=47474&fp=$fp&code=$code"))
    }

    @Test
    fun rejectsBadFingerprint() {
        assertNull(PairingUri.parse(uri(fp = "a1".repeat(31))))
        assertNull(PairingUri.parse(uri(fp = "A1".repeat(32))))
        assertNull(PairingUri.parse(uri(fp = "zz".repeat(32))))
    }

    @Test
    fun rejectsVersion2() {
        assertNull(PairingUri.parse(uri(v = "2")))
    }

    @Test
    fun rejectsShortCode() {
        assertNull(PairingUri.parse(uri(code = code.dropLast(1))))
        assertNull(PairingUri.parse(uri(code = code.dropLast(1) + "=")))
    }

    @Test
    fun rejectsBadPortOrHost() {
        assertNull(PairingUri.parse(uri(port = "0")))
        assertNull(PairingUri.parse(uri(port = "65536")))
        assertNull(PairingUri.parse(uri(port = "abc")))
        assertNull(PairingUri.parse(uri(host = "mac.local")))
        assertNull(PairingUri.parse(uri(host = "192.168.1.256")))
        assertNull(PairingUri.parse(uri(host = "192.168.1")))
    }

    @Test
    fun rejectsMissingParameter() {
        assertNull(PairingUri.parse("kopi://pair?v=1&host=192.168.1.20&port=47474&fp=$fp"))
        assertNull(PairingUri.parse("garbage"))
    }
}
