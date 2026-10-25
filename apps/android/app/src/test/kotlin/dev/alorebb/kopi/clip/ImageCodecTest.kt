package dev.alorebb.kopi.clip

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCodecTest {
    private fun encode(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    @Test
    fun pngPassesThrough() {
        val png = encode(Bitmap.CompressFormat.PNG)
        val out = ImageCodec.normalize(png)
        assertNotNull(out)
        assertEquals("image/png", out!!.mime)
        assertArrayEquals(png, out.bytes)
    }

    @Test
    fun jpegPassesThrough() {
        val jpeg = encode(Bitmap.CompressFormat.JPEG)
        val out = ImageCodec.normalize(jpeg)
        assertEquals("image/jpeg", out!!.mime)
        assertArrayEquals(jpeg, out.bytes)
    }

    @Test
    fun webpBecomesPng() {
        val out = ImageCodec.normalize(encode(Bitmap.CompressFormat.WEBP_LOSSLESS))
        assertEquals("image/png", out!!.mime)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), out.bytes.copyOfRange(0, 4))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(ImageCodec.normalize("not an image".toByteArray()))
    }
}
