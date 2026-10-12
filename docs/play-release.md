# Releasing the TAK Beacon app through Google Play

The app has to be on Play **before** the ATAK plugin is submitted to tak.gov,
because the plugin embeds the Play-signed APK. Order:

1. Release the app on Play (at least internal testing).
2. Download the Play-signed universal APK.
3. Put it in the plugin's assets and submit the plugin.

## Why Play signing stops Play Protect warnings

Play Protect warns about apps it has never seen, signed by keys it doesn't
know. The OpenTAK Tracker plugin installed an APK that wasn't on Play. Once
TAK Beacon is on Play under your developer account:

- The APK the plugin embeds is signed with Play's **app signing key**, which
  Play Protect recognises.
- Your developer identity is verified. Google's developer-verification rollout
  requires this for apps installed on certified Android devices, including
  apps sideloaded by another app such as ATAK.

The plugin must embed **the Play-signed APK, byte for byte**. If you rebuild it
locally, it is signed by your upload key and the warnings come back. Android
will also refuse to update across the two signatures.

## One-time setup

1. **Confirm the application ID.** `tracker/app/build.gradle.kts` uses
   `com.taksolutions.takbeacon`. Play never lets you change it after the first
   upload.
2. **Create an upload key** and keep it out of the repo (it's gitignored):

   ```bash
   keytool -genkeypair -v -keystore ~/.config/atak-plugins/takbeacon-upload.jks \
     -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   Then create `tracker/keystore.properties`:

   ```properties
   storeFile=C:/Users/<you>/.config/atak-plugins/takbeacon-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```

3. In Play Console, create the app and enroll in **Play App Signing** (the
   default). Google generates and holds the app signing key.

## Each release

1. Bump `beaconVersion` in `tracker/app/build.gradle.kts`. `versionCode` is
   derived from it (MAJOR·1,000,000 + MINOR·1,000 + PATCH), so every release is
   an update Play and MDMs accept.
2. Build the bundle:

   ```bash
   cd tracker && ./gradlew :app:bundleRelease
   # → app/build/outputs/bundle/release/app-release.aab
   ```

3. Upload it to a track (internal testing first) and complete review.
4. **Download the Play-signed universal APK:** Play Console → *Test and
   release* → *App bundle explorer* → pick the version → *Downloads* →
   **Signed, universal APK**. Save it as
   `~/atak-dist/takbeacon-app/TAKBeacon-<version>-play-signed.apk`.
5. Verify the signer is Google's app signing key, not your upload key:

   ```bash
   apksigner verify --print-certs TAKBeacon-<version>-play-signed.apk
   ```

   The SHA-256 must match *App signing key certificate* under
   Play Console → *Test and release* → *App integrity*.

## Play Console declarations this app needs

| Item | What to declare |
|---|---|
| Foreground service: location | Reports the user's position to their team's TAK server while the beacon is on. A short video showing Start → notification → position on a TAK map. |
| Background location (`ACCESS_BACKGROUND_LOCATION`) | Only for "Start on boot": resumes position reporting after a restart for dedicated tracking devices. Requested only when the user turns that on, after an in-app disclosure. |
| Data safety | Precise location: collected, **shared** with servers the user configures (not with you), not used for ads, required for the app's core function. Device IDs: a random UID sent to the user's TAK server. |
| Camera | QR code scanning for server enrollment, on-device only. |
| Prominent disclosure | Shown before every location request (`MainActivity` → "Share your location"). |

If you'd rather not justify background location, delete the
`ACCESS_BACKGROUND_LOCATION` line from the manifest and remove "Start on boot".
Nothing else depends on it.
