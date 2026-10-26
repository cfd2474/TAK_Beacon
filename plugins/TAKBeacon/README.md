ATAK Plugin — TAK Beacon

**Download TAK Beacon 1.4.6** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.6/ATAK-Plugin-TAKBeacon-1.4.6--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.6/ATAK-Plugin-TAKBeacon-1.4.6--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.6/ATAK-Plugin-TAKBeacon-1.4.6--5.8.0-civ-release.apk

**Standalone app, no ATAK:** `TAKBeacon-app-1.4.6.apk` on the [v1.4.6 release page](https://github.com/cfd2474/TAK_Beacon/releases/tag/v1.4.6) (the same Play-signed app the plugin installs; Android 8+)

All releases: https://github.com/cfd2474/TAK_Beacon/releases

**User guide: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/cfd2474/TAK_Beacon/blob/main/plugins/TAKBeacon/docs/USER_GUIDE.md)

**User manual with screenshots (PDF):** https://github.com/cfd2474/TAK_Beacon/blob/main/docs/TAK_Beacon_User_Manual.pdf
(the same manual is in ATAK: Settings, Tool Preferences, Specific Tool Preferences, TAK Beacon, Plugin documentation)

_________________________________________________________________
PURPOSE AND CAPABILITIES

  TAK Beacon keeps an operator's position reporting to their TAK server when
  ATAK is not running: on a phone carried as a tracker, after ATAK is closed,
  or on a device that only needs to be seen. It is two parts: the TAK Beacon
  app (a small position reporter, published on Google Play) and this ATAK
  plugin, which installs, configures and controls it.

  With the plugin, an operator can:

  - install or update the TAK Beacon app from inside ATAK (the plugin carries
    the Play-signed app; Android shows its normal install confirmation). The
    plugin checks the installed version against the one it carries, posts an
    ATAK notification when the app is out of date, and turns the beacon back
    on after the update if it was on
  - send one of ATAK's server connections, with its certificates and the
    operator's callsign, team and role, to TAK Beacon, so both apps report as
    one marker
  - start and stop the beacon, and see whether it is transmitting, waiting for
    GPS, or paused, and how many servers it is connected to
  - have the beacon pause automatically while ATAK is running
    (ATAK is then the one reporting), and resume when ATAK leaves

  The app itself provides PLI to several servers, certificate enrollment,
  QR/data-package setup, and 911 / Ring the Bell / In Contact alerts.

_________________________________________________________________
STATUS

  Version 1.4.6, for ATAK-CIV 5.6.0, 5.7.0 and 5.8.0. From 1.3.1 the plugin
  and the TAK Beacon app it carries share one version number. The plugin
  checks that the installed app matches, raises an ATAK notification when it
  is out of date and updates it from the pane; Start beacon starts the app
  first when it isn't running; the beacon pauses for as long as ATAK runs.
  The app accepts an MDM managed configuration (server and identity), and
  the manual (with screenshots) opens from Tool Preferences, Plugin
  documentation.

  1.0.0 through 1.3.5, 1.4.1, 1.4.5 and 1.4.6 were signed by tak.gov for all three versions. 1.4.0
  and 1.4.2 to 1.4.4 were not released.

_________________________________________________________________
POINT OF CONTACTS

  TAK-Solutions, LLC
https://github.com/cfd2474/TAK_Beacon/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  The plugin opens no network connections and no listening sockets.

  It talks to the TAK Beacon app on the device only, over Android IPC (a
  bound service), and installs the app through Android's PackageInstaller.

  The TAK Beacon app (a separate APK, carried in this plugin's assets)
  connects outbound only, to the TAK servers the user configures:
    - streaming CoT: TCP 8089 (TLS) or 8087 (plain TCP), or the port configured
    - certificate enrollment: TCP 8446 (HTTPS), only while signing in
    - map tiles: HTTPS 443 to tile.openstreetmap.org, only while its map
      preview is shown (never over a satellite network)
    - UDP broadcast, off by default: CoT datagrams to 239.2.3.1:6969 (ATAK SA
      multicast, TTL 1) or a user-set host:port, unencrypted
  Over a constrained satellite network (T-Mobile T-Satellite) it keeps
  reporting at a reduced rate; this can be turned off in its settings.
  It has no inbound ports and no listening sockets.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8, with location
  services. About 20 MB of storage for the TAK Beacon app.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Copy template.local.properties to
  local.properties and set sdk.dir, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target;
  pass -Psdk.path and -Ptakdev.plugin for that release's SDK
  (scripts/build.sh <x.y.z> does both). versionCode is derived from
  PLUGIN_VERSION and ATAK_VERSION, so it does not depend on git.

_________________________________________________________________
DEVELOPER NOTES

  - app/src/main/assets/takbeacon.apk is the TAK Beacon app exactly as Google
    Play signs it (Play App Signing), not built from this tree. It is staged
    into the submission by scripts/stage-app.sh, which checks the package and
    the Play app signing certificate, and is not kept in the source repo. It is a
    separate application (package com.taksolutions.takbeacon) with its own
    signing certificate, distinct from this plugin's. The plugin never loads
    or executes it: AppInstaller copies it to ATAK's cache and hands it to
    Android's PackageInstaller, which asks the user to confirm. Its source is
    published under Apache-2.0 alongside this plugin.
  - Plugin to app: a Messenger bound service in the app
    (com.taksolutions.takbeacon.action.BRIDGE). The app serves only callers
    whose UID belongs to an ATAK package, and applies a server profile only
    from the ATAK signing certificate the user approved on first use.
  - Server profiles are read with ATAK's own lookup order:
    AtakCertificateDatabase per server and port, then per server, then the
    default; matching credentials from AtakAuthenticationDatabase.
  - ATAK's foreground state comes from ActivityLifecycleCallbacks on ATAK's
    Application; the heartbeat runs for the plugin's lifetime, not in the pane.
  - Dialogs use the MapView context, resources the plugin context; no Spinner.
  - ATAK targets SDK 35 without QUERY_ALL_PACKAGES, so it can only see apps
    that match its manifest queries; the TAK Beacon app declares a VIEW
    intent filter (for data packages), which is what makes it visible.
  - The user manual is reached from Settings, Tool Preferences, Specific
    Tool Preferences, TAK Beacon.
