// Keep this file pure ASCII (write other characters as \u{...}): the tak.gov
// build rewrites it in place with the JVM default charset, which may not be UTF-8.
//
// Laid out like the ATAK-CIV SDK's plugin manual samples (plugin-examples/*/docs/
// user_manual): one topic per slide, text beside screenshots. Screenshots are in
// screens/, taken on an emulator (ATAK-CIV 5.8 SDK build, placeholder server
// tak.example.com).

#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "TAK Beacon",
   plugin-version: "1.3.2",
   platform: "ATAK",
   platform-version: "5.6 \u{2013} 5.8",
)

#set text(size: 14pt)
#set list(spacing: 0.6em)
#set enum(spacing: 0.6em)

// A phone screenshot, sized to fit beside text on a slide.
#let phone(path) = box(stroke: 0.5pt + luma(180), image(path, height: 9.2cm))
// An ATAK screenshot (landscape crop).
#let atak(path, w: 100%) = box(stroke: 0.5pt + luma(120), image(path, width: w))


#tak-slide[
= Overview
#toolbox.side-by-side(columns: (0.9fr, 8fr, 4fr))[
#image("plugin_icon.png", width: 90%)
][
TAK Beacon keeps reporting your position to your TAK server when ATAK is not running. It has two parts:

- *The TAK Beacon app*, a small position reporter that runs in the background with its own notification.
- *This ATAK plugin*, which installs and updates the app, starts and stops it, and gives it ATAK's server connection so it reports to the same server under your callsign.

Open the plugin from ATAK's *Tools* menu: *TAK Beacon*.
][
#atak("screens/atak_tools.jpg")
]
]

#tak-slide[
= Install the TAK Beacon app
#toolbox.side-by-side(columns: (7fr, 5fr))[
+ Open *TAK Beacon* from the ATAK menu. The pane shows whether the app is installed and which version this plugin carries.
+ Tap *Install TAK Beacon*.
+ The first time, Android asks to *allow ATAK to install unknown apps*. Tap *Settings*, turn on *Allow from this source*, then go back. This is asked once.
+ Android shows its install screen. Tap *Install*.

The pane then reads _Installed, connected_. If it says the installed app is a _different build_, it was installed from another source: tap *Uninstall other build*, then install again.

*Start beacon* is available once the app is installed.
][
#atak("screens/pane_not_installed.jpg")
]
]

#tak-slide[
= Keep the app up to date
#toolbox.side-by-side(columns: (6fr, 6fr))[
Each plugin release carries the matching TAK Beacon app (both have the same version number). Shortly after ATAK starts, and whenever the app is installed, updated or removed, the plugin checks the installed app.

If it is older, ATAK shows *TAK Beacon update available*. Open the pane, tap *Update TAK Beacon* and confirm Android's update screen.

Servers, settings and the PIN are kept. A beacon that was on comes back on by itself, even while TAK Beacon is locked.
][
#atak("screens/update_notification.jpg")
#v(0.3em)
#grid(columns: (1fr, 1fr), gutter: 0.4em, align: top,
  atak("screens/pane_update.jpg"), atak("screens/android_update.jpg"))
]
]

#tak-slide[
= First run: location permission
#toolbox.side-by-side(columns: (6fr, 3fr, 3fr))[
The first time TAK Beacon opens, it explains how it uses your location, then Android asks for it:

+ *Share your location*: tap *Continue*.
+ Android: choose *Precise* and *While using the app*.
+ Android: allow *notifications*, so the beacon's status stays visible.

Your position is sent only to the TAK servers you add (and the UDP destination, if you turn UDP broadcast on), and only while the beacon is on.
][
#phone("screens/app_location_disclosure.jpg")
][
#phone("screens/app_home_off.jpg")
]
]

#tak-slide[
= First run: location all the time and battery
#toolbox.side-by-side(columns: (5fr, 3fr, 3fr, 3fr))[
TAK Beacon then asks for location *Allow all the time*, so it keeps reporting with the screen off, after a restart and when ATAK starts it. Tap *Continue*; on Android's screen choose *Allow all the time*, then go back.

