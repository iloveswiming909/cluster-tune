package com.aure.clustertune.ui

import android.content.Intent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aure.clustertune.jdwp.WirelessDebugConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * One-time-per-boot setup for the no-root "Wireless debug" execution method.
 *
 * Flow:
 *  1. User enables Wireless debugging in Developer options (button opens it).
 *  2. We discover the pairing port (mDNS) and, when the user opens
 *     "Pair device with pairing code", show a field for the 6-digit code.
 *  3. Pairing succeeds -> we discover the connect port -> "Connected".
 *
 * All the transport is handled by [WirelessDebugConnectionManager], which
 * wraps the vendored jdwp-injector resolver/pairing classes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDebugSetupScreen(
    connectionManager: WirelessDebugConnectionManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Not connected") }
    var pairingReady by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(connectionManager.connectionInfo != null) }
    var busy by remember { mutableStateOf(false) }

    fun startConnect() {
        status = "Looking for wireless debugging…"
        connectionManager.startConnectDiscovery(
            onConnected = { info ->
                connected = true
                status = "Connected (${info.host}:${info.port})"
            },
            onUnavailable = {
                status = "Wireless debugging not found. Enable it, then Pair below."
            },
        )
    }

    fun startPairing() {
        status = "Waiting for the pairing dialog…"
        connectionManager.startPairingDiscovery(
            onPairingPortFound = { _, _ ->
                pairingReady = true
                status = "Enter the 6-digit pairing code shown on screen."
            },
            onLost = {
                pairingReady = false
                status = "Pairing dialog closed. Reopen 'Pair device with pairing code'."
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
                Text("✓ Ready. You can now select 'Wireless debug (no root)' as the execution method in Settings and apply profiles.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            } else {
                Text("1. Enable Wireless debugging in Developer options.")
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        startConnect()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Developer options")
                }

                Spacer(Modifier.height(4.dp))
                Text("2. If this is the first time, tap 'Pair device with pairing code' and pair here:")
                OutlinedButton(
                    onClick = { startPairing() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start pairing")
                }

                if (pairingReady) {
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
                                // After pairing, look for the connect port.
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
