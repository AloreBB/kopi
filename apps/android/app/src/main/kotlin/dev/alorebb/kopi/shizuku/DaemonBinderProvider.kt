package dev.alorebb.kopi.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import dev.alorebb.kopi.daemon.AppAttach

/**
 * Receives the daemon's binder (see [AppAttach]). Guarded by INTERACT_ACROSS_USERS_FULL in the
 * manifest, which shell has and ordinary apps do not, and by a shell/root calling-uid check.
 */
class DaemonBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) throw SecurityException("caller uid=$uid")
        if (method == AppAttach.METHOD) extras?.getBinder(AppAttach.EXTRA_BINDER)?.let(DaemonLauncher::attached)
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
