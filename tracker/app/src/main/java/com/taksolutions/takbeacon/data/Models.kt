package com.taksolutions.takbeacon.data

import kotlinx.serialization.Serializable

/** TAK team colours, as they appear in the CoT `__group name` attribute. */
enum class Team(val cotName: String, val argb: Long) {
    WHITE("White", 0xFFFFFFFF),
    YELLOW("Yellow", 0xFFFFEB3B),
    ORANGE("Orange", 0xFFFF9800),
    MAGENTA("Magenta", 0xFFE040FB),
    RED("Red", 0xFFF44336),
    MAROON("Maroon", 0xFF8E2430),
    PURPLE("Purple", 0xFF7E57C2),
    DARK_BLUE("Dark Blue", 0xFF1A3A9E),
    BLUE("Blue", 0xFF2196F3),
    CYAN("Cyan", 0xFF00BCD4),
    TEAL("Teal", 0xFF009688),
    GREEN("Green", 0xFF4CAF50),
    DARK_GREEN("Dark Green", 0xFF1B5E20),
    BROWN("Brown", 0xFF795548);

    companion object {
        fun fromCot(name: String?): Team? = entries.firstOrNull { it.cotName.equals(name?.trim(), ignoreCase = true) }
    }
}

/** TAK team roles, as they appear in the CoT `__group role` attribute. */
enum class Role(val cotName: String) {
    TEAM_MEMBER("Team Member"),
    TEAM_LEAD("Team Lead"),
    HQ("HQ"),
    SNIPER("Sniper"),
    MEDIC("Medic"),
    FORWARD_OBSERVER("Forward Observer"),
    RTO("RTO"),
    K9("K9");

    companion object {
        fun fromCot(name: String?): Role? = entries.firstOrNull { it.cotName.equals(name?.trim(), ignoreCase = true) }
    }
}

enum class CoordFormat(val label: String) { DD("Decimal degrees"), DMS("Deg/Min/Sec"), MGRS("MGRS") }

enum class SpeedUnit(val label: String, val fromMps: Double) {
    KMH("km/h", 3.6), MPH("mph", 2.2369362921), KNOTS("kn", 1.9438444924), MPS("m/s", 1.0)
}

enum class Transport(val wire: String) {
    SSL("ssl"), TCP("tcp");

    companion object {
        fun fromWire(s: String?): Transport = if (s.equals("tcp", ignoreCase = true)) TCP else SSL
    }
}

/**
 * One TAK server. Certificate material lives in [com.taksolutions.takbeacon.security.CertStore]
 * under this server's [id]; passwords are stored encrypted with a hardware-backed key.
 */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8089,
    val transport: Transport = Transport.SSL,
    val enabled: Boolean = true,
    val hasClientCert: Boolean = false,
    val hasTrustStore: Boolean = false,
    /** SHA-256 of a server certificate the user accepted on first use, hex. */
    val pinnedServerSha256: String? = null,
    /** Set when a data package carried a trust store but no client certificate. */
    val needsEnrollment: Boolean = false,
    val enrollUsername: String? = null,
    val csrPort: Int = 8446,
    /** Origin of the profile, shown in the server list. */
    val source: String = "manual",
)

data class BeaconSettings(
    val uid: String,
    val callsign: String,
    val team: Team = Team.CYAN,
    val role: Role = Role.TEAM_MEMBER,
    val intervalSeconds: Int = 10,
    val staleSeconds: Int = 120,
    val coordFormat: CoordFormat = CoordFormat.MGRS,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val startOnBoot: Boolean = false,
    val atakAutoPause: Boolean = true,
    val volumeSos: Boolean = false,
    val kioskLock: Boolean = false,
    val darkMap: Boolean = true,
    val showMap: Boolean = true,
    /** Whether tracking was on when the app last stopped; restored on boot. */
    val wasRunning: Boolean = false,
    /** Signing certificate (SHA-256 hex) of the ATAK build the user trusted. */
    val trustedAtakSigner: String? = null,
    /** Report over satellite (e.g. T-Mobile T-Satellite) when it is the only network. */
    val satelliteData: Boolean = true,
    /** Slowest-of interval used while on a constrained satellite network. */
    val satelliteIntervalSeconds: Int = 60,
    /** Also send each report as CoT over UDP (mesh SA), unencrypted. */
    val udpBroadcast: Boolean = false,
    val udpHost: String = DEFAULT_UDP_HOST,
    val udpPort: Int = DEFAULT_UDP_PORT,
    /** PBKDF2 hash and salt of the lock PIN; null when no PIN is set. */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** Settings, servers and the beacon's on/off are frozen until the PIN is entered. */
    val locked: Boolean = false,
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null

    companion object {
        /** ATAK's default situational-awareness multicast group. */
        const val DEFAULT_UDP_HOST = "239.2.3.1"
        const val DEFAULT_UDP_PORT = 6969
    }
}

/** TAK emergency types and their CoT event types. */
enum class EmergencyType(val label: String, val cotType: String, val detailType: String) {
    ALERT_911("911 Alert", "b-a-o-tbl", "911 Alert"),
    RING_THE_BELL("Ring The Bell", "b-a-o-pan", "Ring The Bell"),
    IN_CONTACT("Troops In Contact", "b-a-o-opn", "In Contact"),
    GEO_FENCE("Geo-fence Breached", "b-a-g", "Geo-fence Breached"),
}
