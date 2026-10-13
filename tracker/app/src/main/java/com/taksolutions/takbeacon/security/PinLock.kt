package com.taksolutions.takbeacon.security

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The beacon lock's PIN: stored only as a salted PBKDF2 hash, checked in
 * constant time, with an escalating lockout after wrong attempts.
 */
object PinLock {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    private const val ITERATIONS = 120_000
    private const val FREE_ATTEMPTS = 5

    data class Hashed(val hash: String, val salt: String)

    fun isValidPin(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    fun hash(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): Hashed {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded
        val b64 = Base64.getEncoder()
        return Hashed(b64.encodeToString(key), b64.encodeToString(salt))
    }

    fun matches(pin: String, stored: Hashed): Boolean {
        val salt = Base64.getDecoder().decode(stored.salt)
        val candidate = Base64.getDecoder().decode(hash(pin, salt).hash)
        return MessageDigest.isEqual(candidate, Base64.getDecoder().decode(stored.hash))
    }

    // Wrong-attempt throttling, per process. A restart does not reset it to
    // zero for free: it costs a cold start, and the delay grows quickly anyway.
    @Volatile private var failures = 0
    @Volatile private var blockedUntil = 0L

    /** Milliseconds to wait before the next attempt is allowed, or 0. */
    fun waitMs(now: Long = SystemClock.elapsedRealtime()): Long = (blockedUntil - now).coerceAtLeast(0)

    fun recordFailure(now: Long = SystemClock.elapsedRealtime()) {
        failures++
        if (failures >= FREE_ATTEMPTS) {
            // 30 s, 60 s, 120 s … capped at 15 minutes.
            val steps = (failures - FREE_ATTEMPTS).coerceAtMost(5)
            blockedUntil = now + (30_000L shl steps).coerceAtMost(15 * 60_000L)
        }
    }

    fun recordSuccess() {
        failures = 0
        blockedUntil = 0
    }
}