Last, if Android is limiting its battery use, TAK Beacon asks to run without restrictions. Tap *Allow*, then *Allow* again: otherwise reports can stop while the screen is off.
][
#phone("screens/app_background_disclosure.jpg")
][
#phone("screens/app_allow_all_time.jpg")
][
#phone("screens/app_battery.jpg")
]
]

#tak-slide[
= Give TAK Beacon your server
#toolbox.side-by-side(columns: (6fr, 6fr))[
+ In the pane, tap *Send a server to TAK Beacon* and pick one of ATAK's connections.
+ Confirm with *Send*. TAK Beacon receives the server, ATAK's certificates for it, and your callsign, team and role, so you appear as *one* marker whichever app is reporting.

If ATAK has no client certificate for that server, TAK Beacon adds it and asks you to sign in to get its own.
][
#grid(columns: (1fr, 1fr), gutter: 0.4em, align: top,
  atak("screens/send_list.jpg"), atak("screens/send_confirm.jpg"))
#v(0.4em)
#atak("screens/pane_sent.jpg", w: 60%)
]
]

#tak-slide[
= Approve ATAK once
#toolbox.side-by-side(columns: (7fr, 3fr, 3fr))[
The first time ATAK sends a server, TAK Beacon asks you to approve it: tap its notification (or open the app), check the request and tap *Approve*.

Approving trusts this ATAK install: later servers from it are applied without asking. To revoke it, use *Forget* in TAK Beacon under *Settings, ATAK*.

The server then shows in TAK Beacon's *Servers* list. A server marked _Needs a certificate_ needs *Sign in* once with your TAK username and password.
][
#phone("screens/app_approve.jpg")
][
#phone("screens/app_servers_list.jpg")
]
]

#tak-slide[
= Start and stop reporting
#toolbox.side-by-side(columns: (6fr, 5fr, 3fr))[
- *Start beacon* turns reporting on. If TAK Beacon isn't running, the plugin starts it first.
- While reporting, Android shows a *TAK Beacon* notification. *Stop beacon* in the pane or the app, or *Stop* in the notification, turns it off.
- The pane shows the state (_Transmitting_, _Waiting for GPS_, _Paused while ATAK is running_), how many servers are connected, and when the last report was sent.
][
#atak("screens/pane_installed.jpg")
][
#phone("screens/app_home_transmitting.jpg")
]
]

#tak-slide[
= Pause while ATAK is running
#toolbox.side-by-side(columns: (6fr, 5fr, 3fr))[
In TAK Beacon, *Settings, ATAK, Pause while ATAK is running* (on by default) stops TAK Beacon's reports for as long as ATAK is running, in front or in the background, so you are not on the map twice.

Reporting resumes as soon as ATAK is closed or stops. An active emergency is never paused.
][
#atak("screens/pane_paused.jpg")
][
#phone("screens/app_settings_atak.jpg")
]
]

#tak-slide[
= The Beacon screen and emergencies
#toolbox.side-by-side(columns: (7fr, 3fr))[
The Beacon screen shows your position (tap *MGRS* to change the format), accuracy, source and battery, a map preview and your servers.

Emergencies are sent from the TAK Beacon app, so they work with ATAK closed:
- Choose *911 Alert*, *Ring The Bell* or *In Contact*, then *hold* the red button for two seconds.
- With *Volume-key SOS* on (*Settings, Safety*), pressing a volume key five times within three seconds sends a 911 alert, even with the screen off.
- While an alert is active, a red banner offers *Cancel*, which clears it on every server.

#text(size: 9pt)[Map data \u{00A9} OpenStreetMap contributors.]
][
#phone("screens/app_home_map_emergency.jpg")
]
]

#tak-slide[
= Other ways to add a server in the app
#toolbox.side-by-side(columns: (6fr, 3fr, 3fr))[
In TAK Beacon, open *Servers*:
- *Scan QR*: the enrollment QR code from your TAK server administrator.
- *Sign in*: server address, username and password; TAK Beacon requests its own certificate from the server's enrollment port (8446).
- *Data package*: the .zip connection package your administrator sent. You can also open a package directly with TAK Beacon.
- *Manual*: address, port and certificate files.

