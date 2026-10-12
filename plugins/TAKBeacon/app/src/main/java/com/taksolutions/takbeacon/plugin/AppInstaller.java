package com.taksolutions.takbeacon.plugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Installs and updates the TAK Beacon app from the Play-signed APK carried in
 * this plugin's assets, through Android's PackageInstaller (ATAK holds
 * REQUEST_INSTALL_PACKAGES on 5.6–5.8). Android always asks the user to confirm.
 */
final class AppInstaller {

    private static final String TAG = "TAKBeaconInstaller";
    static final String ASSET = "takbeacon.apk";
    private static final String ACTION_STATUS = "com.taksolutions.takbeacon.plugin.INSTALL_STATUS";

    enum Status {
        /** The plugin carries no app (development build). */
        NO_EMBEDDED_APP,
        NOT_INSTALLED,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        /** The installed app is newer than the one carried here. */
        INSTALLED_NEWER,
        /** Installed from another signer (e.g. a local build); Android won't update across that. */
        SIGNATURE_MISMATCH,
    }

    interface Callback {
        void onInstallFinished(boolean ok, String message);
    }

    private final Context pluginContext;
    private final Context hostContext;
    private PackageInfo embedded;
    private File extracted;
    private BroadcastReceiver receiver;

    AppInstaller(Context pluginContext, Context hostContext) {
        this.pluginContext = pluginContext;
        this.hostContext = hostContext;
    }

    /** The embedded app's versionName, or null. Extracts the APK on first use. */
    String embeddedVersion() {
        final PackageInfo p = embedded();
        return p == null ? null : p.versionName;
    }

    String installedVersion() {
        final PackageInfo p = installed();
        return p == null ? null : p.versionName;
    }

    Status status() {
        final PackageInfo e = embedded();
        final PackageInfo i = installed();
        if (i == null)
            return e == null ? Status.NO_EMBEDDED_APP : Status.NOT_INSTALLED;
        if (e == null)
            return Status.UP_TO_DATE;
        if (!signers(e).equals(signers(i)))
            return Status.SIGNATURE_MISMATCH;
        final long ev = versionCode(e);
        final long iv = versionCode(i);
        if (ev > iv)
            return Status.UPDATE_AVAILABLE;
        return ev == iv ? Status.UP_TO_DATE : Status.INSTALLED_NEWER;
    }

    /** Opens Android's installer for the embedded app; the result arrives on [cb]. */
    void install(final Callback cb) {
        final File apk = extract();
        if (apk == null || embedded() == null) {
            cb.onInstallFinished(false, "This plugin build does not include the TAK Beacon app.");
            return;
        }
        final PackageInstaller installer = hostContext.getPackageManager().getPackageInstaller();
        final PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(BridgeProtocol.APP_PACKAGE);
        params.setSize(apk.length());
        PackageInstaller.Session session = null;
        try {
            final int id = installer.createSession(params);
            session = installer.openSession(id);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            listen(cb);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // The installer fills in the status extras, so the intent must be mutable.
            if (Build.VERSION.SDK_INT >= 31)
                flags |= PendingIntent.FLAG_MUTABLE;
            final PendingIntent pi = PendingIntent.getBroadcast(hostContext, id,
                    new Intent(ACTION_STATUS).setPackage(hostContext.getPackageName()), flags);
            session.commit(pi.getIntentSender());
            session.close();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "install session failed", e);
            if (session != null)
                session.abandon();
            cb.onInstallFinished(false, "Could not start the installer: " + e.getMessage());
        }
    }

    /** Opens Android's uninstall prompt for the app (needed after a signature mismatch). */
    void uninstall() {
        final Intent i = new Intent(Intent.ACTION_DELETE,
                android.net.Uri.parse("package:" + BridgeProtocol.APP_PACKAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        hostContext.startActivity(i);
    }

    void dispose() {
        unlisten();
    }

    private void listen(final Callback cb) {
        unlisten();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android's own confirmation screen.
                    final Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        hostContext.startActivity(confirm);
                    }
                    return;
                }
                unlisten();
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    cb.onInstallFinished(true, "TAK Beacon installed.");
                } else {
                    final String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    cb.onInstallFinished(false, describe(status, msg));
                }
            }
        };
        final IntentFilter filter = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            hostContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            hostContext.registerReceiver(receiver, filter);
        }
    }

    private void unlisten() {
        if (receiver != null) {
            try {
                hostContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
    }

    private static String describe(int status, String msg) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Installation was cancelled.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "Installation was blocked by the device policy.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "A different TAK Beacon build is installed. Uninstall it, then install again.";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "TAK Beacon is not compatible with this device.";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough storage to install TAK Beacon.";
            default:
                return "Installation failed" + (msg != null ? ": " + msg : ".");
        }
    }

    private PackageInfo embedded() {
        if (embedded != null)
            return embedded;
        final File apk = extract();
        if (apk == null)
            return null;
        final PackageInfo p = hostContext.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), signingFlag());
        if (p == null || !BridgeProtocol.APP_PACKAGE.equals(p.packageName)) {
            Log.w(TAG, "embedded APK is not " + BridgeProtocol.APP_PACKAGE);
            return null;
        }
        embedded = p;
        return p;
    }

    private PackageInfo installed() {
        try {
            return hostContext.getPackageManager().getPackageInfo(BridgeProtocol.APP_PACKAGE, signingFlag());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Copies the APK out of the plugin's assets into ATAK's cache, once per plugin load. */
    private File extract() {
        if (extracted != null && extracted.isFile())
            return extracted;
        final File dir = new File(hostContext.getCacheDir(), "takbeacon");
        if (!dir.isDirectory() && !dir.mkdirs())
            return null;
        final File out = new File(dir, ASSET);
        try (InputStream in = pluginContext.getAssets().open(ASSET);
             OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        } catch (IOException e) {
            // Development builds carry no app.
            Log.i(TAG, "no embedded app: " + e.getMessage());
            return null;
        }
        extracted = out;
        return out;
    }

    @SuppressWarnings("deprecation")
    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            sigs = p.signingInfo.hasMultipleSigners()
                    ? p.signingInfo.getApkContentsSigners()
                    : p.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = p.signatures;
        }
        final Set<String> out = new HashSet<>();
        if (sigs == null)
            return out;
        for (Signature s : sigs) {
            try {
                final byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
                final StringBuilder sb = new StringBuilder();
                for (byte b : d)
                    sb.append(String.format("%02x", b));
                out.add(sb.toString());
            } catch (java.security.NoSuchAlgorithmException ignored) {
            }
        }
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0)
            out.write(buf, 0, n);
    }
}
