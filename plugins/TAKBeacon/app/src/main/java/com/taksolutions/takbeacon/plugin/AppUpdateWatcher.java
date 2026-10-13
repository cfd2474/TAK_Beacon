package com.taksolutions.takbeacon.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.util.NotificationUtil;
import com.atakmap.coremap.log.Log;

/**
 * Checks that the installed TAK Beacon app is the version this plugin carries:
 * shortly after ATAK starts, and whenever the app is installed, updated or
 * removed. When the installed app is older it posts an ATAK notification
 * pointing at the plugin's Update button; when it is current again, the
 * notification is cleared.
 */
final class AppUpdateWatcher {

    interface Listener {
        void onAppChanged();
    }

    private static final String TAG = "TAKBeaconUpdate";
    private static final long FIRST_CHECK_MS = 15_000L;

    private final Context host;
    private final AppInstaller installer;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private int notifyId = -1;

    AppUpdateWatcher(Context host, AppInstaller installer, Listener listener) {
        this.host = host;
        this.installer = installer;
        this.listener = listener;
    }

    void start() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri data = intent.getData();
                if (data != null && BridgeProtocol.APP_PACKAGE.equals(data.getSchemeSpecificPart()))
                    check();
            }
        };
        final IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        // Protected system broadcasts: only the system can send these.
        if (Build.VERSION.SDK_INT >= 33)
            host.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else
            host.registerReceiver(receiver, f);
        // Let ATAK finish starting before the first check.
        main.postDelayed(firstCheck, FIRST_CHECK_MS);
    }

    void stop() {
        main.removeCallbacks(firstCheck);
        if (receiver != null) {
            try {
                host.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
        clear();
    }

    private final Runnable firstCheck = new Runnable() {
        @Override
        public void run() {
            check();
        }
    };

    /** Re-reads the installed version, updates the notification, and tells the pane. */
    void check() {
        try {
            final AppInstaller.Status st = installer.status();
            if (st == AppInstaller.Status.UPDATE_AVAILABLE)
                notifyUpdate();
            else
                clear();
        } catch (RuntimeException e) {
            Log.w(TAG, "version check failed: " + e.getMessage());
        }
        listener.onAppChanged();
    }

    private void notifyUpdate() {
        final NotificationUtil nu = NotificationUtil.getInstance();
        if (notifyId < 0)
            notifyId = nu.reserveNotifyId();
        final String title = "TAK Beacon update available";
        final String text = "Installed " + installer.installedVersion() + "; this plugin includes "
                + installer.embeddedVersion() + ". Open TAK Beacon from the ATAK menu and tap Update.";
        nu.postNotification(notifyId, android.R.drawable.stat_sys_download,
                NotificationUtil.YELLOW, title, title, text);
    }

    private void clear() {
        if (notifyId >= 0)
            NotificationUtil.getInstance().clearNotification(notifyId);
    }
}
