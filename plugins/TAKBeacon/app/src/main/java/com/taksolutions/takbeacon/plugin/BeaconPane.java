package com.taksolutions.takbeacon.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * The plugin's pane: app install/update, beacon start/stop, and sending one of
 * ATAK's servers to TAK Beacon.
 *
 * <p>Windows (dialogs) are built on the MapView context; resources come from the
 * plugin context. A dialog on the plugin context throws BadTokenException in
 * ATAK's process.
 */
final class BeaconPane implements BeaconBridge.Listener {

    private static final String TAG = "TAKBeaconPane";
    /** How long to wait for the app to come up and connect before opening it instead. */
    private static final long START_WAIT_MS = 5_000L;

    private final Context pluginContext;
    private final MapView mapView;
    private final BeaconBridge bridge;
    private final AppInstaller installer;
    private final View root;

    private final TextView beaconState;
    private final TextView appStatus;
    private final TextView beaconDetail;
    private final TextView message;
    private final Button install;
    private final Button open;
    private final Button start;
    private final Button stop;
    private final Button share;
    private boolean restoreAfterUpdate;
    /** Start was tapped while the app wasn't running: send START once it connects. */
    private boolean startWhenConnected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable startFallback = new Runnable() {
        @Override
        public void run() {
            if (!startWhenConnected)
                return;
            startWhenConnected = false;
            // The app didn't come up in the background: open it, it starts the beacon itself.
            say("Opening TAK Beacon to start the beacon\u2026");
            openApp(BridgeProtocol.ACTION_START_TRACKING);
        }
    };

