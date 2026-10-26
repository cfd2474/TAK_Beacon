# TAK Beacon — User Guide

**Version 1.4.5** · ATAK plugin and the TAK Beacon app

Prefer pictures? The [user manual with screenshots (PDF)](https://github.com/cfd2474/TAK_Beacon/blob/main/docs/TAK_Beacon_User_Manual.pdf) covers the same steps.

**Download TAK Beacon 1.4.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.5/ATAK-Plugin-TAKBeacon-1.4.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.5/ATAK-Plugin-TAKBeacon-1.4.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/cfd2474/TAK_Beacon/releases/download/v1.4.5/ATAK-Plugin-TAKBeacon-1.4.5--5.8.0-civ-release.apk

**Standalone app, no ATAK:** `TAKBeacon-app-1.4.5.apk` on the [v1.4.5 release page](https://github.com/cfd2474/TAK_Beacon/releases/tag/v1.4.5) (the same Play-signed app the plugin installs; Android 8+)

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

### Keeping the app up to date

Each plugin release carries the matching TAK Beacon app. The plugin checks the
installed app shortly after ATAK starts and whenever the app is installed,
updated or removed. If the installed app is older, ATAK shows a notification,
**TAK Beacon update available**, and the pane reads *Update to … available*.
Open the pane, tap **Update TAK Beacon** and confirm Android's update screen.
Servers, settings and the PIN are kept. If the beacon was on, it comes back on
by itself after the update, even while TAK Beacon is locked.

## 2. Give TAK Beacon your server

1. In the pane, tap **Send a server to TAK Beacon** and pick one of ATAK's
   connections.
2. Confirm. TAK Beacon receives the server, ATAK's certificates for it, and your
   callsign, team and role, so you appear as **one** marker whichever app is
   reporting.
3. The first time, TAK Beacon opens in front of ATAK and asks you to approve:
   check the request and tap **Approve** (or **Reject**), and you are back in
   ATAK. Approving trusts this ATAK install for later updates; you can revoke
   it in TAK Beacon under **Settings → ATAK**. If TAK Beacon doesn't open, tap
   its notification instead.

If ATAK has no client certificate for that server, TAK Beacon adds it and asks
you to sign in to get its own.

## 3. Start and stop reporting

- **Start beacon** turns reporting on. It is available once the app is
  installed; if TAK Beacon isn't running, the plugin starts it first. The first time, TAK Beacon opens to
  explain how it uses your location and to ask for permission, then returns you
  to ATAK.
- While reporting, Android shows a **TAK Beacon** notification. **Stop beacon**
  in the pane, or **Stop** in the notification, turns it off.
- The pane shows the state (*Transmitting*, *Waiting for GPS*, *Paused while
  ATAK is running*), how many servers are connected, and when the last report was
  sent.

### Adaptive reporting

**Settings → Reporting → Adaptive reporting** (off by default) reports by
movement instead of a fixed interval: every 3 minutes when still, 30 seconds
walking, 10 seconds driving and 5 seconds above about 30 mph. It reports at once
when you start moving, speed up, or turn while driving, and slows down only
after you have stayed slower for a while (so a traffic light doesn't change it).
On low battery (20% or less, not charging) the intervals are longer. Satellite
never reports faster than its own rate, and emergencies are unaffected. The
Beacon screen shows the current state, for example *Adaptive · Walking · Every
30s*. Your organization can set it.

### Pause while ATAK is running

In TAK Beacon, **Settings → ATAK → Pause while ATAK is running** stops TAK
Beacon's reports for as long as ATAK is running (in front or in the
background), so you are not on the map twice.
Reporting resumes as soon as ATAK is closed or stops. An active
emergency is never paused.

### Map symbol

**Settings → Reporting → Map symbol** sets how you appear on the map: *Ground
unit* (the default, as ATAK reports itself), *Vehicle*, *Aircraft* or *Boat*.
Your organization can also set it.

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

**Managed by your organization.** If your organization manages the phone, it can
set up TAK Beacon's server, callsign, team and role for you. That server is
marked *Managed by your organization* and can't be changed or removed on the
phone, and the settings it provides show *Set by your organization*. If its
certificate needs confirming, tap **Review certificate** on the server.
Administrators: see [MDM managed configuration](https://github.com/cfd2474/TAK_Beacon/blob/main/docs/mdm-managed-config.md).

## 6. Satellite (T-Mobile T-Satellite)

TAK Beacon is satellite-ready (Android's constrained satellite networks; it is
listed under the phone's *Satellite connectivity → Supported apps*). When
satellite is the only network:

- it keeps reporting from its foreground service, at the slower **satellite
  rate** (1 minute by default; Settings → Satellite), and markers stay on the
  map longer to match;
- it pings the server less often and turns the map preview off to save data;
- the app, its notification and the ATAK pane say **Via satellite**.

Emergencies are always sent over satellite. To keep TAK Beacon off satellite
except for emergencies, turn off **Use satellite data**; it then shows *Paused
on satellite* until a normal network returns.

## 7. UDP broadcast (mesh SA)

Off by default. In **Settings → UDP broadcast**, turn on **Broadcast position
over UDP** to also send every report (and any emergency) as CoT over UDP, so
ATAK and other TAK clients on the same Wi-Fi or mesh see you without a server.
The default destination, `239.2.3.1:6969`, is ATAK's situational-awareness
multicast; enter another address and port to send to a specific host. UDP works
with no server configured. It is **not encrypted**: anyone on that network can
read it.

Turn on **Only when no server is connected** to send routine reports over UDP
only while no TAK server is connected (so a connected phone isn't on the map
twice). Emergencies always go over UDP when it is on.

## Messages (TAK chat)

While the beacon is on and connected, TAK Beacon receives TAK chat: messages
sent to you, to **All Chat Rooms** and to your team's room. Each one notifies
(turn off group notifications in **Settings → Messages**) and appears in the
**Messages** tab, which shows an unread count.

- Reply from the conversation, with the quick replies (*Copy*, *En route*,
  *Need assistance*), or straight from the notification's **Reply**.
- Post to All Chat Rooms or your team room from their conversations.
- ATAK users see when you received and read their direct messages; turn this
  off in **Settings → Messages → Send read receipts**.
- While TAK Beacon is locked you can read but not reply.
- While the beacon is paused for ATAK, messages are saved without a
  notification: ATAK on the same phone shows them.

Messages are kept on the phone, encrypted, for up to 30 days (500 at most).

### Priority alerts

In **Settings → Messages → Priority alerts**, enter words or phrases separated
by commas (for example *mayday, evacuate, shots fired*) and tap **Save
keywords**. When a received message contains one, as a whole word and
ignoring case, TAK Beacon goes into alert mode:

- An alarm sounds at full volume, even with the phone on silent or vibrate,
  for 2 minutes or until you acknowledge it, whichever comes first. The phone
  vibrates too.
- A priority notification shows the message with an **Acknowledge** button.
  It stays until acknowledged: swiping it away brings it back. Opening TAK
  Beacon shows the alert until you tap **Acknowledge**, which also opens the
  conversation. You can acknowledge while TAK Beacon is locked.
- With the phone locked or asleep, the screen turns on and the alert fills it,
  over the lock screen, with **Acknowledge** and **Acknowledge and open
  conversation** (which asks you to unlock first). While you are using
  another app, the same alert screen opens in front of it; **Acknowledge**
  takes you back to that app.
- Afterwards the alarm volume goes back to what it was.

For the full-screen alert, Settings may show two more buttons: **Allow full
screen on lock screen** (Android 14 and later) and **Allow over other apps**
(in the list that opens, tap TAK Beacon and turn it on). Without them the
alert still sounds, as a notification.

Do Not Disturb normally lets alarms through. If yours silences alarms, tap
**Allow through Do Not Disturb** and allow TAK Beacon: it then lifts Do Not
Disturb while the alarm sounds and restores it after. **Test alert** tries it.

Keywords are checked in every received chat (direct, All Chat Rooms, team),
even with group notifications off. No alert sounds for a message more than 15
minutes old, or while the beacon is paused for ATAK unless you turn on **Alert
while paused for ATAK** (off by default). Your organization can set
the keywords; they are then read only.

## 8. Lock with a PIN

Use the lock to keep a configured beacon exactly as it is (on a tracker issued
to someone, for example):

- On the Beacon screen, tap **Lock with a PIN** and choose a 4–8 digit PIN.
  TAK Beacon locks as soon as it is set. Change or remove the PIN in
  **Settings → Lock**.
- While locked, the beacon cannot be switched on or off (from the app, its
  notification, or the ATAK plugin), and settings and servers cannot be
  changed. **Emergency alerts still work.**
- Tap **Locked · tap to unlock** (or **Unlock** on a locked screen) and enter
  the PIN to make changes; tap the lock again to re-lock. After five wrong PINs
  TAK Beacon makes you wait, longer after each further miss.
- The PIN cannot be recovered. A forgotten PIN means clearing TAK Beacon's
  storage (Android Settings → Apps), which removes its servers and certificates.

## 9. Permissions, battery and starting automatically

- Every time TAK Beacon opens, it checks everything it needs and asks for
  whatever is missing, one at a time:
  - precise location, notifications, and location **"Allow all the time"**
    (Android shows a settings screen for that one: choose *Allow all the
    time*), which keep the beacon reporting with the screen off, after a
    restart and when ATAK starts it;
  - **unrestricted battery**, so Android doesn't stop reports while the screen
    is off;
  - for priority alerts: **display over other apps**, **full-screen alerts**
    (Android 14 and later) and **Do Not Disturb access**. These three are
    switched on in an Android settings page that TAK Beacon opens: tap TAK
    Beacon in the list and turn it on, then go back. They aren't asked for
    when your organization has turned chat off.

  **Not now** skips one; you're asked again next time. (It doesn't ask when
  ATAK opens TAK Beacon just to start the beacon.)
- While anything is missing, a red banner across the top of the Beacon screen
  lists each missing permission and what doesn't work without it, with a
  **Grant permissions** button; **Settings → Permissions** has the same
  button. If Android no longer shows a prompt (it was refused twice), TAK
  Beacon opens its Android settings page instead: open *Permissions* and allow
  it there.
- **Settings → Startup & battery → Start on boot** (on by default) starts the
  beacon whenever the phone starts *and* whenever the app is opened. It needs
  location access "Allow all the time".

## Troubleshooting

- **"Connecting to app" never clears** — open the TAK Beacon app once, then
  reopen the pane.
- **"Server rejected this device's certificate"** — send the server from ATAK
  again, or re-enroll it in TAK Beacon.
- **"Stopped retrying"** on a server — after five certificate failures in a row
  TAK Beacon stops reconnecting, so the server doesn't block the phone. Fix the
  certificate (re-enroll, or send the server from ATAK again), then tap
  **Retry**.
- **Reports stop with the screen off** — allow TAK Beacon to run without
  battery restrictions (it asks when opened; or **Settings → Startup &
  battery**).
- **Start/Stop does nothing** — TAK Beacon is locked; unlock it with its PIN.
- **Red missing-permissions banner** — tap it and allow what it lists,
  choosing location "Allow all the time".
- **"Waiting for GPS"** — TAK Beacon needs a location fix before it reports;
  check that location is on.

## Network and privacy

TAK Beacon sends your position only to the servers you add: their streaming
port (8089 TLS or 8087 TCP by default) and, during sign-in only, their
enrollment port (8446); and, only if you turn UDP broadcast on, to its UDP
destination (default 239.2.3.1:6969). It does not listen for incoming connections. The ATAK
plugin itself makes no network connections.

The same manual is inside the plugin: **Settings → Tool Preferences → Specific
Tool Preferences → TAK Beacon → Plugin documentation**.

Problems or questions: https://github.com/cfd2474/TAK_Beacon/issues
