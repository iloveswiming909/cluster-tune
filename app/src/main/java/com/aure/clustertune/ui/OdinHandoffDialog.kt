package com.aure.clustertune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown when the standard PServer apply path failed verification on a
 * device that has Odin Settings available as a fallback. Offers the
 * user a "open Odin Settings" button that takes them to the Run
 * script as root flow. The dialog body switches to a longer tutorial
 * the first time, then a shorter reminder afterwards.
 */
@Composable
fun OdinHandoffDialog(
    request: com.aure.clustertune.ui.TunerViewModel.HandoffRequest,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Apply via Odin Settings")
        },
        text = {
            Column(
                modifier = Modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (request.showTutorial) {
                    Text(
                        text = "This device needs an extra step to apply CPU frequency limits. " +
                            "ClusterTune has written the apply script to:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = request.userVisiblePath,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap \"Open Odin Settings\" below, then:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("1. Tap \"Run script as root\"")
                    Text("2. Tap OK on the warning prompt")
                    Text("3. Pick clustertune-apply.sh from the ClusterScripts folder")
                    Text("4. Wait for the \"applied\" confirmation, then return here")
                } else {
                    Text(
                        text = "Open Odin Settings → Run script as root → " +
                            "pick clustertune-apply.sh, then return here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Script: ${request.userVisiblePath}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Odin Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
