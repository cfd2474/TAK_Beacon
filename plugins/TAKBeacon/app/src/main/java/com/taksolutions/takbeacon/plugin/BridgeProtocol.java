package com.taksolutions.takbeacon.plugin;

/**
 * The contract with the TAK Beacon app, mirrored from the app's
 * com.taksolutions.takbeacon.bridge.BridgeProtocol. docs/bridge-protocol.md in
 * the TAK Beacon repository is the specification; change both sides together.
 */
final class BridgeProtocol {
    private BridgeProtocol() {
    }

    static final int VERSION = 1;

    static final String APP_PACKAGE = "com.taksolutions.takbeacon";
    static final String ACTION_BIND = "com.taksolutions.takbeacon.action.BRIDGE";
    static final String ACTION_START_TRACKING = "com.taksolutions.takbeacon.action.START_TRACKING";

    static final int MSG_HELLO = 1;
    static final int MSG_QUERY = 2;
    static final int MSG_HEARTBEAT = 3;
    static final int MSG_START = 4;
    static final int MSG_STOP = 5;
    static final int MSG_PUSH_PROFILE = 6;

    static final int MSG_STATE = 100;
    static final int MSG_RESULT = 101;

    static final String KEY_SERVER_NAME = "serverName";
    static final String KEY_HOST = "host";
    static final String KEY_PORT = "port";
    static final String KEY_TRANSPORT = "transport";
    static final String KEY_CLIENT_P12 = "clientP12";
    static final String KEY_CLIENT_PASSWORD = "clientPassword";
    static final String KEY_TRUST_P12 = "trustP12";
    static final String KEY_TRUST_PASSWORD = "trustPassword";
    static final String KEY_CALLSIGN = "callsign";
    static final String KEY_UID = "uid";
    static final String KEY_TEAM = "team";
    static final String KEY_ROLE = "role";

    static final String KEY_APP_VERSION_NAME = "appVersionName";
    static final String KEY_RUN_STATE = "runState";
    static final String KEY_SERVERS = "servers";
    static final String KEY_CONNECTED = "connected";
    static final String KEY_LAST_SENT_MS = "lastSentMs";
    static final String KEY_EMERGENCY = "emergency";
    static final String KEY_TRUSTED = "trusted";
    static final String KEY_SATELLITE = "satellite";
    static final String KEY_MESSAGE = "message";

    /** RESULT text meaning: start me from a foreground Activity instead. */
    static final String NEEDS_FOREGROUND = "needs_foreground";
    static final String AWAITING_APPROVAL = "awaiting_approval";
}
