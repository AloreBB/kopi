package dev.alorebb.kopi.daemon

import dev.alorebb.kopi.net.PinnedTls
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZonedDateTime

class DaemonIdentityTest {
    @TempDir
    lateinit var root: File

    private fun file() = File(File(root, "kopi"), "identity.p12")

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun createsP12WhenMissing() {
        DaemonIdentity(file()).loadOrCreate()
        assertTrue(file().isFile)
        assertTrue(file().length() > 0)
    }

    @Test
    fun loadIsStable() {
        val first = DaemonIdentity(file()).loadOrCreate()
        val second = DaemonIdentity(file()).loadOrCreate()
        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first.fingerprint))
    }

    @Test
    fun certIsSelfSignedP256() {
        val cert = DaemonIdentity(file()).loadOrCreate().certificate
        assertEquals("CN=Kopi Android", cert.subjectX500Principal.name)
        assertEquals("EC", cert.publicKey.algorithm)
        cert.verify(cert.publicKey)
        val nineYears = ZonedDateTime.now().plusYears(9).toInstant()
        assertTrue(!cert.notAfter.toInstant().isBefore(nineYears))
    }

    @Test
    fun filesAreOwnerOnly() {
        DaemonIdentity(file()).loadOrCreate()
        assertEquals("rwx------", perms(file().parentFile!!))
        assertEquals("rw-------", perms(file()))
    }

    @Test
    fun servesPinnedTlsKeyManager() {
        val id = DaemonIdentity(file()).loadOrCreate()
        val km = PinnedTls.keyManager(id.keyStore, DaemonIdentity.ALIAS, DaemonIdentity.PASSWORD)
        assertEquals(id.certificate, km.getCertificateChain(DaemonIdentity.ALIAS)!!.first())
        assertNotNull(km.getPrivateKey(DaemonIdentity.ALIAS))
    }
}
