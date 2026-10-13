package com.taksolutions.takbeacon.plugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.atakmap.coremap.log.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client side of the TAK Beacon bridge: binds the app's Messenger service,
 * keeps a copy of its state, and sends the heartbeat that tells it ATAK is running.
 *
 * <p>Lives for the plugin's lifetime (not inside a Tool or the pane), so the
 * heartbeat keeps running while the pane is closed. Everything runs on the
 * main thread.
 */
final class BeaconBridge {

    private static final String TAG = "TAKBeaconBridge";
    private static final long TICK_MS = 5_000L;
    private static final long REBIND_MS = 15_000L;

    /** The app's last reported state. Fields are null/0 until the first STATE. */
    static final class State {
        boolean bound;
        String appVersion;
        String runState;
        int servers;
        int connected;
        long lastSentMs;
        String emergency;
        boolean trusted;
        boolean satellite;
        boolean locked;
    }

    interface Listener {
        void onStateChanged(State state);

        void onResult(int request, boolean ok, String message);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final State state = new State();

    private Messenger service;
    private boolean binding;
    private boolean running;

    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            onReply(msg);
        }
    });

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            binding = false;
            state.bound = true;
            send(BridgeProtocol.MSG_HELLO, 0, null);
            sendHeartbeat();
            notifyState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The app died or was updated; Android rebinds by itself.
            service = null;
            state.bound = false;
            notifyState();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
            scheduleRebind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "TAK Beacon refused the bind (older app without the bridge?)");
            unbind();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running)
                return;
            sendHeartbeat();
            send(BridgeProtocol.MSG_QUERY, 0, null);
            main.postDelayed(this, TICK_MS);
        }
    };

    BeaconBridge(Context appContext) {
        this.appContext = appContext;
    }

    void start() {
        running = true;
        bind();
        main.postDelayed(tick, TICK_MS);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
        // ATAK is shutting down (or the plugin unloading): the app resumes reporting now.
        send(BridgeProtocol.MSG_HEARTBEAT, 0, null);
        unbind();
    }

    /** Re-binds now: after an install, or when the pane opens. */
    void refresh() {
        if (service == null) {
            unbind();
            bind();
        } else {
            send(BridgeProtocol.MSG_QUERY, 0, null);
        }
    }

    State state() {
        return state;
    }

    boolean isBound() {
        return service != null;
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void startTracking() {
        send(BridgeProtocol.MSG_START, 0, null);
    }

    void stopTracking() {
        send(BridgeProtocol.MSG_STOP, 0, null);
    }

    void pushProfile(Bundle profile) {
        send(BridgeProtocol.MSG_PUSH_PROFILE, 0, profile);
    }

    private void bind() {
        if (!running)
            return;
        final Intent i = new Intent(BridgeProtocol.ACTION_BIND).setPackage(BridgeProtocol.APP_PACKAGE);
        try {
            binding = appContext.bindService(i, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(TAG, "bind refused: " + e.getMessage());
            binding = false;
        }
        if (!binding) {
            // Not installed (yet). Forget the attempt so the next tick retries.
            try {
                appContext.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            scheduleRebind();
        }
    }

    private void unbind() {
        if (service != null || binding) {
            try {
                appContext.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        service = null;
        binding = false;
        state.bound = false;
    }

    private void scheduleRebind() {
        main.removeCallbacks(rebind);
        if (running)
            main.postDelayed(rebind, REBIND_MS);
    }

    private final Runnable rebind = new Runnable() {
        @Override
        public void run() {
            if (running && service == null && !binding)
                bind();
        }
    };

    /**
     * "ATAK is running", every tick whether ATAK is in front or not: TAK Beacon pauses
     * for as long as ATAK runs, not only while it is on screen.
     */
    private void sendHeartbeat() {
        send(BridgeProtocol.MSG_HEARTBEAT, 1, null);
    }

    private void send(int what, int arg1, Bundle data) {
        final Messenger s = service;
        if (s == null)
            return;
        final Message m = Message.obtain(null, what, arg1, 0);
        m.replyTo = replies;
        if (data != null)
            m.setData(data);
        try {
            s.send(m);
        } catch (RemoteException e) {
            service = null;
            state.bound = false;
            notifyState();
        }
    }

    private void onReply(Message msg) {
        final Bundle b = msg.getData();
        switch (msg.what) {
            case BridgeProtocol.MSG_STATE:
                state.appVersion = b.getString(BridgeProtocol.KEY_APP_VERSION_NAME);
                state.runState = b.getString(BridgeProtocol.KEY_RUN_STATE);
                state.servers = b.getInt(BridgeProtocol.KEY_SERVERS);
                state.connected = b.getInt(BridgeProtocol.KEY_CONNECTED);
                state.lastSentMs = b.getLong(BridgeProtocol.KEY_LAST_SENT_MS, 0L);
                state.emergency = b.getString(BridgeProtocol.KEY_EMERGENCY);
                state.trusted = b.getBoolean(BridgeProtocol.KEY_TRUSTED);
                // Absent from apps before 1.1: reads as false.
                state.satellite = b.getBoolean(BridgeProtocol.KEY_SATELLITE);
                state.locked = b.getBoolean(BridgeProtocol.KEY_LOCKED);
                notifyState();
                break;
            case BridgeProtocol.MSG_RESULT:
                final String text = b.getString(BridgeProtocol.KEY_MESSAGE, "");
                for (Listener l : listeners)
                    l.onResult(msg.arg2, msg.arg1 == 1, text);
                // Any command may have changed the state; ask now rather than in 5 s.
                send(BridgeProtocol.MSG_QUERY, 0, null);
                break;
            default:
                break;
        }
    }

    private void notifyState() {
        for (Listener l : listeners)
            l.onStateChanged(state);
    }
}
