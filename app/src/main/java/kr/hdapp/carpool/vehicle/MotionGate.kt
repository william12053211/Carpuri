package kr.hdapp.carpool.vehicle

/** A fresh moving speed overrides P; P can establish a safe state when speed is unavailable. */
object MotionGate {
    const val FRESH_MS = 3_000L

    fun isSafe(speed: Double?, speedAt: Long, parked: Boolean?, gearAt: Long, now: Long): Boolean {
        val freshSpeed = speed?.takeIf { it.isFinite() && speedAt >= 0 && now - speedAt in 0..FRESH_MS }
        if (freshSpeed != null) return freshSpeed == 0.0
        return parked == true && gearAt >= 0 && now - gearAt in 0..FRESH_MS
    }
}
