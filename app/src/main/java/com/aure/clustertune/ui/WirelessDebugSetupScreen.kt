package com.aure.clustertune.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time-per-boot setup for the no-root "Wireless debug" execution method.
 *
 * The tricky part is Android's pairing UX: the system "Pair device with
 * pairing code" dialog shows a 6-digit code AND hosts the pairing service on
 * a random port. That dialog closes as soon as it loses focus, which also
 * stops the pairing service. To make this usable we:
 *   - launch the system wireless-debugging screen in SPLIT SCREEN (adjacent),
 *     so it stays visible next to ClusterTune and doesn't close, and
 *   - have the user read the code from the system pane and type it into
 *     ClusterTune's field (NOT the system dialog — typing there makes the
 *     system self-pair and close before we can pair).
 *
 * Approach adapted from github.com/wuyr/jdwp-injector-for-android (Apache-2.0).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDebugSetupScreen(
    connectionManager: WirelessDebugConnectionManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var devOptionsEnabled by remember { mutableStateOf(isDevOptionsEnabled(context)) }
    var status by remember { mutableStateOf("Not connected") }
    var pairingReady by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(connectionManager.connectionInfo != null) }
    var busy by remember { mutableStateOf(false) }

    // Re-check dev options + keep discovery alive while this screen is shown.
    DisposableEffect(Unit) {
        devOptionsEnabled = isDevOptionsEnabled(context)
        onDispose { connectionManager.stopAll() }
    }

    fun startConnect() {
        status = "Looking for wireless debugging…"
        connectionManager.startConnectDiscovery(
            onConnected = { info ->
                connected = true
                status = "Connected (${info.host}:${info.port}). You're ready."
            },
            onUnavailable = {
                status = "Wireless debugging not found yet. Make sure it's ON, then pair below."
            },
        )
    }

    fun startPairing() {
        status = "Waiting for the pairing dialog… keep the system pane open."
        connectionManager.startPairingDiscovery(
            onPairingPortFound = { _, _ ->
                pairingReady = true
                status = "Pairing service found. Type the 6-digit code shown in the system pane here."
            },
            onLost = {
                pairingReady = false
                status = "Pairing dialog closed. Reopen 'Pair device with pairing code' in the system pane."
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wireless debug setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This lets ClusterTune apply profiles without root, using Android's " +
                    "built-in Wireless debugging. You only need to pair once per boot."
            )

            Text("Status: $status")

            if (connected) {
                Text("✓ Ready. ClusterTune can now apply profiles. Return and select a profile.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            } else {
                // Step 1: developer options (only if not already enabled)
                if (!devOptionsEnabled) {
                    Text("1. Turn on Developer options first.")
                    OutlinedButton(
                        onClick = {
                            openAdjacent(context, Settings.ACTION_DEVICE_INFO_SETTINGS)
                            devOptionsEnabled = isDevOptionsEnabled(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open About phone (tap Build number 7×)")
                    }
                    Text(
                        "In About phone, tap 'Build number' seven times to unlock Developer " +
                            "options, then come back here.",
                    )
                    OutlinedButton(
                        onClick = { devOptionsEnabled = isDevOptionsEnabled(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I've enabled Developer options")
                    }
                } else {
                    // Step 2: open wireless debugging in SPLIT SCREEN
                    Text("1. Open Wireless debugging (it opens beside ClusterTune).")
                    OutlinedButton(
                        onClick = {
                            openAdjacent(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            startConnect()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Wireless debugging (split screen)")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "2. In the system pane, turn Wireless debugging ON, then tap " +
                            "'Pair device with pairing code'. Then tap Start pairing here:",
                    )
                    OutlinedButton(
                        onClick = { startPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start pairing")
                    }

                    if (pairingReady) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Read the 6-digit code from the system pane and type it HERE " +
                                        "(don't type it in the system dialog — that makes it close).",
                                )
                                OutlinedTextField(
                                    value = pairingCode,
                                    onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                                    label = { Text("6-digit pairing code") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        busy = true
                                        status = "Pairing…"
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                connectionManager.pair(
                                                    code = pairingCode,
                                                    onPaired = {},
                                                    onError = {},
                                                )
                                            }
                                            pairingReady = false
                                            busy = false
                                            startConnect()
                                        }
                                    },
                                    enabled = pairingCode.length == 6 && !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Pair")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("3. Already paired this boot? Just connect:")
                    OutlinedButton(
                        onClick = { startConnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

/** Whether Developer options are enabled on this device. */
private fun isDevOptionsEnabled(context: Context): Boolean {
    return Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        if (Build.TYPE == "eng") 1 else 0,
    ) != 0
}

/**
 * Launch a system settings screen in SPLIT SCREEN next to ClusterTune, so the
 * wireless-debugging pairing dialog stays visible and doesn't close when the
 * user types the code into ClusterTune. windowingMode=3 is split-screen.
 */
private fun openAdjacent(context: Context, action: String) {
    val intent = Intent(action).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    }
    val options = Bundle().apply { putInt("android.activity.windowingMode", 3) }
    runCatching { context.startActivity(intent, options) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}
