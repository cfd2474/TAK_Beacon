# TAK Beacon

A lightweight position beacon for the TAK ecosystem, plus an ATAK plugin that
installs and drives it.

| Part | Path | Status |
|---|---|---|
| **TAK Beacon app** (`com.taksolutions.takbeacon`) | [tracker/](tracker/) | Builds; tested end to end on an emulator |
| **ATAK plugin** (`com.taksolutions.takbeacon.plugin`) | [plugins/TAKBeacon/](plugins/TAKBeacon/) | Builds for ATAK-CIV 5.6.0 / 5.7.0 / 5.8.0; tested on the 5.8.0 SDK build |
| Icons | [branding/](branding/) | `python -I branding/make_icons.py` regenerates every size |

## The app

- **PLI** to any number of TAK servers over TLS (client certificates) or TCP,
  on an interval you choose, with battery, course and speed.
- **Enrollment** via TAK certificate enrollment on port 8446 (TAK Server and
  OpenTAKServer). Supports QR codes (`tak://…/enroll`, `takbeacon://enroll`, and
  `Name,host,port,ssl`), deep links, data packages (`.zip` with `.pref` and
  `.p12`), and manual entry. Server certificates the device doesn't recognise
  are shown by fingerprint for the user to trust once.
- **Emergencies:** 911 / Ring the Bell / In Contact, sent with a hold-to-send
  button or by pressing a volume key 5 times (works with the screen off).
  Cancel is supported.
- **ATAK integration** ([docs/bridge-protocol.md](docs/bridge-protocol.md)): the
  plugin can push ATAK's server, certificates and identity, start and stop
  tracking, and pause the beacon while ATAK is in front.
- Team colour and role, DD/DMS/MGRS, an OpenStreetMap preview with a dark mode,
  kiosk lock, and start on boot.
- No Google Play services dependency: it runs on de-Googled fleet devices.

```bash
cd tracker
./gradlew :app:testDebugUnitTest     # CoT, MGRS, enrollment parsing
./gradlew :app:assembleDebug         # com.taksolutions.takbeacon.debug
./gradlew :app:bundleRelease         # for Play; see docs/play-release.md
```

## Provenance

TAK Beacon is a clean-room implementation. It replicates the *feature set* of
OpenTAK Tracker, but no code, resources or protocol definitions were taken from
that app or its source. Everything here is written against public TAK
conventions: CoT event types, the TAK Server certificate-enrollment API, and the
data package format. Keep it that way; it matters for the tak.gov submission.

## Release order

1. App → Google Play ([docs/play-release.md](docs/play-release.md)).
2. Download the Play-signed universal APK (App bundle explorer, Signed universal
   APK), keep it in `~/atak-dist/takbeacon-app/`, then stage it into the plugin:
   `plugins/TAKBeacon/scripts/stage-app.sh <apk>` (it checks the app is signed by Play's app signing key, `1D:62:9D:…:1C:42`).
3. `plugins/TAKBeacon/scripts/submit.sh` builds one tak.gov zip per ATAK target
   in `~/atak-dist/`, running every atak-plugin-pipeline gate.

Day-to-day plugin builds: `plugins/TAKBeacon/scripts/build.sh <5.6.0|5.7.0|5.8.0|all> [debug]`.

## License

Apache License 2.0 ([LICENSE](LICENSE)). The plugin's files that came from the
ATAK SDK template are listed in [plugins/TAKBeacon/NOTICE.md](plugins/TAKBeacon/NOTICE.md).