    BeaconPane(Context pluginContext, MapView mapView, BeaconBridge bridge, AppInstaller installer) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.bridge = bridge;
        this.installer = installer;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.beacon_pane, null);
        beaconState = root.findViewById(R.id.beacon_state);
        appStatus = root.findViewById(R.id.app_status);
        beaconDetail = root.findViewById(R.id.beacon_detail);
        message = root.findViewById(R.id.message);
        install = root.findViewById(R.id.btn_install);
        open = root.findViewById(R.id.btn_open);
        start = root.findViewById(R.id.btn_start);
        stop = root.findViewById(R.id.btn_stop);
        share = root.findViewById(R.id.btn_share);

        install.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onInstallClicked();
            }
        });
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openApp(null);
            }
        });
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartClicked();
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bridge.stopTracking();
            }
        });
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickServer();
            }
        });
        bridge.addListener(this);
    }

    View view() {
        return root;
    }

    /** Re-reads the app's install state (after it is installed, updated or removed). */
    void refresh() {
        bridge.refresh();
        render();
    }

    void onShown() {
        bridge.refresh();
        render();
    }

    void dispose() {
        main.removeCallbacks(startFallback);
        bridge.removeListener(this);
    }

    @Override
    public void onStateChanged(BeaconBridge.State state) {
        // Start was tapped before the app was running: it is up now.
        if (startWhenConnected && state.bound && state.runState != null) {
            startWhenConnected = false;
            main.removeCallbacks(startFallback);
            if (state.locked) {
                say("TAK Beacon is locked. Unlock it in the TAK Beacon app with its PIN first.");
            } else if ("STOPPED".equals(state.runState)) {
                say("Starting\u2026");
                bridge.startTracking();
            } else {
                say("The beacon is already on.");
            }
        }
        // After an update the app was killed: bring the beacon back if it was on.
        if (restoreAfterUpdate && state.bound && state.runState != null) {
            restoreAfterUpdate = false;
            if ("STOPPED".equals(state.runState)) {
                say("Restarting the beacon after the update\u2026");
                bridge.startTracking();
            }
        }
        render();
    }

    @Override
    public void onResult(int request, boolean ok, String text) {
        if (request == BridgeProtocol.MSG_START && BridgeProtocol.NEEDS_FOREGROUND.equals(text)) {
            // Android 12+ won't let the app start its location service from the
            // background; an Activity started from ATAK (in front) can.
            openApp(BridgeProtocol.ACTION_START_TRACKING);
            say("Starting from the TAK Beacon app…");
            return;
        }
        if (BridgeProtocol.LOCKED.equals(text)) {
            say("TAK Beacon is locked. Unlock it in the TAK Beacon app with its PIN first.");
            return;
        }
        if (request == BridgeProtocol.MSG_PUSH_PROFILE && BridgeProtocol.AWAITING_APPROVAL.equals(text)) {
            say("Approve the server in TAK Beacon (tap its notification). This is asked once per ATAK install.");
            return;
        }
        say(text);
    }

    private void render() {
        final AppInstaller.Status st = installer.status();
        final BeaconBridge.State s = bridge.state();
        final boolean installed = st != AppInstaller.Status.NOT_INSTALLED && st != AppInstaller.Status.NO_EMBEDDED_APP;

        switch (st) {
            case NO_EMBEDDED_APP:
                appStatus.setText("Not installed. This build of the plugin does not include the app; install TAK Beacon from Google Play.");
                break;
            case NOT_INSTALLED:
                appStatus.setText("Not installed. Version " + installer.embeddedVersion() + " is included with this plugin.");
                break;
            case UPDATE_AVAILABLE:
                appStatus.setText("Installed " + installer.installedVersion() + ". Update to " + installer.embeddedVersion() + " available.");
                break;
            case SIGNATURE_MISMATCH:
                appStatus.setText("Installed " + installer.installedVersion() + " is a different build (another signer) and cannot be updated in place. Uninstall it, then install.");
                break;
            default:
                appStatus.setText("Installed " + installer.installedVersion() + (s.bound ? ", connected." : ", connecting…"));
                break;
        }
        install.setVisibility(st == AppInstaller.Status.NOT_INSTALLED || st == AppInstaller.Status.UPDATE_AVAILABLE
                || st == AppInstaller.Status.SIGNATURE_MISMATCH ? View.VISIBLE : View.GONE);
        install.setText(st == AppInstaller.Status.UPDATE_AVAILABLE ? pluginContext.getString(R.string.update)
                : st == AppInstaller.Status.SIGNATURE_MISMATCH ? pluginContext.getString(R.string.uninstall)
                : pluginContext.getString(R.string.install));
        open.setVisibility(installed ? View.VISIBLE : View.GONE);

        final String run = s.bound ? s.runState : null;
        final boolean running = run != null && !"STOPPED".equals(run);
        // Installed is enough: an app that isn't running is started first (onStartClicked).
        start.setEnabled(installed && !running && !(s.bound && s.locked) && !startWhenConnected);
        stop.setEnabled(s.bound && running && !s.locked);
        share.setEnabled(s.bound && !s.locked);

        if (!installed) {
            setState("Not installed", R.color.state_off);
            beaconDetail.setText("Install the app to start reporting.");
        } else if (!s.bound) {
            setState(startWhenConnected ? "Starting TAK Beacon" : "Connecting to app", R.color.state_waiting);
            beaconDetail.setText(startWhenConnected ? "" : "TAK Beacon isn't running. Start beacon starts it.");
        } else {
            if (s.emergency != null) setState("EMERGENCY ACTIVE", R.color.state_alert);
            else if ("TRANSMITTING".equals(run)) setState("Transmitting", R.color.state_live);
            else if ("PAUSED_FOR_ATAK".equals(run)) setState("Paused while ATAK is running", R.color.state_paused);
            else if ("PAUSED_SATELLITE".equals(run)) setState("Paused on satellite", R.color.state_paused);
            else if ("WAITING_FOR_FIX".equals(run)) setState("Waiting for GPS", R.color.state_waiting);
            else if ("NO_SERVERS".equals(run)) setState("No servers", R.color.state_waiting);
            else setState("Off", R.color.state_off);

            final StringBuilder d = new StringBuilder();
            if (s.locked)
                d.append("Locked in the TAK Beacon app: on/off and servers can't be changed until it is unlocked with its PIN.\n");
            if (s.satellite)
                d.append("Via satellite: reports are slower to save data.\n");
            if ("PAUSED_SATELLITE".equals(run))
                d.append("Satellite data is off in TAK Beacon's Settings.\n");
            d.append(s.connected).append(" of ").append(s.servers).append(" server").append(s.servers == 1 ? "" : "s").append(" connected");
            if (s.lastSentMs > 0) {
                final long ago = Math.max(0, (System.currentTimeMillis() - s.lastSentMs) / 1000);
                d.append(" · last report ").append(ago).append(" s ago");
            }
            if (s.servers == 0 && !s.locked)
                d.append("\nNo servers yet: send one of ATAK's below.");
            beaconDetail.setText(d.toString());
        }
    }

    private void setState(String text, int colorRes) {
        beaconState.setText(text);
        beaconState.setTextColor(pluginContext.getResources().getColor(colorRes));
    }

    private void say(String text) {
        message.setText(text == null ? "" : text);
    }

    /**
     * Checks before sending START: the app must be installed (checked now, not from
     * the last render), and running; if it isn't running it is started first.
     */
    private void onStartClicked() {
        final AppInstaller.Status st = installer.status();
        if (st == AppInstaller.Status.NOT_INSTALLED || st == AppInstaller.Status.NO_EMBEDDED_APP) {
            say("TAK Beacon is not installed. Install it first.");
            render();
            return;
        }
        if (bridge.isBound() && bridge.state().runState != null) {
            say("Starting\u2026");
            bridge.startTracking();
            return;
        }
        // Not running: binding starts the app's process; START goes out once it connects.
        startWhenConnected = true;
        say("Starting TAK Beacon\u2026");
        bridge.refresh();
        main.removeCallbacks(startFallback);
        main.postDelayed(startFallback, START_WAIT_MS);
        render();
    }

    private void onInstallClicked() {
        if (installer.status() == AppInstaller.Status.SIGNATURE_MISMATCH) {
            installer.uninstall();
            return;
        }
        final BeaconBridge.State before = bridge.state();
        final boolean wasRunning = before.bound && before.runState != null && !"STOPPED".equals(before.runState);
        say("Opening Android's installer…");
        installer.install(new AppInstaller.Callback() {
            @Override
            public void onInstallFinished(boolean ok, String text) {
                say(text);
                if (ok) {
                    restoreAfterUpdate = wasRunning;
                    bridge.refresh();
                }
                render();
            }
        });
    }

    private void openApp(String action) {
        final Context host = mapView.getContext();
        Intent i;
        if (action != null) {
            i = new Intent(action).setPackage(BridgeProtocol.APP_PACKAGE);
        } else {
            i = host.getPackageManager().getLaunchIntentForPackage(BridgeProtocol.APP_PACKAGE);
            if (i == null) {
                say("TAK Beacon is not installed.");
                return;
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            host.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open TAK Beacon: " + e.getMessage());
            say("Could not open TAK Beacon.");
        }
    }

    /** A plain AlertDialog list on the host context: never a Spinner. */
    private void pickServer() {
        final List<AtakServers.Server> servers = AtakServers.list();
        if (servers.isEmpty()) {
            say("ATAK has no server connections to send.");
            return;
        }
        final String[] labels = new String[servers.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = servers.get(i).label();
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(pluginContext.getString(R.string.share_server))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmShare(servers.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmShare(final AtakServers.Server server) {
        final Bundle profile;
        try {
            profile = AtakServers.profile(server, mapView);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not read ATAK's connection", e);
            say("Could not read ATAK's connection to " + server.host + ".");
            return;
        }
        final boolean tls = !"tcp".equalsIgnoreCase(server.proto);
        final String body = tls && !AtakServers.hasClientCert(profile)
                ? "ATAK has no client certificate for " + server.host + ". TAK Beacon will add the server and ask you to sign in for its own certificate."
                : "TAK Beacon will get a copy of ATAK's connection to " + server.host + ":" + server.port
                + (tls ? ", including its certificates," : "")
                + " and report as " + mapView.getDeviceCallsign() + ".";
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Send to TAK Beacon?")
                .setMessage(body)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        bridge.pushProfile(profile);
                        say("Sent " + server.host + " to TAK Beacon.");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
