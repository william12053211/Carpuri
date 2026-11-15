package kr.hdapp.carpool.vehicle

import kr.hdapp.carpool.core.Seat

/** Live sensor values only: passenger registration never supplies a seat's physical occupancy. */
internal class SeatSignalState {
    val values = Seat.values().associateWith { null as Boolean? }.toMutableMap()
    val receivedAt = Seat.values().associateWith { -1L }.toMutableMap()

    fun accept(seat: Seat, value: Boolean?, at: Long): Boolean {
        if (at < (receivedAt[seat] ?: -1L)) return false
        values[seat] = value
        receivedAt[seat] = at
        return true
    }

    fun expire(now: Long, freshnessMs: Long): List<Seat> = Seat.values().filter { seat ->
        values[seat] != null && now - (receivedAt[seat] ?: -1L) !in 0..freshnessMs
    }.onEach { values[it] = null }

    fun clear() {
        Seat.values().forEach { values[it] = null; receivedAt[it] = -1L }
    }
}
