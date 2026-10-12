
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "TAK Beacon",
   plugin-version: "1.0.0",
   platform: "ATAK",
   platform-version: "5.6 – 5.8",
)

#set text(size: 17pt)
#set list(spacing: 0.7em)
#set enum(spacing: 0.7em)


#tak-slide[
= Overview
#block()
#toolbox.side-by-side(columns: (1.2fr, 9fr))[
#image("plugin_icon.png", width: 90%)
][
TAK Beacon keeps reporting your position to your TAK server when ATAK is not running. It has two parts:

- *The TAK Beacon app*, a small position reporter that runs in the background with its own notification.
- *This ATAK plugin*, which installs and updates the app, starts and stops it, and gives it ATAK's server connection so it reports to the same server under your callsign.

Open the plugin from ATAK's toolbar menu: *TAK Beacon*.
]
]

#tak-slide[
= Install the TAK Beacon app
#block()
+ Open *TAK Beacon* from the ATAK menu. The pane shows whether the app is installed and which version this plugin carries.
+ Tap *Install TAK Beacon* (or *Update TAK Beacon* when a newer version is included).
+ The first time, Android asks to *allow ATAK to install unknown apps*. Tap *Settings*, turn on *Allow from this source*, then go back. This is asked once.
+ Android shows its install screen. Tap *Install*.

The pane then reads _Installed, connected_. If it says the installed app is a _different build_, it was installed from another source; tap *Uninstall other build*, then install again.
]

#tak-slide[
= Give TAK Beacon your server
#block()
+ In the pane, tap *Send a server to TAK Beacon* and pick one of ATAK's connections.
+ Confirm. TAK Beacon receives the server, ATAK's certificates for it, and your callsign, team and role, so you appear as *one* marker whichever app is reporting.
+ The first time, TAK Beacon asks you to approve: tap its notification, check the request, and tap *Approve*. Approving trusts this ATAK install for later updates; it can be revoked in TAK Beacon under *Settings, ATAK*.

If ATAK has no client certificate for that server, TAK Beacon adds it and asks you to sign in to get its own.
]

#tak-slide[
= Start and stop reporting
#block()
- *Start beacon* turns reporting on. The first time, TAK Beacon opens to explain how it uses your location and to ask for permission, then returns you to ATAK.
- While reporting, Android shows a *TAK Beacon* notification. *Stop beacon* in the pane, or *Stop* in the notification, turns it off.
- The pane shows the state (_Transmitting_, _Waiting for GPS_, _Paused while ATAK is open_), how many servers are connected, and when the last report was sent.

== Pause while ATAK is open
#block()
In TAK Beacon, *Settings, ATAK, Pause while ATAK is open* stops TAK Beacon's reports while ATAK is in front, so you are not on the map twice. Reporting resumes about 15 seconds after ATAK leaves the screen. An active emergency is never paused.
]

#tak-slide[
= Emergencies
#block()
Emergencies are sent from the TAK Beacon app, so they work with ATAK closed:
- On the Beacon screen, choose *911 Alert*, *Ring The Bell* or *In Contact*, then *hold* the red button for two seconds.
- With *Volume-key SOS* turned on (TAK Beacon *Settings, Safety*), pressing a volume key five times within three seconds sends a 911 alert, even with the screen off.
- While an alert is active, a red banner offers *Cancel*, which clears the alert on every server.
]

#tak-slide[
= Other ways to add a server in the app
#block()
In TAK Beacon, open *Servers*:
- *Scan QR*: the enrollment QR code from your TAK server administrator.
- *Sign in*: server address, username and password; TAK Beacon requests its own certificate from the server's enrollment port (8446).
- *Data package*: the .zip connection package your administrator sent. You can also open a package directly with TAK Beacon.
- *Manual*: address, port and certificate files.

If a server presents a certificate the device does not recognise, TAK Beacon shows its fingerprint and asks you to confirm it once.
]

#tak-slide[
= Satellite (T-Mobile T-Satellite)
#block()
TAK Beacon is satellite-ready: when satellite is the only network, it keeps reporting at a slower satellite rate (1 minute by default) and turns its map preview off to save data. The pane and the app say *Via satellite* while this is happening. Emergencies are always sent. Turn satellite data off, or change its rate, in TAK Beacon *Settings, Satellite*.

]

#tak-slide[
= UDP broadcast
#block()
TAK Beacon can also send each report as CoT over UDP, so ATAK and other TAK clients on the same Wi-Fi or mesh see you without a server. It is off by default and is not encrypted. Turn it on in *Settings, UDP broadcast*; the default destination, 239.2.3.1:6969, is ATAK's situational-awareness multicast.
]

#tak-slide[
= Troubleshooting
#block()
- *Connecting to app* never clears: open the TAK Beacon app once, then reopen this pane.
- *Server rejected this device's certificate*: send the server from ATAK again, or re-enroll it in TAK Beacon.
- *Reports stop with the screen off*: in Android settings set TAK Beacon's battery use to *Unrestricted* (TAK Beacon *Settings, Startup and battery*).
- *Waiting for GPS*: TAK Beacon needs a location fix before it reports; check that location is on.

]

#tak-slide[
= Network and privacy
#block()
TAK Beacon sends your position only to the servers you add, on their streaming port (8089 TLS or 8087 TCP by default) and, during sign-in only, their enrollment port (8446), plus the UDP destination if you turn UDP broadcast on. It does not listen for incoming connections. The plugin itself makes no network connections.
]
