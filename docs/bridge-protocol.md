# TAK Beacon bridge protocol (v1)

How the TAK Beacon ATAK plugin talks to the TAK Beacon app. The app side is
`tracker/app/src/main/java/com/taksolutions/takbeacon/bridge/`; the plugin keeps a
copy of `BridgeProtocol.kt`'s constants.

## Transport

The plugin binds a `Messenger` service:

```java
Intent i = new Intent("com.taksolutions.takbeacon.action.BRIDGE")
        .setPackage("com.taksolutions.takbeacon");
mapView.getContext().bindService(i, conn, Context.BIND_AUTO_CREATE);
```

Every request carries `replyTo` so the app can answer. Bind with the **host
(MapView) context**, not the plugin context.

## Who may call

The app reads `Message.sendingUid` and serves only these packages:
`com.atakmap.app.civ`, `com.atakmap.app.mil`, `com.atakmap.app.gov`.
Other callers get `MSG_RESULT` with `ok = 0` and nothing else happens.

A server profile carries certificates, so it gets a second check. The app
records the SHA-256 of the calling ATAK's signing certificate. The first push
from a signer it doesn't know waits for the user to approve it in the app (a
notification opens the approval dialog). After approval, pushes from that
signer apply straight away. The user can revoke this in Settings → ATAK.

## Messages (plugin → app)

| what | name | payload | reply |
|---|---|---|---|
| 1 | HELLO | — | STATE |
| 2 | QUERY | — | STATE |
| 3 | HEARTBEAT | `arg1` = 1 foreground / 0 leaving | none |
| 4 | START | — | RESULT (`needs_foreground` → see below) |
| 5 | STOP | — | RESULT |
| 6 | PUSH_PROFILE | profile bundle | RESULT (`awaiting_approval` if not yet trusted) |
| 7 | EMERGENCY | `emergency` = `ALERT_911` \| `RING_THE_BELL` \| `IN_CONTACT` \| `GEO_FENCE` | RESULT |
| 8 | CANCEL_EMERGENCY | — | RESULT |

**Heartbeat.** While ATAK is running (in front or not), send `HEARTBEAT arg1=1`
every 5 seconds, and `arg1=0` when ATAK shuts down or the plugin unloads. If the
user has turned on "Pause while ATAK is running", the app (1.3.2+) pauses PLI from
the first `arg1=1` until an `arg1=0` or until the bridge loses its binding (ATAK's
process died). There is no timeout: a sleeping phone delays heartbeats without
ATAK having gone. An active emergency is never paused. Plugins before 1.3.2 sent
`arg1=1` only while ATAK was in front and `arg1=0` on leaving, which still means
"pause only while in front".

**Starting from the background.** Android 12+ blocks most foreground-service
starts from the background. When START returns `needs_foreground`, start an
activity from ATAK's context instead:

```java
Intent i = new Intent("com.taksolutions.takbeacon.action.START_TRACKING")
        .setPackage("com.taksolutions.takbeacon")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
mapView.getContext().startActivity(i);
```

The app starts tracking, then moves itself to the back so the user is returned
to ATAK. If location permission hasn't been granted yet, the app stays in front
and asks for it.

## Profile bundle

| key | type | notes |
|---|---|---|
| `serverName` | String | display name |
| `host` | String | required |
| `port` | int | default 8089 |
| `transport` | String | `ssl` (default) or `tcp` |
| `clientP12` | byte[] | ≤ 64 KB, PKCS#12 with private key |
| `clientPassword` | String | |
| `trustP12` | byte[] | ≤ 64 KB, PKCS#12 trust store |
| `trustPassword` | String | |
| `callsign`, `uid`, `team`, `role` | String | optional; adopted so the operator appears as **one** marker whichever app reports |

A profile for a host:port the app already has replaces that server's
certificates; otherwise it adds a new server.

## Messages (app → plugin)

`STATE` (100) — bundle: `protocol`, `appVersionCode` (long), `appVersionName`,
`runState` (`STOPPED` `WAITING_FOR_FIX` `TRANSMITTING` `PAUSED_FOR_ATAK`
`PAUSED_SATELLITE` `NO_SERVERS`), `servers`, `connected`, `lastSentMs`,
`emergency`, `trusted`, `satellite` (boolean; app 1.1+, absent before),
`locked` (boolean; app 1.2+). While locked the app answers START, STOP and
PUSH_PROFILE with RESULT `ok = 0`, `message = "locked"`, with one exception
(app 1.3+): START is accepted while locked when the beacon was on before the
app was last stopped, so a plugin can restore it after updating the app.
A plugin must treat an unknown `runState` as "not transmitting" rather than fail.

`RESULT` (101) — `arg1` = 1 ok / 0 failed, `arg2` = the request's `what`,
`message` = text.

The plugin uses `appVersionCode` to decide whether its embedded APK is newer
than the installed app.
