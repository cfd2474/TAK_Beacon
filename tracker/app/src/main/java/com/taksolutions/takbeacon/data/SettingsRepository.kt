package com.taksolutions.takbeacon.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("beacon_settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val UID = stringPreferencesKey("uid")
        val CALLSIGN = stringPreferencesKey("callsign")
        val TEAM = stringPreferencesKey("team")
        val ROLE = stringPreferencesKey("role")
        val INTERVAL = intPreferencesKey("interval_s")
        val STALE = intPreferencesKey("stale_s")
        val COORD = stringPreferencesKey("coord_format")
        val SPEED = stringPreferencesKey("speed_unit")
        val BOOT = booleanPreferencesKey("start_on_boot")
        val ATAK_PAUSE = booleanPreferencesKey("atak_auto_pause")
        val VOLUME_SOS = booleanPreferencesKey("volume_sos")
        val KIOSK = booleanPreferencesKey("kiosk_lock")
        val DARK_MAP = booleanPreferencesKey("dark_map")
        val SHOW_MAP = booleanPreferencesKey("show_map")
        val WAS_RUNNING = booleanPreferencesKey("was_running")
        val ATAK_SIGNER = stringPreferencesKey("trusted_atak_signer")
        val SAT_DATA = booleanPreferencesKey("satellite_data")
        val SAT_INTERVAL = intPreferencesKey("satellite_interval_s")
        val UDP = booleanPreferencesKey("udp_broadcast")
        val UDP_HOST = stringPreferencesKey("udp_host")
        val UDP_PORT = intPreferencesKey("udp_port")
    }

    val settings: Flow<BeaconSettings> = context.settingsStore.data.map(::settingsFrom)

    suspend fun current(): BeaconSettings = settings.first()

    /** Gives a fresh install a stable UID and a default callsign. */
    suspend fun ensureIdentity() {
        context.settingsStore.edit { p ->
            if (p[K.UID].isNullOrBlank()) p[K.UID] = "ANDROID-" + UUID.randomUUID().toString().replace("-", "").take(16)
            if (p[K.CALLSIGN].isNullOrBlank()) {
                val model = Build.MODEL.filter { it.isLetterOrDigit() }.take(8).uppercase()
                p[K.CALLSIGN] = "BEACON-" + model.ifEmpty { "1" }
            }
        }
    }

    suspend fun update(transform: (BeaconSettings) -> BeaconSettings) {
        context.settingsStore.edit { p ->
            val cur = settingsFrom(p)
            val n = transform(cur)
            p[K.UID] = n.uid
            p[K.CALLSIGN] = n.callsign.trim()
            p[K.TEAM] = n.team.name
            p[K.ROLE] = n.role.name
            p[K.INTERVAL] = n.intervalSeconds.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
            p[K.STALE] = n.staleSeconds.coerceIn(30, 3600)
            p[K.COORD] = n.coordFormat.name
            p[K.SPEED] = n.speedUnit.name
            p[K.BOOT] = n.startOnBoot
            p[K.ATAK_PAUSE] = n.atakAutoPause
            p[K.VOLUME_SOS] = n.volumeSos
            p[K.KIOSK] = n.kioskLock
            p[K.DARK_MAP] = n.darkMap
            p[K.SHOW_MAP] = n.showMap
            p[K.WAS_RUNNING] = n.wasRunning
            if (n.trustedAtakSigner == null) p.remove(K.ATAK_SIGNER) else p[K.ATAK_SIGNER] = n.trustedAtakSigner
            p[K.SAT_DATA] = n.satelliteData
            p[K.SAT_INTERVAL] = n.satelliteIntervalSeconds.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
            p[K.UDP] = n.udpBroadcast
            p[K.UDP_HOST] = n.udpHost.trim().ifEmpty { BeaconSettings.DEFAULT_UDP_HOST }
            p[K.UDP_PORT] = n.udpPort.coerceIn(1, 65535)
        }
    }

    private fun settingsFrom(p: Preferences): BeaconSettings = BeaconSettings(
        uid = p[K.UID] ?: "",
        callsign = p[K.CALLSIGN] ?: "",
        team = p[K.TEAM]?.let { runCatching { Team.valueOf(it) }.getOrNull() } ?: Team.CYAN,
        role = p[K.ROLE]?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: Role.TEAM_MEMBER,
        intervalSeconds = p[K.INTERVAL] ?: 10,
        staleSeconds = p[K.STALE] ?: 120,
        coordFormat = p[K.COORD]?.let { runCatching { CoordFormat.valueOf(it) }.getOrNull() } ?: CoordFormat.MGRS,
        speedUnit = p[K.SPEED]?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() } ?: SpeedUnit.KMH,
        startOnBoot = p[K.BOOT] ?: false,
        atakAutoPause = p[K.ATAK_PAUSE] ?: true,
        volumeSos = p[K.VOLUME_SOS] ?: false,
        kioskLock = p[K.KIOSK] ?: false,
        darkMap = p[K.DARK_MAP] ?: true,
        showMap = p[K.SHOW_MAP] ?: true,
        wasRunning = p[K.WAS_RUNNING] ?: false,
        trustedAtakSigner = p[K.ATAK_SIGNER],
        satelliteData = p[K.SAT_DATA] ?: true,
        satelliteIntervalSeconds = p[K.SAT_INTERVAL] ?: 60,
        udpBroadcast = p[K.UDP] ?: false,
        udpHost = p[K.UDP_HOST] ?: BeaconSettings.DEFAULT_UDP_HOST,
        udpPort = p[K.UDP_PORT] ?: BeaconSettings.DEFAULT_UDP_PORT,
    )

    companion object {
        const val MIN_INTERVAL = 2
        const val MAX_INTERVAL = 600
    }
}
