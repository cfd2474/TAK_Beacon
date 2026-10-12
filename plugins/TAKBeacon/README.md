ATAK Plugin — TAK Beacon

_________________________________________________________________
PURPOSE AND CAPABILITIES

  TAK Beacon keeps an operator's position reporting to their TAK server when
  ATAK is not running: on a phone carried as a tracker, after ATAK is closed,
  or on a device that only needs to be seen. It is two parts: the TAK Beacon
  app (a small position reporter, published on Google Play) and this ATAK
  plugin, which installs, configures and controls it.

  With the plugin, an operator can:

  - install or update the TAK Beacon app from inside ATAK (the plugin carries
    the Play-signed app; Android shows its normal install confirmation)
  - send one of ATAK's server connections, with its certificates and the
    operator's callsign, team and role, to TAK Beacon, so both apps report as
    one marker
  - start and stop the beacon, and see whether it is transmitting, waiting for
    GPS, or paused, and how many servers it is connected to
  - have the beacon pause automatically while ATAK is in the foreground
    (ATAK is then the one reporting), and resume when ATAK leaves

  The app itself provides PLI to several servers, certificate enrollment,
  QR/data-package setup, and 911 / Ring the Bell / In Contact alerts.

_________________________________________________________________
STATUS

  Version 1.0.0. Built for ATAK-CIV 5.6.0, 5.7.0 and 5.8.0.

  Verified on the ATAK-CIV 5.8.0.5 SDK developer build (Android 14 emulator):
  plugin loads; pane opens; the embedded app installs through Android's
  installer; the plugin connects to the app; an ATAK TCP server is sent to the
  app, approved there, and the app reports to it with ATAK's callsign and UID;
  start from the plugin opens the app's location-permission flow when needed.

  Prepared for tak.gov third-party submission.

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
      preview is shown
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
    Play signs it (Play App Signing), not built from this tree. It is a
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