If a server presents a certificate the device does not recognise, TAK Beacon shows its fingerprint and asks you to confirm it once.
][
#phone("screens/app_servers.jpg")
][
#phone("screens/app_sign_in.jpg")
]
]

#tak-slide[
= Satellite and UDP broadcast
#toolbox.side-by-side(columns: (8fr, 3fr))[
*Satellite (T-Mobile T-Satellite).* When satellite is the only network, TAK Beacon keeps reporting at a slower satellite rate (1 minute by default) and turns its map preview off to save data. The pane and the app say *Via satellite* while this is happening. Emergencies are always sent. Turn satellite data off, or change its rate, in *Settings, Satellite*.

*UDP broadcast.* TAK Beacon can also send each report as CoT over UDP, so ATAK and other TAK clients on the same Wi-Fi or mesh see you without a server. It is off by default and is not encrypted. Turn it on in *Settings, UDP broadcast*; the default destination, 239.2.3.1:6969, is ATAK's situational-awareness multicast.
][
#phone("screens/app_settings_satellite_udp.jpg")
]
]

#tak-slide[
= Lock with a PIN
#toolbox.side-by-side(columns: (5fr, 3fr, 3fr, 4fr))[
On the Beacon screen, tap *Lock with a PIN* and choose a 4\u{2013}8 digit PIN; TAK Beacon locks as soon as it is set.

While locked, the beacon can't be switched on or off (from the app, its notification or this plugin) and settings and servers can't be changed. Emergency alerts still work.

Tap *Locked \u{00B7} tap to unlock* and enter the PIN to make changes. Change or remove the PIN in *Settings, Lock*. The PIN cannot be recovered.
][
#phone("screens/app_pin_dialog.jpg")
][
#phone("screens/app_home_locked.jpg")
][
#atak("screens/pane_locked.jpg")
]
]

#tak-slide[
= Missing permissions
#toolbox.side-by-side(columns: (6fr, 3fr, 3fr))[
If a permission TAK Beacon needs is turned off later, a red *Missing permissions* banner shows across the top of the Beacon screen, and *Settings, Permissions* shows a *Grant permissions* button. Tap either to be asked again.

If Android no longer shows the prompt (it was refused twice), TAK Beacon opens its Android settings page instead: open *Permissions* and allow *Location: All the time* and *Notifications* there.

*Start on boot* (*Settings, Startup and battery*) starts the beacon when the phone starts and whenever the app is opened.
][
#phone("screens/app_missing_permissions.jpg")
][
#phone("screens/app_settings_permissions.jpg")
]
]

#tak-slide[
= Troubleshooting
- *Connecting to app* never clears: tap *Start beacon* (it starts the app), or open the TAK Beacon app once, then reopen this pane.
- *Server rejected this device's certificate*: send the server from ATAK again, or *Sign in* to it again in TAK Beacon.
- *Reports stop with the screen off*: allow location *All the time*, and set TAK Beacon's battery use to *Unrestricted* (*Settings, Startup and battery*).
- *Start or Stop does nothing*: TAK Beacon is locked; unlock it with its PIN.
- *Waiting for GPS*: TAK Beacon needs a location fix before it reports; check that location is on.
- *Red Missing permissions banner*: tap it and allow what it lists.
]

#tak-slide[
= Network, privacy and this manual
#toolbox.side-by-side(columns: (7fr, 5fr))[
TAK Beacon sends your position only to the servers you add, on their streaming port (8089 TLS or 8087 TCP by default) and, during sign-in only, their enrollment port (8446), plus the UDP destination if you turn UDP broadcast on. It does not listen for incoming connections. The plugin itself makes no network connections.

This manual is in ATAK under *Settings, Tool Preferences, Specific Tool Preferences, TAK Beacon, User manual*.
][
#atak("screens/tool_prefs_manual.jpg")
]
]
