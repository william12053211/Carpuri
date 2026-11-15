package kr.hdapp.carpool.vehicle

import kr.hdapp.carpool.core.Seat
import java.math.BigDecimal

/** All callbacks are delivered on the main thread. Times are receipt elapsedRealtime ms. */
interface VehicleListener {
    fun onDoor(id: String, open: Boolean?, receivedAt: Long)
    fun onSeat(seat: Seat, occupied: Boolean?, receivedAt: Long)
    fun onOdometer(km: BigDecimal?, receivedAt: Long)
    fun onSpeed(metersPerSecond: Double?, receivedAt: Long)
    fun onParked(parked: Boolean?, receivedAt: Long) {}
    fun onUx(keyboardAllowed: Boolean?, editingAllowed: Boolean?)
    fun onDisplayRestrictions(restricted: Boolean, maxStringLength: Int, maxContentItems: Int) {}
    fun onProblem(message: String)
    fun onConnection(connected: Boolean, detail: String) {}
}

interface VehicleSource {
    val label: String
    fun start(listener: VehicleListener)
    fun stop()
}
