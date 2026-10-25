package dev.alorebb.kopi.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
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
    onScan: () -> Unit,
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
        else -> PairStep(onScan)
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

@Composable
private fun PairStep(onScan: () -> Unit) {
    Page(actions = { PrimaryButton(stringResource(R.string.action_scan_code), onScan) }) {
        StepHeader(3, stringResource(R.string.setup_pair_title))
        Body(stringResource(R.string.setup_pair_body))
    }
}
