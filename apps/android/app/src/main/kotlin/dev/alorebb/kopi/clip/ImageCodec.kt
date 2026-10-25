package dev.alorebb.kopi.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

class NormalizedImage(val mime: String, val bytes: ByteArray)

/** Only `image/png` and `image/jpeg` cross the wire: other formats are re-encoded as PNG. */
object ImageCodec {
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** Null when [bytes] is not a decodable image. */
    fun normalize(bytes: ByteArray): NormalizedImage? {
        if (bytes.startsWith(PNG)) return NormalizedImage("image/png", bytes)
        if (bytes.startsWith(JPEG)) return NormalizedImage("image/jpeg", bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        bitmap.recycle()
        return NormalizedImage("image/png", out.toByteArray())
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
