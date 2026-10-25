package dev.alorebb.kopi.daemon

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClipboardCallTest {
    private val s: Class<*> = String::class.java
    private val i: Class<*> = Int::class.javaPrimitiveType!!
    private val l: Class<*> = Long::class.javaPrimitiveType!!

    /** Stands in for android.content.ClipData, which is not available on the JVM. */
    private class FakeClip

    @Test
    fun picksAndroid16Overload() {
        val methods = listOf(
            MethodSig("getPrimaryClip", listOf(s, i)),
            MethodSig("getPrimaryClip", listOf(s, s, i)),
            MethodSig("getPrimaryClip", listOf(s, s, i, i)),
            MethodSig("setPrimaryClip", listOf(s, s, i, i, i)),
        )
        assertEquals(MethodSig("getPrimaryClip", listOf(s, s, i, i)), ClipboardCall.pick(methods, "getPrimaryClip"))
    }

    @Test
    fun fillsAndroid16Args() {
        val args = ClipboardCall.args(MethodSig("getPrimaryClip", listOf(s, s, i, i)), emptyList())
        assertArrayEquals(arrayOf<Any?>("com.android.shell", null, 0, 0), args)
    }

    @Test
    fun fillsLegacyArgs() {
        val args = ClipboardCall.args(MethodSig("getPrimaryClip", listOf(s, i)), emptyList())
        assertArrayEquals(arrayOf<Any?>("com.android.shell", 0), args)
    }

    @Test
    fun keepsLeadingClip() {
        val clip = FakeClip()
        val sig = MethodSig("setPrimaryClip", listOf(FakeClip::class.java, s, s, i, i))
        assertEquals(sig, ClipboardCall.pick(listOf(sig), "setPrimaryClip", listOf(FakeClip::class.java)))
        val args = ClipboardCall.args(sig, listOf(clip))
        assertArrayEquals(arrayOf<Any?>(clip, "com.android.shell", null, 0, 0), args)
    }

    @Test
    fun rejectsUnknownParamTypes() {
        assertNull(ClipboardCall.pick(listOf(MethodSig("getPrimaryClip", listOf(s, l))), "getPrimaryClip"))
    }
}
