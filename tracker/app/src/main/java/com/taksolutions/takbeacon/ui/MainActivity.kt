package com.taksolutions.takbeacon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.annotation.SuppressLint
import android.net.Uri
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.R
import com.taksolutions.takbeacon.bridge.BridgeProtocol
import com.taksolutions.takbeacon.bridge.BridgeService
import com.taksolutions.takbeacon.bridge.PendingProfile
import com.taksolutions.takbeacon.enroll.EnrollmentLink
import com.taksolutions.takbeacon.security.CertStore
import com.taksolutions.takbeacon.service.BeaconService
import com.taksolutions.takbeacon.service.RunState
import com.taksolutions.takbeacon.ui.components.PinDialog
import com.taksolutions.takbeacon.ui.settings.PinAction
import com.taksolutions.takbeacon.ui.home.HomeScreen
import com.taksolutions.takbeacon.ui.servers.EnrollDialogs
import com.taksolutions.takbeacon.ui.servers.ManualServerDialog
import com.taksolutions.takbeacon.ui.servers.QrScannerDialog
import com.taksolutions.takbeacon.ui.servers.ServersScreen
import com.taksolutions.takbeacon.ui.settings.SettingsScreen
import com.taksolutions.takbeacon.ui.theme.BeaconTheme
import com.taksolutions.takbeacon.ui.theme.Mono

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** Set when the ATAK plugin opened us only to start tracking; we step back to ATAK after. */
    private var startRequestedByPlugin by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { BeaconTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (vm.settings.value?.kioskLock == true) runCatching { startLockTask() }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> when (intent.data?.scheme) {
                "content" -> intent.data?.let(vm::importDataPackage)
                else -> intent.dataString?.let { EnrollmentLink.parse(it) }?.let(vm::handleEnrollRequest)
                    ?: vm.toast("That enrollment link isn't valid")
            }
            // A locked beacon's on/off can't be changed from outside either.
            BridgeProtocol.ACTION_START_TRACKING -> if (!BeaconApp.from(this).locked) startRequestedByPlugin = true
            ACTION_REVIEW_ATAK_PROFILE -> Unit // the approval dialog shows whenever a profile is pending
        }
    }

    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val settings by vm.settings.collectAsStateWithLifecycle()
        val tracker by vm.tracker.collectAsStateWithLifecycle()
        val servers by vm.servers.collectAsStateWithLifecycle()
        val links by vm.links.collectAsStateWithLifecycle()
        val enroll by vm.enroll.collectAsStateWithLifecycle()
        val toast by vm.toast.collectAsStateWithLifecycle()
        val pending by vm.pendingProfile.collectAsStateWithLifecycle()

        var tab by rememberSaveable { mutableStateOf(0) }
        var scanning by remember { mutableStateOf(false) }
        var manual by remember { mutableStateOf(false) }
        var manualError by remember { mutableStateOf<String?>(null) }
        var locationDisclosure by remember { mutableStateOf(false) }
        var backgroundDisclosure by remember { mutableStateOf(false) }
        var pinAction by remember { mutableStateOf<PinAction?>(null) }
        var lockAfterSet by remember { mutableStateOf(false) }
        var batteryPrompt by remember { mutableStateOf(false) }
        // Once per launch (not per rotation): battery check and start-on-launch.
        var launchChecksDone by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

        // --- permissions ---------------------------------------------------------
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            startTracking()
        }
        val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else startTracking()
            } else {
                vm.toast("TAK Beacon needs precise location to report your position")
            }
        }
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.updateSettings { it.copy(startOnBoot = granted) }
            if (!granted) vm.toast("Start on boot needs location access \"Allow all the time\"")
        }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanning = true else vm.toast("Camera access is needed to scan QR codes")
        }
        val dataPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vm::importDataPackage)
        }

        fun requestStart() {
            when {
                !has(Manifest.permission.ACCESS_FINE_LOCATION) -> locationDisclosure = true
                Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) ->
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> startTracking()
            }
        }

        // The plugin opened us to start tracking.
        LaunchedEffect(settings != null) {
            val s = settings ?: return@LaunchedEffect
            if (launchChecksDone) return@LaunchedEffect
            launchChecksDone = true
            // "Start on boot" also means: start whenever the app is opened.
            if (s.startOnBoot && tracker.runState == RunState.STOPPED && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startTracking()
            }
            // Reports stop in Doze when the system restricts the app's battery use.
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) batteryPrompt = true
        }

        LaunchedEffect(startRequestedByPlugin) {
            if (startRequestedByPlugin) {
                startRequestedByPlugin = false
                val ready = has(Manifest.permission.ACCESS_FINE_LOCATION)
                requestStart()
                if (ready) moveTaskToBack(true)
            }
        }

        // --- layout --------------------------------------------------------------------
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painterResource(R.drawable.brand_lighthouse), null, Modifier.size(30.dp),
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("TAK Beacon", style = MaterialTheme.typography.titleLarge)
                                settings?.let {
                                    Text(listOf("Beacon", "Servers", "Settings")[tab], style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Outlined.CellTower, null) }, label = { Text("Beacon") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Outlined.Dns, null) }, label = { Text("Servers") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val s = settings ?: return@Scaffold
            when (tab) {
                0 -> HomeScreen(
                    settings = s, tracker = tracker, servers = servers, links = links, contentPadding = padding,
                    onStart = ::requestStart,
                    onStop = { BeaconService.stop(this) },
                    onEmergency = { type ->
                        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) BeaconService.emergency(this, type)
                        else locationDisclosure = true
                    },
                    onCancelEmergency = { BeaconService.cancelEmergency(this) },
                    onCoordFormat = { f -> vm.updateSettings { it.copy(coordFormat = f) } },
                    onOpenServers = { tab = 1 },
                    onLockToggle = {
                        when {
                            s.locked -> pinAction = PinAction.UNLOCK
                            s.hasPin -> vm.lock()
                            else -> { lockAfterSet = true; pinAction = PinAction.SET }
                        }
                    },
                )
                1 -> ServersScreen(
                    servers = servers, links = links, contentPadding = padding,
                    certSummary = vm::certSummary,
                    onScan = { if (has(Manifest.permission.CAMERA)) scanning = true else camera.launch(Manifest.permission.CAMERA) },
                    onSignIn = { vm.showEnrollForm() },
                    onImport = { dataPackage.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onManual = { manualError = null; manual = true },
                    onToggle = { id, on -> vm.setServerEnabled(id, on) },
                    onEnroll = vm::enrollExisting,
                    onDelete = vm::deleteServer,
                    locked = s.locked,
                    onUnlock = { pinAction = PinAction.UNLOCK },
                )
                else -> SettingsScreen(
                    s = s, contentPadding = padding,
                    onChange = { f -> vm.updateSettings(f) },
                    onStartOnBoot = { on ->
                        when {
                            !on -> vm.updateSettings { it.copy(startOnBoot = false) }
                            Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                                vm.updateSettings { it.copy(startOnBoot = true) }
                            !has(Manifest.permission.ACCESS_FINE_LOCATION) -> vm.toast("Start the beacon once first to grant location access")
                            else -> backgroundDisclosure = true
                        }
                    },
                    onKiosk = { on ->
                        vm.updateSettings { it.copy(kioskLock = on) }
                        runCatching { if (on) startLockTask() else stopLockTask() }
                    },
                    onBatterySettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    onForgetAtak = vm::forgetAtak,
                    onPin = { pinAction = it },
                )
            }
        }

        // --- dialogs --------------------------------------------------------------------
        if (scanning) {
            QrScannerDialog(
                onResult = { text ->
                    scanning = false
                    EnrollmentLink.parse(text)?.let(vm::handleEnrollRequest)
                        ?: vm.toast("That QR code isn't a TAK enrollment or server code")
                },
                onDismiss = { scanning = false },
            )
        }
        if (manual) {
            ManualServerDialog(
                error = manualError,
                onDismiss = { manual = false },
                onSave = { name, host, port, transport, cUri, cPw, tUri, tPw ->
                    vm.addManualServer(name, host, port, transport, cUri, cPw, tUri, tPw) { err ->
                        if (err == null) manual = false else manualError = err
                    }
                },
            )
        }
        EnrollDialogs(
            state = enroll,
            onSubmit = { vm.enroll(it) },
            onTrust = { req, sha -> vm.enroll(req, sha) },
            onDismiss = vm::dismissEnroll,
        )
        if (locationDisclosure) {
            // Prominent disclosure, shown before the system prompt (Google Play user data policy).
            AlertDialog(
                onDismissRequest = { locationDisclosure = false },
                title = { Text("Share your location") },
                text = {
                    Text(
                        "TAK Beacon collects your device's precise location and sends it to the TAK servers you have added, " +
                            "so your team can see you on their map. This continues while the app is closed or not in use, " +
                            "for as long as the beacon is on. Location is not sent anywhere else.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        locationDisclosure = false
                        location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { locationDisclosure = false }) { Text("Not now") } },
            )
        }
        if (backgroundDisclosure) {
            AlertDialog(
                onDismissRequest = { backgroundDisclosure = false },
                title = { Text("Allow location all the time") },
                text = {
                    Text(
                        "To resume reporting after your phone restarts, TAK Beacon needs location access \"Allow all the time\". " +
                            "Your location is still only sent to the TAK servers you added, and only while the beacon is on.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        backgroundDisclosure = false
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { backgroundDisclosure = false }) { Text("Cancel") } },
            )
        }
        pending?.let { p -> AtakApprovalDialog(p) }

        pinAction?.let { action ->
            val close = { pinAction = null; lockAfterSet = false }
            when (action) {
                PinAction.SET -> PinDialog(
                    "Set a PIN",
                    if (lockAfterSet) "Choose a PIN. TAK Beacon locks as soon as it is set."
                    else "Choose a PIN for locking TAK Beacon. It can't be recovered: keep it safe.",
                    askCurrent = false, askNew = true, confirmLabel = if (lockAfterSet) "Set and lock" else "Set PIN",
                    onDismiss = close,
                ) { _, new ->
                    vm.setPin(null, new).also { err -> if (err == null && lockAfterSet) vm.lock() }
                }
                PinAction.CHANGE -> PinDialog("Change PIN", "Enter the current PIN, then the new one.",
                    askCurrent = true, askNew = true, confirmLabel = "Change", onDismiss = close) { cur, new -> vm.setPin(cur, new) }
                PinAction.REMOVE -> PinDialog("Remove PIN", "Without a PIN, TAK Beacon can't be locked.",
                    askCurrent = true, askNew = false, confirmLabel = "Remove", onDismiss = close) { cur, _ -> vm.removePin(cur) }
                PinAction.UNLOCK -> PinDialog("Unlock TAK Beacon", "Enter the PIN to change the beacon or its settings.",
                    askCurrent = true, askNew = false, confirmLabel = "Unlock", onDismiss = close) { cur, _ -> vm.unlock(cur) }
            }
        }

        if (batteryPrompt && !locationDisclosure && !backgroundDisclosure && pending == null && pinAction == null) {
            AlertDialog(
                onDismissRequest = { batteryPrompt = false },
                title = { Text("Keep TAK Beacon running") },
                text = {
                    Text(
                        "Android is limiting TAK Beacon's battery use, which can stop position reports while the " +
                            "screen is off. Allow TAK Beacon to run without battery restrictions?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        batteryPrompt = false
                        requestBatteryExemption()
                    }) { Text("Allow") }
                },
                dismissButton = { TextButton(onClick = { batteryPrompt = false }) { Text("Not now") } },
            )
        }
    }

    @Composable
    private fun AtakApprovalDialog(p: PendingProfile.Pending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Accept server from ATAK?") },
            text = {
                Column {
                    val certs = p.profile.clientP12 != null || p.profile.trustP12 != null
                    Text("ATAK (${p.caller.packageName}) wants to give TAK Beacon its connection to ${p.profile.serverName} " +
                        "(${p.profile.host}:${p.profile.port})" + (if (certs) ", including its certificates" else "") +
                        (p.profile.callsign?.let { ", and your callsign $it" } ?: "") + ".")
                    Spacer(Modifier.size(8.dp))
                    Text("Approving trusts this ATAK build for future updates. Its signing certificate:",
                        style = MaterialTheme.typography.bodySmall)
                    Text(CertStore.prettyFingerprint(p.caller.signerSha256).take(47) + "…", fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.approvePendingProfile()
                    getSystemService(android.app.NotificationManager::class.java).cancel(BridgeService.APPROVAL_NOTIFICATION)
                }) { Text("Approve") }
            },
            dismissButton = { TextButton(onClick = vm::rejectPendingProfile) { Text("Reject") } },
        )
    }

    /** Android's own "allow unrestricted battery" prompt; falls back to the settings list. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun startTracking() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        runCatching { BeaconService.start(this) }.onFailure { vm.toast(it.message ?: "Could not start") }
    }

    companion object {
        const val ACTION_REVIEW_ATAK_PROFILE = "com.taksolutions.takbeacon.REVIEW_ATAK_PROFILE"
    }
}
