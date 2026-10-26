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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import com.taksolutions.takbeacon.ui.chat.MessagesScreen
import com.taksolutions.takbeacon.chat.ChatManager
import com.taksolutions.takbeacon.data.MessagingMode
import com.taksolutions.takbeacon.net.LinkState
import com.taksolutions.takbeacon.ui.servers.EnrollDialogs
import com.taksolutions.takbeacon.ui.servers.ManualServerDialog
import com.taksolutions.takbeacon.ui.servers.QrScannerDialog
import com.taksolutions.takbeacon.ui.servers.ServersScreen
import com.taksolutions.takbeacon.ui.settings.SettingsScreen
import com.taksolutions.takbeacon.ui.theme.BeaconTheme
import com.taksolutions.takbeacon.ui.theme.Mono

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** A chat thread to open, from a message notification. */
    private var openThreadRequest by mutableStateOf<String?>(null)
    /** The chat thread on screen; it gets no notifications while the app is in front. */
    private var chatOnScreen: String? = null

    /** Runtime permissions not granted yet; refreshed on every resume. */
    private var missing by mutableStateOf(emptyList<Perm>())
    /** Priority alerts may lift Do Not Disturb; refreshed on every resume. */
    private var dndAccess by mutableStateOf(true)
    /** Priority alerts may go full screen over the lock screen, and over other apps. */
    private var fullScreenAccess by mutableStateOf(true)
    private var overlayAccess by mutableStateOf(true)

    /** Set when the ATAK plugin opened us only to start tracking; we step back to ATAK after. */
    private var startRequestedByPlugin by mutableStateOf(false)
    /** This launch came from the plugin (to start tracking or review a server): no permission walk over ATAK. */
    private var launchedByPlugin = false
    /** Opened to approve a server from ATAK: go back to ATAK once it is approved or rejected. */
    private var reviewForPlugin by mutableStateOf(false)
    /** Show the Servers tab (a server from ATAK still needs signing in). */
    private var showServers by mutableStateOf(false)
    /** Counts resumes, so the permission walk continues after a settings page. */
    private var resumes by mutableStateOf(0)
    /** Ask for missing permissions now (the launch check was skipped because the plugin opened us). */
    private var walkRequest by mutableStateOf(0)
    /**
     * The plugin opened us, so the permission check was skipped; it runs at the next resume
     * the plugin didn't cause (the user opening the app, Recents, a rotation). Kept across
     * recreation, like [reviewForPlugin].
     */
    private var walkSkipped = false
    private var pluginIntentSinceResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        else {
            walkSkipped = savedInstanceState.getBoolean(STATE_WALK_SKIPPED)
            reviewForPlugin = savedInstanceState.getBoolean(STATE_REVIEW_FOR_PLUGIN)
        }
        setContent { BeaconTheme { App() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WALK_SKIPPED, walkSkipped)
        outState.putBoolean(STATE_REVIEW_FOR_PLUGIN, reviewForPlugin)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        // In the background, every message notifies.
        BeaconApp.from(this).chat.visibleThread = null
        BeaconApp.from(this).alerts.appInFront = false
    }

    override fun onResume() {
        super.onResume()
        BeaconApp.from(this).chat.visibleThread = chatOnScreen
        BeaconApp.from(this).alerts.appInFront = true
        refreshPermissions()
        resumes++
        // The user is here now (not the plugin opening us, nor an approval on screen): run the
        // permission check the plugin's launch skipped.
        if (walkSkipped && !pluginIntentSinceResume && !reviewForPlugin && PendingProfile.pending.value == null) {
            walkSkipped = false
            walkRequest++
        }
        pluginIntentSinceResume = false
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
                launchedByPlugin = true
                walkSkipped = true
                pluginIntentSinceResume = true
            }
            ACTION_REVIEW_ATAK_PROFILE -> Unit // the approval dialog shows whenever a profile is pending
            BridgeProtocol.ACTION_REVIEW_SERVER -> {
                launchedByPlugin = true
                walkSkipped = true
                pluginIntentSinceResume = true
                if (PendingProfile.pending.value != null) reviewForPlugin = true
                getSystemService(android.app.NotificationManager::class.java).cancel(BridgeService.APPROVAL_NOTIFICATION)
            }
            ChatManager.ACTION_OPEN_THREAD -> openThreadRequest = intent.getStringExtra(ChatManager.EXTRA_THREAD)
        }
    }

    private fun refreshPermissions() {
        missing = Perm.missing(this, chatOn = vm.settings.value?.messagingMode != MessagingMode.OFF)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        dndAccess = nm.isNotificationPolicyAccessGranted
        fullScreenAccess = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        overlayAccess = Settings.canDrawOverlays(this)
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
        val chat = BeaconApp.from(this).chat
        val chatData by chat.data.collectAsStateWithLifecycle()
        var openThread by rememberSaveable { mutableStateOf<String?>(null) }

        var tab by rememberSaveable { mutableStateOf(0) }
        var scanning by remember { mutableStateOf(false) }
        var manual by remember { mutableStateOf(false) }
        var manualError by remember { mutableStateOf<String?>(null) }
        var locationDisclosure by remember { mutableStateOf(false) }
        var backgroundDisclosure by remember { mutableStateOf(false) }
        var pinAction by remember { mutableStateOf<PinAction?>(null) }
        var lockAfterSet by remember { mutableStateOf(false) }
        // A special permission being explained, and one whose settings page is open.
        var special by remember { mutableStateOf<Perm?>(null) }
        var awaiting by remember { mutableStateOf<Perm?>(null) }
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
            if (walkAll && !(fromUser && blocked) && !startAfter) {
                // Walking everything: go on to the rest; the banner keeps showing this one.
                bootAfter = false; skipped += p; advance()
            } else {
                walkAll = false; startAfter = false; fromUser = false; bootAfter = false; skipped.clear()
            }
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
            val next = missing.firstOrNull { it !in skipped && (walkAll || it in Perm.TO_START) }
            when {
                next == Perm.LOCATION -> locationDisclosure = true
                next == Perm.NOTIFICATIONS -> notifications.launch(Perm.NOTIFICATIONS.permission)
                next == Perm.BACKGROUND -> backgroundDisclosure = true
                next != null -> special = next
                else -> {
                    walkAll = false; fromUser = false; skipped.clear()
                    if (startAfter) { startAfter = false; startTracking() }
                }
            }
        }

        /** "Not now" on a disclosure: skip it and go on when walking everything; otherwise stop. */
        fun declined(p: Perm) {
            if (walkAll && !startAfter) { skipped += p; advance() }
            else { walkAll = false; startAfter = false; fromUser = false; skipped.clear() }
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
            // Every launch: ask for whatever is missing (not when ATAK opened us just to start tracking).
            if (!launchedByPlugin && missing.isNotEmpty()) {
                if (!s.permissionsPrompted) vm.markPermissionsPrompted()
                requestPermissions(userAsked = false)
            }
            // "Start on boot" also means: start whenever the app is opened.
            // A locked beacon that was on (e.g. killed by an update) comes back on too.
            val restore = s.startOnBoot || (s.locked && s.wasRunning)
            if (restore && tracker.runState == RunState.STOPPED && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                startTracking()
            }
        }

        // A permission check the plugin's launch skipped (see walkRequest).
        LaunchedEffect(walkRequest, settings != null) {
            if (walkRequest == 0 || settings == null) return@LaunchedEffect
            refreshPermissions()
            if (missing.isNotEmpty() && !walkAll && !startAfter && special == null && awaiting == null &&
                !locationDisclosure && !backgroundDisclosure
            ) requestPermissions(userAsked = false)
        }

        // Back from a special permission's settings page: on to the next one.
        LaunchedEffect(resumes) {
            awaiting?.let { p -> awaiting = null; skipped += p; advance() }
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
                                    Text(listOf("Beacon", "Servers", "Settings", "Messages")[tab], style = MaterialTheme.typography.labelSmall,
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
                    if (settings?.messagingMode != MessagingMode.OFF) {
                        val unread = chatData.unread()
                        NavigationBarItem(tab == 3, { tab = 3 }, {
                            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, null)
                            }
                        }, label = { Text("Messages") })
                    }
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Outlined.Dns, null) }, label = { Text("Servers") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            val s = settings ?: return@Scaffold
            // A message notification was tapped.
            LaunchedEffect(openThreadRequest) {
                openThreadRequest?.let { tab = 3; openThread = it; openThreadRequest = null }
            }
            if (tab == 3 && s.messagingMode == MessagingMode.OFF) tab = 0
            // The open thread is read, and notifies nothing while on screen.
            val showing = if (tab == 3) openThread else null
            LaunchedEffect(showing, chatData.unread(showing)) {
                chatOnScreen = showing
                chat.visibleThread = showing
                showing?.let { chat.markRead(it) }
            }
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
                    unreadMessages = if (s.messagingMode == MessagingMode.OFF) 0 else chatData.unread(),
                    onOpenMessages = { tab = 3; openThread = null },
                    onLockToggle = {
                        when {
                            s.locked -> pinAction = PinAction.UNLOCK
                            s.hasPin -> vm.lock()
                            else -> { lockAfterSet = true; pinAction = PinAction.SET }
                        }
                    },
                )
                3 -> MessagesScreen(
                    data = chatData,
                    offered = listOfNotNull(chat.threadFor(ChatManager.KEY_ALL, s), chat.threadFor(chat.teamKey(s), s)),
                    openKey = openThread,
                    onOpen = { openThread = it },
                    canSend = !s.locked && s.messagingMode == MessagingMode.FULL,
                    sendBlockedReason = when {
                        s.messagingMode == MessagingMode.READ_ONLY -> "Replies are turned off by your organization"
                        s.locked -> "Locked: unlock to reply"
                        else -> null
                    },
                    connected = links.values.any { it.state == LinkState.CONNECTED },
                    onSend = { key, text -> chat.send(key, text) },
                    onUnlock = { pinAction = PinAction.UNLOCK },
                    contentPadding = padding,
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
                    onRetry = vm::retryServer,
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
                    onClearMessages = { chat.clear(); vm.toast("Messages cleared") },
                    onTestAlert = { BeaconApp.from(this).alerts.trigger("test", "TAK Beacon", "This is a test of the priority alert.", null) },
                    dndAccess = dndAccess,
                    onDndAccess = { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } },
                    fullScreenAccess = fullScreenAccess,
                    onFullScreenAccess = {
                        if (Build.VERSION.SDK_INT >= 34) runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")))
                        }
                    },
                    overlayAccess = overlayAccess,
                    onOverlayAccess = {
                        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                    },
                )
            }
        }

        // --- dialogs --------------------------------------------------------------------
        val alert by BeaconApp.from(this).alerts.state.collectAsStateWithLifecycle()
        alert?.let { a ->
            // Priority alert: only Acknowledge closes it (it is allowed while PIN-locked).
            AlertDialog(
                onDismissRequest = {},
                properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                title = { Text("Priority alert: ${a.keyword}", color = MaterialTheme.colorScheme.onErrorContainer) },
                text = {
                    Column {
                        Text(a.fromCallsign, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(a.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp))
                        if (!a.sounding) Text("Alarm stopped after 2 minutes.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.Button(
                        onClick = {
                            BeaconApp.from(this).alerts.acknowledge()
                            a.threadKey?.let { tab = 3; openThread = it }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Acknowledge") }
                },
            )
        }
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
                onDismissRequest = { locationDisclosure = false; declined(Perm.LOCATION) },
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
                    locationDisclosure = false; declined(Perm.LOCATION)
                }) { Text("Not now") } },
            )
        }
        if (backgroundDisclosure) {
            AlertDialog(
                onDismissRequest = { backgroundDisclosure = false; bootAfter = false; declined(Perm.BACKGROUND) },
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
                    backgroundDisclosure = false; bootAfter = false; declined(Perm.BACKGROUND)
                }) { Text("Not now") } },
            )
        }
        pending?.let { p -> AtakApprovalDialog(p) }
        LaunchedEffect(showServers) { if (showServers) { tab = 1; showServers = false } }

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

        special?.let { p ->
            val skip = { special = null; skipped += p; advance() }
            AlertDialog(
                onDismissRequest = skip,
                title = { Text(specialTitle(p)) },
                text = { Text(specialText(p)) },
                confirmButton = {
                    TextButton(onClick = {
                        special = null
                        awaiting = p
                        if (!openSpecial(p)) { awaiting = null; skipped += p; advance() }
                    }) { Text(if (p == Perm.BATTERY) "Allow" else "Open settings") }
                },
                dismissButton = { TextButton(onClick = skip) { Text("Not now") } },
            )
        }
    }

    private fun specialTitle(p: Perm) = when (p) {
        Perm.BATTERY -> "Keep TAK Beacon running"
        Perm.OVERLAY -> "Show priority alerts over other apps"
        Perm.FULL_SCREEN -> "Show priority alerts full screen"
        Perm.DND -> "Let priority alerts through Do Not Disturb"
        else -> p.label
    }

    private fun specialText(p: Perm) = when (p) {
        Perm.BATTERY -> "Android is limiting TAK Beacon's battery use, which can stop position reports while the screen " +
            "is off. Allow TAK Beacon to run without battery restrictions?"
        Perm.OVERLAY -> "So a priority alert can open in front of the app you are using. On the next screen, tap " +
            "TAK Beacon and turn on \"Allow display over other apps\"."
        Perm.FULL_SCREEN -> "So a priority alert can wake the phone and fill the screen when it is locked. On the next " +
            "screen, turn on full-screen notifications for TAK Beacon."
        Perm.DND -> "So a priority alert can sound when Do Not Disturb would silence alarms. TAK Beacon lifts Do Not " +
            "Disturb only while an alert sounds, then restores it. On the next screen, tap TAK Beacon and allow access."
        else -> p.effect
    }

    /** Opens the settings page (or Android's prompt) for a special permission; false if there is none. */
    private fun openSpecial(p: Perm): Boolean {
        val pkg = Uri.parse("package:$packageName")
        val intent = when (p) {
            Perm.BATTERY -> { requestBatteryExemption(); return true }
            Perm.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Perm.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else return false
            Perm.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            else -> return false
        }
        return runCatching { startActivity(intent) }.isSuccess
    }

    /** Across the top of the Beacon screen while anything is missing: what, what it breaks, and a Grant button. */
    @Composable
    private fun PermissionBanner(missing: List<Perm>, onClick: () -> Unit) {
        val on = MaterialTheme.colorScheme.onErrorContainer
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (missing.size == 1) "A permission is missing" else "${missing.size} permissions are missing",
                    style = MaterialTheme.typography.titleMedium, color = on,
                )
            }
            Text(if (missing.size == 1) "TAK Beacon won't work fully until it is granted:" else "TAK Beacon won't work fully until they are granted:", style = MaterialTheme.typography.bodySmall,
                color = on, modifier = Modifier.padding(top = 4.dp))
            missing.forEach { p ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("\u2022 ${p.label}: ") }
                        append(p.effect)
                    },
                    style = MaterialTheme.typography.bodySmall, color = on, modifier = Modifier.padding(top = 4.dp),
                )
            }
            androidx.compose.material3.Button(
                onClick = onClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Grant permissions") }
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
                    vm.approvePendingProfile { server -> afterReview(server) }
                    getSystemService(android.app.NotificationManager::class.java).cancel(BridgeService.APPROVAL_NOTIFICATION)
                }) { Text("Approve") }
            },
            dismissButton = { TextButton(onClick = { vm.rejectPendingProfile(); afterReview(null) }) { Text("Reject") } },
        )
    }

    /**
     * After approving or rejecting a server the plugin opened us for: back to ATAK, unless
     * the new server still needs signing in for a certificate. Then stay, on the Servers tab.
     */
    private fun afterReview(server: com.taksolutions.takbeacon.data.ServerConfig?) {
        if (!reviewForPlugin) return
        reviewForPlugin = false
        if (server?.needsEnrollment == true) {
            // Staying in the app: now is the time for the permission check the launch skipped.
            showServers = true
            walkSkipped = false
            walkRequest++
        } else moveTaskToBack(true)
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
        private const val STATE_WALK_SKIPPED = "walk_skipped"
        private const val STATE_REVIEW_FOR_PLUGIN = "review_for_plugin"
    }
}
