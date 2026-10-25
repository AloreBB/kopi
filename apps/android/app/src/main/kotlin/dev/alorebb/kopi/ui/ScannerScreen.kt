package dev.alorebb.kopi.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.alorebb.kopi.R
import dev.alorebb.kopi.net.PairingUri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scans the Mac's QR (CameraX + bundled ML Kit) and hands the first valid `kopi://pair` URI over. */
@Composable
fun ScannerScreen(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    if (granted) {
        CameraScanner(onScanned, onCancel)
        return
    }
    // Denied: explain why, and offer the right way back (the dialog, or Settings once Android stops showing it).
    val activity = context as? Activity
    val canAsk = !asked || activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    Page(actions = {
        if (canAsk) PrimaryButton(stringResource(R.string.action_allow_camera), { request.launch(Manifest.permission.CAMERA) })
        else PrimaryButton(stringResource(R.string.action_open_settings), {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        })
        QuietButton(stringResource(R.string.action_cancel), onCancel)
    }) {
        Text(stringResource(R.string.camera_needed), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanner(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notKopi by remember { mutableStateOf(false) }
    val done = remember { AtomicBoolean(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || done.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val values = codes.mapNotNull { it.rawValue }
                        val pairing = values.firstOrNull { PairingUri.parse(it) != null }
                        when {
                            pairing != null -> if (done.compareAndSet(false, true)) onScanned(pairing)
                            values.isNotEmpty() -> notKopi = true
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.Center).size(240.dp)
                .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp)),
        )
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (notKopi) R.string.scanner_not_kopi else R.string.scanner_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onCancel, modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))) {
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
        }
    }
}

/** While `daemon.pair` runs, and its failure (spec section 8: expired code). */
@Composable
fun PairingScreen(state: PairingState, onScanAgain: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    when (state) {
        PairingState.InProgress -> Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(stringResource(R.string.pairing_progress), style = MaterialTheme.typography.titleMedium)
            }
        }
        PairingState.Failed -> Page(actions = {
            PrimaryButton(stringResource(R.string.action_scan_again), onScanAgain)
            QuietButton(stringResource(R.string.action_cancel), onCancel)
        }) {
            Text(stringResource(R.string.err_code_expired), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.code_expired_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
