# Pushing a TAK server to TAK Beacon from an MDM

From version 1.3.3, TAK Beacon accepts an Android **managed configuration** (app
restrictions). An MDM can give it a TAK server, a callsign, team and role, and
can have the beacon start by itself. Any MDM that sets app restrictions works:
a device owner calling `DevicePolicyManager.setApplicationRestrictions`, an
Android Enterprise console, or ATLAS (TAK-MDM).

The keys are declared in the app (`tracker/app/src/main/res/xml/app_restrictions.xml`),
so consoles that read an APK's restrictions build their form from them. All keys
are flat (string, integer, bool and choice); there are no bundles or bundle arrays.

## Keys

Every key is optional. A key the MDM leaves out falls back to its default or to
the user's own setting.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `managed_config_version` | string | | Any text. Change it to make devices apply the configuration again (and enroll again). |
| `server_name` | string | the address | Name shown in TAK Beacon. |
| `server_host` | string | | TAK server host name or IP. Leave empty to manage identity only. |
| `server_port` | integer | 8089 | CoT streaming port (8089 TLS, 8087 TCP). |
| `server_protocol` | choice `ssl`/`tcp` | `ssl` | |
| `enroll_port` | integer | 8446 | Certificate enrollment port. |
| `enroll_username` | string | | With the password, the device requests its own client certificate (`/Marti/api/tls/signClient/v2`). |
| `enroll_password` | string | | TAK password or token. |
| `server_cert_sha256` | string | | Only for a self-signed server: its SHA-256 fingerprint, so devices trust it without asking. |
| `client_p12_base64` | string | | Instead of enrollment: the client certificate as a base64 .p12. |
| `client_p12_password` | string | | |
| `trust_p12_base64` | string | | Optional server CA as a base64 .p12 trust store. |
| `trust_p12_password` | string | | |
| `callsign` | string | | Set here, the user cannot change it. |
| `team` | choice | | `White` … `Brown` (the TAK team colours). |
| `role` | choice | | `Team Member`, `Team Lead`, `HQ`, `Sniper`, `Medic`, `Forward Observer`, `RTO`, `K9`. |
| `cot_type` | string | `a-f-G-U-C` | Map symbol (CoT type) of position reports, e.g. `a-f-G-E-V-C` for a vehicle (1.3.4+). |
| `reporting_mode` | choice `fixed`/`adaptive` | `fixed` | Fixed interval, or Adaptive: by movement, 3 min still to 5 s fast (1.4.1+). |
| `messaging` | choice `full`/`read_only`/`off` | `full` | TAK chat: receive and reply, receive only, or off (1.3.5+). |
| `alert_keywords` | string | (none) | Priority-alert keywords, comma separated (up to 50). A received chat containing one sounds an alarm at full volume until acknowledged, at most 2 minutes. Replaces the user's list, which becomes read only (1.4.3+). |
| `start_beacon` | bool | false | Turn the beacon on when the configuration arrives and at every boot. The user can still stop it. |

Keys holding secrets contain "password", so consoles that mask such keys (ATLAS
does) hide them.

## What the device does

- **When it applies.** At app start, at boot, and whenever Android reports the
  restrictions changed. An MDM may re-send an unchanged configuration on every
  sync: the server is only set up again (and re-enrolled) when its part of the
  configuration changes, or when `managed_config_version` changes.
- **Server.** The server appears in TAK Beacon's Servers list as *Managed by
  your organization*. The user cannot switch it off, edit or remove it. If the
  user had already added the same address and port, that entry is taken over
  rather than duplicated.
- **Certificates.** In order of preference: `client_p12_base64` if given, else
  enrollment with `enroll_username`/`enroll_password`, else the server waits
  for the user to tap *Sign in*. A server whose certificate is not trusted by
  Android (self-signed, no `trust_p12_base64`) needs `server_cert_sha256`, or
  the user confirms its fingerprint once with *Review certificate*.
- **Identity.** `callsign`, `team`, `role` and `cot_type` replace the user's values and
  are shown read-only (*Set by your organization*). A server sent from ATAK's
  plugin does not change them either.
- **Problems** (wrong password, unreachable server, a certificate that won't
  open) are shown on the managed server in TAK Beacon, and the configuration is
  retried at the next app start, boot or configuration change.
- **Removal.** An empty configuration means "not managed": the managed server
  and its certificates are removed and the settings unlock. The user's own
  servers and settings are kept.
- **Per-device values.** Text values may use the MDM's variables (in ATLAS:
  `${device.name}`, `${device.serial}`, `${attribute.Team}`, ...). A value the
  MDM could not fill in, or an unexpanded `${...}`, counts as unset.

The device does not report status back to the MDM.

## ATLAS (TAK-MDM) example

In ATLAS, *App Management → App configurations* for `com.taksolutions.takbeacon`:

| Key | Value |
|---|---|
| `server_name` | `Unit 7` |
| `server_host` | `tak.example.org` |
| `enroll_username` | `${device.name}` |
| `enroll_password` | `${attribute.TAK password}` |
| `callsign` | `E7-${attribute.Unit}` |
| `team` | `Cyan` |
| `role` | `Team Member` |
| `start_beacon` | true |
| `managed_config_version` | `1` |

Notes from the ATLAS side: ATLAS runs as device owner, supports
string/bool/integer/choice keys (not bundle arrays), masks keys containing
"password", and when an app leaves the policy it sends an empty configuration,
which TAK Beacon treats as "not managed".

## Testing without an MDM

Any device-owner test app can set the restrictions, for example Google's
TestDPC (*Manage application restrictions*), or `adb shell dpm set-device-owner`
with a small app that calls `DevicePolicyManager.setApplicationRestrictions`.
