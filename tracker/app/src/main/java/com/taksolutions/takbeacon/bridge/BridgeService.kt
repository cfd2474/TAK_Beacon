package com.taksolutions.takbeacon.bridge

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import com.taksolutions.takbeacon.BeaconApp
import com.taksolutions.takbeacon.BuildConfig
import com.taksolutions.takbeacon.R
import com.taksolutions.takbeacon.data.EmergencyType
import com.taksolutions.takbeacon.service.BeaconService
import com.taksolutions.takbeacon.service.BeaconStatus
import com.taksolutions.takbeacon.ui.MainActivity
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Bound by the ATAK plugin. Every message is checked against the sender's UID:
 * only ATAK packages are served at all, and a server profile (which carries
 * certificates) is applied only from the ATAK build the user approved — the
 * first push from an unknown signer waits for the user in [PendingProfile].
 */
class BridgeService : Service() {

    private val app by lazy { BeaconApp.from(this) }
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == BridgeProtocol.ACTION_BIND) messenger.binder else null

    /** The plugin unbound, or ATAK's process died: ATAK is no longer running. */
    override fun onUnbind(intent: Intent?): Boolean {
        BeaconStatus.atakRunning = false
        return false
    }

    private fun handle(msg: Message) {
        val caller = callerOf(msg.sendingUid)
        if (caller == null) {
            Log.w(TAG, "Rejected bridge message from uid ${msg.sendingUid}")
            reply(msg, ok = false, "Caller is not ATAK")
            return
        }
        // While PIN-locked, ATAK cannot switch the beacon on or off or change its servers.
        // (START is allowed only to restore a beacon that was on when it was locked,
        // e.g. after an app update killed it: that is not a change.)
        val lockedOut = when (msg.what) {
            BridgeProtocol.MSG_STOP, BridgeProtocol.MSG_PUSH_PROFILE -> true
            BridgeProtocol.MSG_START -> !app.wasRunning
            else -> false
        }
        if (app.locked && lockedOut) {
            reply(msg, false, BridgeProtocol.LOCKED)
            return
        }
        when (msg.what) {
            BridgeProtocol.MSG_HELLO, BridgeProtocol.MSG_QUERY -> sendState(msg.replyTo, caller)
            BridgeProtocol.MSG_HEARTBEAT -> {
                // 1: ATAK is running (sent every few seconds); 0: ATAK is shutting down.
                BeaconStatus.atakRunning = msg.arg1 == 1
            }
            BridgeProtocol.MSG_START -> {
                // Android 12+ refuses most background FGS starts; on "needs_foreground"
                // the plugin opens ACTION_START_TRACKING from ATAK's foreground context.
                // Without location permission the service can't run; the app's own
                // screen asks for it, so that is a foreground start too.
                val granted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val ok = granted && try {
                    BeaconService.start(this); true
                } catch (_: IllegalStateException) {
                    false
                }
                reply(msg, ok, if (ok) "Tracking started" else "needs_foreground")
            }
            BridgeProtocol.MSG_STOP -> { BeaconService.stop(this); reply(msg, true, "Tracking stopped") }
            BridgeProtocol.MSG_EMERGENCY -> {
                val type = msg.data?.getString(BridgeProtocol.KEY_EMERGENCY)
                    ?.let { runCatching { EmergencyType.valueOf(it) }.getOrNull() }
                if (type == null) reply(msg, false, "Unknown emergency type")
                else if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) reply(msg, false, "needs_foreground")
                else runCatching { BeaconService.emergency(this, type) }
                    .onSuccess { reply(msg, true, "Emergency sent") }
                    .onFailure { reply(msg, false, "needs_foreground") }
            }
            BridgeProtocol.MSG_CANCEL_EMERGENCY -> { BeaconService.cancelEmergency(this); reply(msg, true, "Emergency cancelled") }
            BridgeProtocol.MSG_PUSH_PROFILE -> receiveProfile(msg, caller)
            else -> reply(msg, false, "Unknown request ${msg.what}")
        }
    }

    private fun receiveProfile(msg: Message, caller: Caller) {
        val data = msg.data ?: return reply(msg, false, "Empty profile")
        val profile = runCatching { AtakProfile.fromBundle(data) }.getOrElse {
            return reply(msg, false, it.message ?: "Invalid profile")
        }
        val trusted = app.trustedAtakSigner
        if (trusted != null && trusted.equals(caller.signerSha256, ignoreCase = true)) {
            app.appScope.launch {
                val result = ProfileApplier(app).apply(profile)
                // replyTo is a binder proxy; posting from a worker thread is fine.
                reply(msg, result.isSuccess, result.fold({ "Profile applied: ${it.name}" }, { it.message ?: "Failed" }))
            }
        } else {
            PendingProfile.offer(profile, caller)
            notifyApprovalNeeded(caller)
            reply(msg, false, "awaiting_approval")
        }
    }

    private fun sendState(replyTo: Messenger?, caller: Caller) {
        replyTo ?: return
        val st = BeaconStatus.state.value
        val trusted = app.trustedAtakSigner
        val b = Bundle().apply {
            putInt(BridgeProtocol.KEY_PROTOCOL, BridgeProtocol.VERSION)
            putLong(BridgeProtocol.KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE.toLong())
            putString(BridgeProtocol.KEY_APP_VERSION_NAME, BuildConfig.VERSION_NAME)
            putString(BridgeProtocol.KEY_RUN_STATE, st.runState.name)
            putInt(BridgeProtocol.KEY_SERVERS, app.servers.servers.value.count { it.enabled })
            putInt(BridgeProtocol.KEY_CONNECTED, app.connections.connectedCount())
            st.lastSentMs?.let { putLong(BridgeProtocol.KEY_LAST_SENT_MS, it) }
            st.emergency?.let { putString(BridgeProtocol.KEY_EMERGENCY, it.name) }
            putBoolean(BridgeProtocol.KEY_SATELLITE, st.onSatellite)
            putBoolean(BridgeProtocol.KEY_LOCKED, app.locked)
            putBoolean(BridgeProtocol.KEY_TRUSTED, trusted != null && trusted.equals(caller.signerSha256, true))
            putBoolean(BridgeProtocol.KEY_APPROVAL_PENDING, PendingProfile.pending.value != null)
        }
        send(replyTo, Message.obtain(null, BridgeProtocol.MSG_STATE).apply { data = b })
    }

    private fun reply(req: Message, ok: Boolean, text: String) {
        val to = req.replyTo ?: return
        send(to, Message.obtain(null, BridgeProtocol.MSG_RESULT, if (ok) 1 else 0, req.what).apply {
            data = Bundle().apply { putString(BridgeProtocol.KEY_MESSAGE, text) }
        })
    }

    private fun send(to: Messenger, m: Message) {
        try { to.send(m) } catch (_: RemoteException) { /* plugin went away */ }
    }

    private fun notifyApprovalNeeded(caller: Caller) {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_REVIEW_ATAK_PROFILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, BeaconApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle("ATAK wants to share a server")
            .setContentText("Tap to review the connection ${caller.packageName} is sending")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(APPROVAL_NOTIFICATION, n) }
        // The plugin opens the approval itself (from ATAK, which is in front). With "Display
        // over other apps" allowed the app can also come forward on its own, which covers an
        // older plugin; otherwise the notification is the way in.
        if (android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).setAction(BridgeProtocol.ACTION_REVIEW_SERVER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    data class Caller(val packageName: String, val signerSha256: String)

    /** The ATAK package behind [uid], with its signing certificate — or null if it isn't ATAK. */
    private fun callerOf(uid: Int): Caller? {
        if (uid < 0) return null
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull { it in BridgeProtocol.ATAK_PACKAGES } ?: return null
        return signerOf(this, pkg)?.let { Caller(pkg, it) }
    }

    companion object {
        private const val TAG = "TAKBeaconBridge"
        const val APPROVAL_NOTIFICATION = 7

        @Suppress("DEPRECATION")
        fun signerOf(context: Context, pkg: String): String? = runCatching {
            val pm = context.packageManager
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo ?: return null
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            // With rotation, the history's last entry is the current signer.
            val cert = sigs?.lastOrNull() ?: return null
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
