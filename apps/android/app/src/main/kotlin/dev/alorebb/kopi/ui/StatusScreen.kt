package dev.alorebb.kopi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.alorebb.kopi.R

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)

/** Status: what [StatusModel.render] says, its fix (if any), and the pairing/daemon controls. */
@Composable
fun StatusScreen(
    ui: UiState,
    onAction: (Int) -> Unit,
    onUnpair: () -> Unit,
    onStop: () -> Unit,
) {
    val model = ui.model
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    Page(actions = {
        if (!ui.checking) {
            model.action?.let { PrimaryButton(stringResource(it), { onAction(it) }) }
            if (ui.paired) QuietButton(stringResource(R.string.action_unpair), { confirmUnpair = true }, MaterialTheme.colorScheme.error)
            if (ui.daemonReachable) QuietButton(stringResource(R.string.action_stop_kopi), onStop)
        }
    }) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.checking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.status_checking), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.padding(top = 7.dp).size(10.dp)
                                .background(dotColor(model, ui.status?.state), CircleShape),
                        )
                        Text(
                            title(model),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    model.detail?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (model.last != null) {
                        Text(
                            stringResource(R.string.last_item, model.last, model.lastAgo.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair_confirm_title)) },
            text = { Text(stringResource(R.string.unpair_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; onUnpair() }) {
                    Text(stringResource(R.string.action_unpair), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun title(model: StatusModel): String =
    if (model.title == R.string.status_connected) stringResource(model.title, model.titleArg ?: stringResource(R.string.your_mac))
    else stringResource(model.title)

@Composable
private fun dotColor(model: StatusModel, state: String?): Color = when {
    model.problem -> MaterialTheme.colorScheme.error
    state == "connected" -> Green
    else -> Amber
}
