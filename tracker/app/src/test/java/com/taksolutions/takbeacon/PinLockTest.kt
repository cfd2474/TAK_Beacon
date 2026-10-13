package com.taksolutions.takbeacon

import com.taksolutions.takbeacon.security.PinLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    @Test fun validPins() {
        assertTrue(PinLock.isValidPin("1234"))
        assertTrue(PinLock.isValidPin("12345678"))
        assertFalse(PinLock.isValidPin("123"))
        assertFalse(PinLock.isValidPin("123456789"))
        assertFalse(PinLock.isValidPin("12a4"))
    }

    @Test fun hashVerifies() {
        val h = PinLock.hash("2468")
        assertTrue(PinLock.matches("2468", h))
        assertFalse(PinLock.matches("2469", h))
        assertFalse(PinLock.matches("", h))
    }

    @Test fun saltMakesEachHashUnique() {
        assertNotEquals(PinLock.hash("2468").hash, PinLock.hash("2468").hash)
    }

    @Test fun lockoutAfterFiveWrongAttempts() {
        PinLock.recordSuccess()
        val t = 1_000_000L
        repeat(4) { PinLock.recordFailure(t) }
        assertEquals(0L, PinLock.waitMs(t))
        PinLock.recordFailure(t)                       // 5th wrong PIN
        assertEquals(30_000L, PinLock.waitMs(t))
        PinLock.recordFailure(t)                       // 6th: doubles
        assertEquals(60_000L, PinLock.waitMs(t))
        assertEquals(0L, PinLock.waitMs(t + 60_000L))
        PinLock.recordSuccess()
        assertEquals(0L, PinLock.waitMs(t))
    }
}
