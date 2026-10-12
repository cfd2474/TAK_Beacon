# TAK Beacon — User Guide

**Version 1.0.0** · ATAK plugin and the TAK Beacon app

**Download TAK Beacon 1.0.0** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.0.0/ATAK-Plugin-TAKBeacon-1.0.0--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.0.0/ATAK-Plugin-TAKBeacon-1.0.0--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.0.0/ATAK-Plugin-TAKBeacon-1.0.0--5.8.0-civ-release.apk

All releases: https://github.com/cfd2474/TAK_Beacon/releases

## Before you start

- Published builds exist for **ATAK-CIV 5.6, 5.7 and 5.8**. Install the one
  that matches your ATAK version (ATAK shows it under *Settings → About ATAK*).
  A build for another version installs but ATAK will not load it.
- The plugins are signed by the TAK Product Center and load in official ATAK
  from tak.gov or Google Play. They do not load in the ATAK SDK developer build.
- You need location services on, and a TAK server connection set up in ATAK
  (or the enrollment details from your server administrator).

## What TAK Beacon is

TAK Beacon keeps reporting your position to your TAK server when ATAK is not
running. It has two parts:

- **The TAK Beacon app** — a small position reporter that runs in the
  background with its own notification.
- **This ATAK plugin** — installs and updates the app, starts and stops it, and
  gives it ATAK's server connection so it reports to the same server under your
  callsign.

Open the plugin from the ATAK menu: **TAK Beacon**.

## 1. Install the TAK Beacon app

1. Open **TAK Beacon** from the ATAK menu. The pane shows whether the app is
   installed and which version this plugin carries.
2. Tap **Install TAK Beacon** (or **Update TAK Beacon** when a newer version is
   included).
3. The first time, Android asks to allow ATAK to **install unknown apps**. Tap
   **Settings**, turn on **Allow from this source**, then go back. This is asked
   once.
4. Android shows its install screen. Tap **Install**.

The pane then reads *Installed, connected*. If it says the installed app is a
*different build*, it was installed from another source: tap **Uninstall other
build**, then install again.

## 2. Give TAK Beacon your server

1. In the pane, tap **Send a server to TAK Beacon** and pick one of ATAK's
   connections.
2. Confirm. TAK Beacon receives the server, ATAK's certificates for it, and your
   callsign, team and role, so you appear as **one** marker whichever app is
   reporting.
3. The first time, TAK Beacon asks you to approve: tap its notification, check
   the request, and tap **Approve**. Approving trusts this ATAK install for later
   updates; you can revoke it in TAK Beacon under **Settings → ATAK**.

If ATAK has no client certificate for that server, TAK Beacon adds it and asks
you to sign in to get its own.

## 3. Start and stop reporting

- **Start beacon** turns reporting on. The first time, TAK Beacon opens to
  explain how it uses your location and to ask for permission, then returns you
  to ATAK.
- While reporting, Android shows a **TAK Beacon** notification. **Stop beacon**
  in the pane, or **Stop** in the notification, turns it off.
- The pane shows the state (*Transmitting*, *Waiting for GPS*, *Paused while
  ATAK is open*), how many servers are connected, and when the last report was
  sent.

### Pause while ATAK is open

In TAK Beacon, **Settings → ATAK → Pause while ATAK is open** stops TAK
Beacon's reports while ATAK is in front, so you are not on the map twice.
Reporting resumes about 15 seconds after ATAK leaves the screen. An active
emergency is never paused.

## 4. Emergencies

Emergencies are sent from the TAK Beacon app, so they work with ATAK closed:

- On the Beacon screen, choose **911 Alert**, **Ring The Bell** or **In
  Contact**, then **hold** the red button for two seconds.
- With **Volume-key SOS** on (TAK Beacon **Settings → Safety**), pressing a
  volume key five times within three seconds sends a 911 alert, even with the
  screen off.
- While an alert is active, a red banner offers **Cancel**, which clears the
  alert on every server.

## 5. Other ways to add a server in the app

In TAK Beacon, open **Servers**:

- **Scan QR** — the enrollment QR code from your TAK server administrator.
- **Sign in** — server address, username and password; TAK Beacon requests its
  own certificate from the server's enrollment port (8446).
- **Data package** — the .zip connection package your administrator sent. You
  can also open a package directly with TAK Beacon.
- **Manual** — address, port and certificate files.

If a server presents a certificate the device does not recognise, TAK Beacon
shows its fingerprint and asks you to confirm it once.

## Troubleshooting

- **"Connecting to app" never clears** — open the TAK Beacon app once, then
  reopen the pane.
- **"Server rejected this device's certificate"** — send the server from ATAK
  again, or re-enroll it in TAK Beacon.
- **Reports stop with the screen off** — set TAK Beacon's battery use to
  **Unrestricted** (TAK Beacon **Settings → Startup & battery**).
- **"Waiting for GPS"** — TAK Beacon needs a location fix before it reports;
  check that location is on.

## Network and privacy

TAK Beacon sends your position only to the servers you add: their streaming
port (8089 TLS or 8087 TCP by default) and, during sign-in only, their
enrollment port (8446). It does not listen for incoming connections. The ATAK
plugin itself makes no network connections.

The same manual is inside the plugin: **Settings → Tool Preferences → Specific
Tool Preferences → TAK Beacon → User manual**.

Problems or questions: https://github.com/cfd2474/TAK_Beacon/issues
