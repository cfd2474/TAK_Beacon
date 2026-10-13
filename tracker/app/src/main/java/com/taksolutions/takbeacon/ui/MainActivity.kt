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
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
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

    /** Runtime permissions not granted yet; refreshed on every resume. */
    private var missing by mutableStateOf(emptyList<Perm>())

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
        refreshPermissions()
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
            BridgeProtocol.ACTION_START_TRACKING -> BeaconApp.from(this).let { app ->
                // Locked: only to restore a beacon that was on (e.g. after an update).
                if (!app.locked || app.wasRunning) startRequestedByPlugin = true
            }
            ACTION_REVIEW_ATAK_PROFILE -> Unit // the approval dialog shows whenever a profile is pending
        }
    }

    private fun refreshPermissions() {
        missing = Perm.missing(this)
    }

    /** For a permission Android will no longer prompt for (denied twice): its own settings page. */
    private fun openAppSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        vm.toast("Open Permissions, then allow Location \"All the time\" and Notifications")
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
        val managed by BeaconApp.from(this).managed.state.collectAsStateWithLifecycle()

        var tab by rememberSaveable { mutableStateOf(0) }
        var scanning by remember { mutableStateOf(false) }
        var manual by remember { mutableStateOf(false) }
        var manualError by remember { mutableStateOf<String?>(null) }
        var locationDisclosure by remember { mutableStateOf(false) }
        var backgroundDisclosure by remember { mutableStateOf(false) }
        var pinAction by remember { mutableStateOf<PinAction?>(null) }
        var lockAfterSet by remember { mutableStateOf(false) }
        var batteryPrompt by remember { mutableStateOf(false) }
        // Permission flow: walk every missing permission (first run, banner, Settings),
        // or only what the beacon needs to start; [fromUser] when the user asked for it.
        var walkAll by remember { mutableStateOf(false) }
        var startAfter by remember { mutableStateOf(false) }
        var fromUser by remember { mutableStateOf(false) }
        var bootAfter by remember { mutableStateOf(false) }
        val skipped = remember { mutableSetOf<Perm>() }
        // Once per launch (not per rotation): battery check and start-on-launch.
        var launchChecksDone by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

        // --- permissions ---------------------------------------------------------
        lateinit var advance: () -> Unit

        // When a prompt was launched: a denial that comes back at once means Android showed nothing.
        val askedAt = remember { longArrayOf(0L) }
        fun denied(p: Perm, message: String) {
            refreshPermissions()
            // Denied instantly with no rationale: Android won't ask again, only its settings page can.
            val blocked = SystemClock.elapsedRealtime() - askedAt[0] < 600 && !shouldShowRequestPermissionRationale(p.permission)
            if (fromUser && blocked) openAppSettings() else vm.toast(message)
            walkAll = false; startAfter = false; fromUser = false; bootAfter = false; skipped.clear()
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Not fatal: the beacon runs without its notification permission, just less visibly.
            if (!it) skipped += Perm.NOTIFICATIONS
            advance()
        }
        val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) advance()
            else denied(Perm.LOCATION, "TAK Beacon needs precise location to report your position")
        }
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (bootAfter) {
                bootAfter = false
                vm.updateSettings { it.copy(startOnBoot = granted) }
            }
            if (granted) advance()
            else denied(Perm.BACKGROUND, "Location \"Allow all the time\" keeps the beacon reporting with the screen off and after a restart")
        }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanning = true else vm.toast("Camera access is needed to scan QR codes")
        }
        val dataPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vm::importDataPackage)
        }

        advance = {
            refreshPermissions()
            val next = missing.firstOrNull { it !in skipped && (walkAll || it != Perm.BACKGROUND) }
            when (next) {
                Perm.LOCATION -> locationDisclosure = true
                Perm.NOTIFICATIONS -> notifications.launch(Perm.NOTIFICATIONS.permission)
                Perm.BACKGROUND -> backgroundDisclosure = true
                null -> {
                    walkAll = false; fromUser = false; skipped.clear()
                    if (startAfter) { startAfter = false; startTracking() }
                }
            }
        }

        /** Every missing permission, one prompt after another (first run, banner, Settings). */
        fun requestPermissions(userAsked: Boolean) {
            walkAll = true; fromUser = userAsked; skipped.clear()
            advance()
        }

        fun requestStart() {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS))) {
                startTracking()
            } else {
                startAfter = true; fromUser = true; skipped.clear()
                advance()
            }
        }

        // The plugin opened us to start tracking.
        LaunchedEffect(settings != null) {
            val s = settings ?: return@LaunchedEffect
            if (launchChecksDone) return@LaunchedEffect
            launchChecksDone = true
            refreshPermissions()
            if (!s.permissionsPrompted && missing.isNotEmpty()) {
                vm.markPermissionsPrompted()
                requestPermissions(userAsked = false)
            }
            // "Start on boot" also means: start whenever the app is opened.
            // A locked beacon that was on (e.g. killed by an update) comes back on too.
            val restore = s.startOnBoot || (s.locked && s.wasRunning)
            if (restore && tracker.runState == RunState.STOPPED && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
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
                Column {
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
                if (tab == 0 && settings != null && missing.isNotEmpty()) {
                    PermissionBanner(missing) { requestPermissions(userAsked = true) }
                }
                }
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
                        else requestPermissions(userAsked = true)
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
                    managedProblem = managed.problem,
                    managedNeedsTrust = managed.pendingTrust != null,
                    onReviewTrust = vm::reviewManagedTrust,
                )
                else -> SettingsScreen(
                    s = s, contentPadding = padding,
                    onChange = { f -> vm.updateSettings(f) },
                    onStartOnBoot = { on ->
                        when {
                            !on -> vm.updateSettings { it.copy(startOnBoot = false) }
                            Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                                vm.updateSettings { it.copy(startOnBoot = true) }
                            else -> { bootAfter = true; requestPermissions(userAsked = true) }
                        }
                    },
                    onKiosk = { on ->
                        vm.updateSettings { it.copy(kioskLock = on) }
                        runCatching { if (on) startLockTask() else stopLockTask() }
                    },
                    onBatterySettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                    onForgetAtak = vm::forgetAtak,
                    onPin = { pinAction = it },
                    missingPermissions = missing.map { it.label },
                    onGrantPermissions = { requestPermissions(userAsked = true) },
                    managedFields = managed.fields,
                    managedByOrg = managed.managed,
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
                onDismissRequest = { locationDisclosure = false; walkAll = false; startAfter = false; fromUser = false },
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
                        askedAt[0] = SystemClock.elapsedRealtime()
                        location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = {
                    locationDisclosure = false; walkAll = false; startAfter = false; fromUser = false
                }) { Text("Not now") } },
            )
        }
        if (backgroundDisclosure) {
            AlertDialog(
                onDismissRequest = { backgroundDisclosure = false; walkAll = false; bootAfter = false; fromUser = false },
                title = { Text("Allow location all the time") },
                text = {
                    Text(
                        "TAK Beacon needs location access \"Allow all the time\" to keep reporting while the screen is off, " +
                            "after your phone restarts, and when ATAK starts it. On the next screen, choose \"Allow all the time\". " +
                            "Your location is still only sent to the TAK servers you added, and only while the beacon is on.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        backgroundDisclosure = false
                        askedAt[0] = SystemClock.elapsedRealtime()
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = {
                    backgroundDisclosure = false; walkAll = false; bootAfter = false; fromUser = false
                }) { Text("Not now") } },
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

        if (batteryPrompt && !walkAll && !startAfter && !locationDisclosure && !backgroundDisclosure &&
            pending == null && pinAction == null) {
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

    /** Across the top of the Beacon screen while a permission is missing; tap to ask again. */
    @Composable
    private fun PermissionBanner(missing: List<Perm>, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Missing permissions", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text(missing.joinToString { it.label } + ". Tap to grant.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
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
