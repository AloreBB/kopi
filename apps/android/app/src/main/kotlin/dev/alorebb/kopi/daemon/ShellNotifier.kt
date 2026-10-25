package dev.alorebb.kopi.daemon

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import dev.alorebb.kopi.R
import dev.alorebb.kopi.ui.StatusModel

/** Where [ShellNotifier] puts its single ongoing notification. */
interface NotificationPoster {
    fun post(model: StatusModel)
    fun cancel()
}

/**
 * The daemon's ongoing status notification, best effort (spec section 8). Posts only when the text
 * changes. If posting throws once, logs `notify failed reason=<exception>` and never tries again, so
 * the status is then only visible in the app. Never logs the notification text (it holds the preview).
 */
class ShellNotifier(private val log: DaemonLog, private val poster: NotificationPoster) {
    private var shown: StatusModel? = null
    private var failed = false

    @Synchronized
    fun show(model: StatusModel) {
        if (failed) return
        if (model == shown) return
        try {
            poster.post(model)
            shown = model
        } catch (e: Throwable) {
            failed = true
            log.event("notify failed reason=${rootName(e)}")
        }
    }

    @Synchronized
    fun cancel() {
        shown = null
        if (!failed) runCatching { poster.cancel() }
    }

    private fun rootName(e: Throwable): String {
        var t = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return t.javaClass.simpleName
    }
}

/**
 * Posts as `com.android.shell` through the hidden `INotificationManager`, by reflection. The texts
 * come from the APK's resources (the daemon runs the APK's code, so `R` matches).
 */
@SuppressLint("PrivateApi")
class ShellNotificationPoster(private val context: Context = SystemContext.get()) : NotificationPoster {
    private val nm: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, Context.NOTIFICATION_SERVICE) as IBinder
        Class.forName("android.app.INotificationManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: error("no INotificationManager")
    }
    private val appResources by lazy { context.createPackageContext(APP_PACKAGE, 0).resources }
    private var channelReady = false

    override fun post(model: StatusModel) {
        val title = model.titleArg?.let { string(model.title, it) } ?: run {
            if (model.title == R.string.status_connected) string(model.title, string(R.string.your_mac)) else string(model.title)
        }
        val text = model.last?.let { string(R.string.last_item, it, model.lastAgo.orEmpty()) }
        ensureChannel()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(Icon.createWithResource(APP_PACKAGE, R.drawable.ic_stat_kopi))
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        text?.let { builder.setContentText(it) }
        openApp()?.let { builder.setContentIntent(it) }
        val enqueue = nm.javaClass.methods.first { it.name == "enqueueNotificationWithTag" && it.parameterTypes.size == 6 }
        enqueue.invoke(nm, SHELL, SHELL, TAG, ID, builder.build(), 0)
    }

    override fun cancel() {
        val cancel = nm.javaClass.methods.first { it.name == "cancelNotificationWithTag" && it.parameterTypes.size == 5 }
        cancel.invoke(nm, SHELL, SHELL, TAG, ID, 0)
    }

    private fun ensureChannel() {
        if (channelReady) return
        val channel = NotificationChannel(CHANNEL, appResources.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        val slice = Class.forName("android.content.pm.ParceledListSlice").getConstructor(List::class.java).newInstance(listOf(channel))
        nm.javaClass.methods.first { it.name == "createNotificationChannels" && it.parameterTypes.size == 2 }
            .invoke(nm, SHELL, slice)
        channelReady = true
    }

    /** Tapping opens Kopi; optional, so a failure here does not stop the notification. */
    private fun openApp(): PendingIntent? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(APP_PACKAGE, "$APP_PACKAGE.ui.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }.getOrNull()

    private fun string(id: Int, vararg args: Any): String = appResources.getString(id, *args)

    companion object {
        const val APP_PACKAGE = "dev.alorebb.kopi"
        const val CHANNEL = "kopi_status"
        private const val SHELL = ClipboardCall.SHELL_PACKAGE
        private const val TAG = "kopi"
        private const val ID = 1
    }
}
