package com.taksolutions.takbeacon.bridge

/**
 * The contract between the TAK Beacon ATAK plugin and this app. The plugin
 * keeps an identical copy; docs/bridge-protocol.md is the specification.
 * Bump [VERSION] on any incompatible change.
 */
object BridgeProtocol {
    const val VERSION = 1

    const val PACKAGE = "com.taksolutions.takbeacon"
    const val ACTION_BIND = "com.taksolutions.takbeacon.action.BRIDGE"
    /** Activity action that starts tracking from the foreground (background FGS starts are blocked). */
    const val ACTION_START_TRACKING = "com.taksolutions.takbeacon.action.START_TRACKING"

    // Plugin → app (Message.what)
    const val MSG_HELLO = 1
    const val MSG_QUERY = 2
    /** arg1 = 1 while ATAK is in the foreground, 0 when it leaves. Send every 5 s while foreground. */
    const val MSG_HEARTBEAT = 3
    const val MSG_START = 4
    const val MSG_STOP = 5
    /** data = profile bundle, keys below. */
    const val MSG_PUSH_PROFILE = 6
    /** data[KEY_EMERGENCY] = EmergencyType name. */
    const val MSG_EMERGENCY = 7
    const val MSG_CANCEL_EMERGENCY = 8

    // App → plugin (sent to Message.replyTo)
    /** data = state bundle, keys below. */
    const val MSG_STATE = 100
    /** arg1 = 1 ok / 0 failed, arg2 = the request's `what`, data[KEY_MESSAGE] = text. */
    const val MSG_RESULT = 101

    // Profile keys
    const val KEY_PROTOCOL = "protocol"
    const val KEY_SERVER_NAME = "serverName"
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_TRANSPORT = "transport"           // "ssl" | "tcp"
    const val KEY_CLIENT_P12 = "clientP12"          // ByteArray
    const val KEY_CLIENT_PASSWORD = "clientPassword"
    const val KEY_TRUST_P12 = "trustP12"            // ByteArray
    const val KEY_TRUST_PASSWORD = "trustPassword"
    const val KEY_CALLSIGN = "callsign"
    const val KEY_UID = "uid"
    const val KEY_TEAM = "team"                     // CoT team name, e.g. "Cyan"
    const val KEY_ROLE = "role"                     // CoT role, e.g. "Team Member"

    // State keys
    const val KEY_APP_VERSION_CODE = "appVersionCode"
    const val KEY_APP_VERSION_NAME = "appVersionName"
    const val KEY_RUN_STATE = "runState"            // RunState name
    const val KEY_SERVERS = "servers"
    const val KEY_CONNECTED = "connected"
    const val KEY_LAST_SENT_MS = "lastSentMs"
    const val KEY_EMERGENCY = "emergency"
    const val KEY_TRUSTED = "trusted"               // this ATAK is approved for profile pushes
    const val KEY_SATELLITE = "satellite"           // on a constrained satellite network (protocol 1, app 1.1+)
    const val KEY_MESSAGE = "message"

    /** ATAK flavours whose calls are accepted at all. */
    val ATAK_PACKAGES = setOf("com.atakmap.app.civ", "com.atakmap.app.mil", "com.atakmap.app.gov")
}
