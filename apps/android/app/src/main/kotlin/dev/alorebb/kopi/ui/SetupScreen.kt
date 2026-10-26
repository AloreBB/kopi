package dev.alorebb.kopi.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.alorebb.kopi.R
import dev.alorebb.kopi.shizuku.HiosAutostart
import dev.alorebb.kopi.shizuku.ShizukuState

/** Setup wizard: one step per screen, "Paso N de 3". */
@Composable
fun SetupScreen(
    step: Int,
    ui: UiState,
    onStep: (Int) -> Unit,
    onAction: (Int) -> Unit,
    onDone: () -> Unit,
    onGrantShizuku: () -> Boolean,
    open: (Intent) -> Unit,
    shizukuOpenIntent: Intent,
    shizukuInstallIntent: Intent,
    batteryIntent: Intent,
) {
    if (step > 1) BackHandler { onStep(step - 1) }
    when (step) {
        1 -> ShizukuStep(ui.shizuku, onNext = { onStep(2) }, onGrantShizuku, open, shizukuOpenIntent, shizukuInstallIntent)
        2 -> AutostartStep(ui.batteryUnrestricted, onNext = { onStep(3) }, open, batteryIntent)
        else -> ConnectStep(ui, onAction, onDone)
    }
}

@Composable
private fun StepHeader(step: Int, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_step, step), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { step / 3f }, modifier = Modifier.fillMaxWidth())
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ShizukuStep(
    state: ShizukuState,
    onNext: () -> Unit,
    onGrantShizuku: () -> Boolean,
    open: (Intent) -> Unit,
    openIntent: Intent,
    installIntent: Intent,
) {
    // Shizuku will not show its dialog again after "deny and don't ask again": send the user to Shizuku.
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    Page(actions = {
        when (state) {
            ShizukuState.NotInstalled -> PrimaryButton(stringResource(R.string.action_install_shizuku), { open(installIntent) })
            ShizukuState.NotRunning -> PrimaryButton(stringResource(R.string.action_open_shizuku), { open(openIntent) })
            ShizukuState.NoPermission ->
                if (permissionBlocked) PrimaryButton(stringResource(R.string.action_open_shizuku), { open(openIntent) })
                else PrimaryButton(stringResource(R.string.action_grant_shizuku), { permissionBlocked = !onGrantShizuku() })
            ShizukuState.Ready -> PrimaryButton(stringResource(R.string.action_continue), onNext)
        }
    }) {
        StepHeader(1, stringResource(R.string.setup_shizuku_title))
        Body(stringResource(R.string.setup_shizuku_body))
        when (state) {
            ShizukuState.NotInstalled -> Hint(stringResource(R.string.err_shizuku_missing))
            ShizukuState.NotRunning -> Hint(stringResource(R.string.shizuku_how_to_start))
            ShizukuState.NoPermission -> Hint(
                stringResource(if (permissionBlocked) R.string.shizuku_permission_denied else R.string.err_shizuku_permission),
            )
            ShizukuState.Ready -> Text(
                stringResource(R.string.setup_shizuku_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AutostartStep(batteryUnrestricted: Boolean, onNext: () -> Unit, open: (Intent) -> Unit, batteryIntent: Intent) {
    val context = LocalContext.current
    val autostart = remember { HiosAutostart.intentIfAvailable(context.packageManager) }
    // "Omitir" until the user has done something here; then the same button reads "Continuar".
    var acted by rememberSaveable { mutableStateOf(false) }
    Page(actions = {
        if (!batteryUnrestricted) SecondaryButton(stringResource(R.string.action_remove_restriction)) { acted = true; open(batteryIntent) }
        if (autostart != null) SecondaryButton(stringResource(R.string.action_open_autostart)) { acted = true; open(autostart) }
        if (acted || batteryUnrestricted) PrimaryButton(stringResource(R.string.action_continue), onNext)
        else QuietButton(stringResource(R.string.action_skip), onNext)
    }) {
        StepHeader(2, stringResource(R.string.setup_autostart_title))
        Body(stringResource(R.string.setup_autostart_body))
        if (batteryUnrestricted) Hint(stringResource(R.string.restriction_removed))
        if (autostart != null) Hint(stringResource(R.string.setup_autostart_hint))
    }
}

/** After this long without a connection, say why the Mac may not be found. */
private const val NOT_FOUND_AFTER_MS = 15_000L

/**
 * Automatic pairing: the daemon finds the Mac on the network and pairs on its own. Shows the
 * search, then "Conectado a <Mac>", or the real cause when something is in the way.
 */
@Composable
private fun ConnectStep(ui: UiState, onAction: (Int) -> Unit, onDone: () -> Unit) {
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    val model = ui.model
    val state = ui.status?.state
    val connected = !ui.checking && ui.daemonReachable && state == "connected"
    // A problem with its own cause (Shizuku, Kopi stopped, Mac busy, old Mac app); "not found" waits for the timer.
    val blocked = !ui.checking && !connected && model.problem && state != "not_found"
    val slow = ui.nowMs - startedAt >= NOT_FOUND_AFTER_MS

    Page(actions = {
        when {
            connected -> PrimaryButton(stringResource(R.string.action_done), onDone)
            blocked -> model.action?.let { action -> PrimaryButton(stringResource(action), { onAction(action) }) }
        }
    }) {
        StepHeader(3, stringResource(R.string.setup_pair_title))
        Body(stringResource(R.string.setup_pair_body))
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when {
            connected -> Text(
                stringResource(R.string.status_connected, ui.status?.macName ?: stringResource(R.string.your_mac)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = live,
            )
            blocked -> Column(live, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(model.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                model.detail?.let { Hint(stringResource(it)) }
            }
            slow -> Column(live, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.err_mac_not_found_yet), style = MaterialTheme.typography.titleMedium)
                Hint(stringResource(if (ui.status?.vpn == true) R.string.vpn_hint else R.string.not_found_hint))
                Searching(R.string.still_searching)
            }
            else -> Searching(R.string.status_searching, live)
        }
    }
}

@Composable
private fun Searching(text: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}
