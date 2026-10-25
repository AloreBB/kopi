package dev.alorebb.kopi.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alorebb.kopi.R

/**
 * Setup, pairing and status. Syncing happens in the shell daemon; this activity only talks to it
 * while visible (HiOS freezes the app process in the background). `kopi://pair?…` links (ACTION_VIEW)
 * take the same path as a scanned QR.
 */
class MainActivity : ComponentActivity() {
    private val vm: KopiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            KopiTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val ui by vm.ui.collectAsStateWithLifecycle()
                val pairing by vm.pairing.collectAsStateWithLifecycle()
                when (val s = screen) {
                    is Screen.Setup -> SetupScreen(
                        step = s.step,
                        ui = ui,
                        onStep = { vm.go(Screen.Setup(it)) },
                        onScan = { vm.go(Screen.Scanner) },
                        onGrantShizuku = vm.gate::requestPermission,
                        open = ::open,
                        shizukuOpenIntent = vm.gate.openIntent(),
                        shizukuInstallIntent = vm.gate.installIntent(),
                        batteryIntent = batteryIntent(),
                    )
                    Screen.Status -> StatusScreen(
                        ui = ui,
                        onAction = ::onStatusAction,
                        onPairAgain = { vm.go(Screen.Scanner) },
                        onUnpair = vm::unpair,
                        onStop = vm::stopDaemon,
                    )
                    Screen.Scanner -> ScannerScreen(onScanned = vm::pair, onCancel = vm::leaveScanner)
                    Screen.Pairing -> PairingScreen(pairing, onScanAgain = { vm.go(Screen.Scanner) }, onCancel = vm::leaveScanner)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onVisible()
    }

    override fun onPause() {
        vm.onHidden()
        super.onPause()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(vm::pair)
    }

    private fun onStatusAction(action: Int) {
        when (action) {
            R.string.action_reactivate_shizuku -> open(vm.gate.openIntent())
            R.string.action_install_shizuku -> open(vm.gate.installIntent())
            R.string.action_grant_shizuku -> if (!vm.gate.requestPermission()) open(vm.gate.openIntent())
            R.string.action_start_kopi -> vm.startDaemon()
            R.string.action_pair_again, R.string.action_scan_code -> vm.go(Screen.Scanner)
            R.string.action_how_to_update -> open(Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL)))
        }
    }

    private fun batteryIntent() =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.err_cannot_open, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.err_cannot_open, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val UPDATE_URL = "https://github.com/AloreBB/kopi/releases"
    }
}
