package dev.alorebb.kopi.shizuku

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/** HiOS hides its autostart list from Settings; it only exists as this Phone Master activity. */
object HiosAutostart {
    val COMPONENT = ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")

    /** Null when the activity does not exist or cannot be opened (another ROM). */
    fun intentIfAvailable(pm: PackageManager): Intent? {
        val intent = Intent().setComponent(COMPONENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = runCatching { pm.getActivityInfo(COMPONENT, 0) }.getOrNull() ?: return null
        return if (info.exported) intent else null
    }
}
