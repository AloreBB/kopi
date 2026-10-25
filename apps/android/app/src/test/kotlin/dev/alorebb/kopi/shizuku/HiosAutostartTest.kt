package dev.alorebb.kopi.shizuku

import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HiosAutostartTest {
    private val pm = RuntimeEnvironment.getApplication().packageManager

    @Test
    fun autostartNullWhenMissing() {
        assertNull(HiosAutostart.intentIfAvailable(pm))
    }

    @Test
    fun autostartIntentWhenPresent() {
        shadowOf(pm).addOrUpdateActivity(ActivityInfo().apply {
            packageName = HiosAutostart.COMPONENT.packageName
            name = HiosAutostart.COMPONENT.className
            exported = true
        })
        val intent = HiosAutostart.intentIfAvailable(pm)
        assertNotNull(intent)
        assertEquals(HiosAutostart.COMPONENT, intent!!.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun autostartNullWhenNotExported() {
        shadowOf(pm).addOrUpdateActivity(ActivityInfo().apply {
            packageName = HiosAutostart.COMPONENT.packageName
            name = HiosAutostart.COMPONENT.className
            exported = false
        })
        assertNull(HiosAutostart.intentIfAvailable(pm))
    }
}
