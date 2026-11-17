package com.taksolutions.takbeacon.service

import com.taksolutions.takbeacon.cot.Fix
import kotlin.math.max

/**
 * Decides which position is good enough to report, against the operator's
 * location confidence limit (metres). Pure, so it is unit-tested; times are
 * elapsed-realtime milliseconds.
 *
 * - A fix counts only if its accuracy (CE) is known and within the limit. GPS or
 *   network, whichever meets it: network positions (Wi-Fi, cell) are fine when
 *   they are that accurate.
 * - GPS is preferred while it is good. A network fix takes over only when it is
 *   more accurate than the GPS fix held, allowing for how old that one is.
 * - Confidence decays between fixes: once a fix is a little old, its error is
 *   taken to grow by the speed it was moving at (at least walking pace), so a
 *   position held through a weak signal is reported only while it could still
 *   be within the limit, and never once it is [MAX_HOLD_MS] old.
 */
class FixFilter {

    private var held: Fix? = null
    private var heldAtMs = 0L
    private var heldGps = false

    /** The most recent fix of any accuracy (shown, and used by an emergency when nothing better is there). */
    var latest: Fix? = null
        private set

    /** Offers a fix taken at [takenAtMs]. True if it is now the position to report. */
    fun offer(fix: Fix, gps: Boolean, takenAtMs: Long, nowMs: Long, limitM: Int): Boolean {
        latest = fix
        val ce = fix.ce
        if (ce == null || !ce.isFinite() || ce < 0 || ce > limitM) return false
        val h = held
        val take = h == null || gps || !heldGps ||
            errorAt(h, heldAtMs, nowMs) > errorAt(fix, takenAtMs, nowMs)
        if (take) {
            held = fix
            heldAtMs = takenAtMs
            heldGps = gps
        }
        return take
    }

    /** The position to report now, its CE raised to the error it may have by now; null if none is good enough. */
    fun reportable(nowMs: Long, limitM: Int): Fix? {
        val h = held ?: return null
        if (nowMs - heldAtMs > MAX_HOLD_MS) return null
        val e = errorAt(h, heldAtMs, nowMs)
        return if (e <= limitM) h.copy(ce = e) else null
    }

    /** What the screen shows: the reportable position, otherwise the latest fix (with its own accuracy). */
    fun shown(nowMs: Long, limitM: Int): Fix? = reportable(nowMs, limitM) ?: latest

    fun clear() {
        held = null
        latest = null
    }

    private fun errorAt(f: Fix, takenAtMs: Long, nowMs: Long): Double {
        val drifting = (nowMs - takenAtMs - GRACE_MS).coerceAtLeast(0L) / 1000.0
        return (f.ce ?: Double.MAX_VALUE) + max(f.speedMps ?: 0.0, MIN_DRIFT_MPS) * drifting
    }

    companion object {
        /** The usual gap between fixes and their delivery: no decay within it. */
        const val GRACE_MS = 2_000L
        /** A position with no fresh fix is assumed to move at least this fast (walking). */
        const val MIN_DRIFT_MPS = 1.0
        /** Never reported once this old, however still it was. */
        const val MAX_HOLD_MS = 5 * 60_000L
    }
}
