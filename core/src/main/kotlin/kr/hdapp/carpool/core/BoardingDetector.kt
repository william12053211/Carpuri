package kr.hdapp.carpool.core

import java.math.BigDecimal
import java.util.UUID

/**
 * Pure, single-thread-confined receipt-time detector. The caller supplies elapsed-realtime
 * milliseconds. Motion never suspends collection; input safety is enforced by the UI gate.
 * Canonical door identifiers group evidence: the passenger front door supports the front
 * passenger seat; either rear door supports either rear seat. Occupancy selects the seat.
 * A complete observed open/close cycle supports changes while open or up to five seconds
 * after closing. Three-second stability is separate from that five-second onset window.
 * Rear changes are finalized together eight seconds after the last related door closes,
 * preserving intermediate evidence and final occupancy count for identity reconciliation.
 * A candidate is provisional evidence to classify later, never an identified person.
 *
 * Before forwarding odometerKm, the adapter must validate its unit, freshness and continuity.
 * Null means unavailable, never zero. SDK timestamps are not mixed with this receipt clock.
 * This object is deliberately rebuilt after a process/service gap; persist candidates and
 * record the coverage gap in the trip engine, then obtain fresh door/seat baselines.
 */
class BoardingDetector(private val sessionId: String = UUID.randomUUID().toString()) {
    enum class Occupancy { OCCUPIED, EMPTY, UNKNOWN }

    data class Candidate(
        val id: String,
        val seat: Seat,
        val occupied: Boolean,
        val observedAtMillis: Long,
        val odometerKm: BigDecimal?,
        val odometerReceivedAtMillis: Long,
        val correlated: Boolean,
        val reason: String?,
        val doorCycleId: String? = null,
        val rearOccupiedCountAfter: Int? = null
    )

    private data class Pending(
        val seat: Seat,
        val occupied: Boolean,
        val observedAt: Long,
        val odometer: BigDecimal?,
        val odometerAt: Long,
        val initial: Boolean,
        var doorEvidenceLost: Boolean = false,
        var emitted: Boolean = false
    )

    private data class SeatState(
        var raw: Occupancy = Occupancy.UNKNOWN,
        var stable: Occupancy = Occupancy.UNKNOWN,
        var needsBaseline: Boolean = true,
        var unknownReported: Boolean = false,
        var rawChangedAt: Long = -1L,
        var pending: Pending? = null
    )

    private data class DoorCycle(val id: String, val doorId: String, val openedAt: Long, val closedAt: Long)
    private data class OpenCycle(val id: String, val doorId: String, val openedAt: Long)

    private val seats = Seat.values().associateWith { SeatState() }
    private val doors = mutableMapOf<String, Boolean>()
    private val unknownDoors = mutableSetOf<String>()
    private val openedDoors = mutableMapOf<String, Long>()
    private val openCycles = mutableMapOf<String, OpenCycle>()
    private val doorCycles = mutableListOf<DoorCycle>()
    private val deferred = mutableListOf<Pending>()
    private val output = mutableListOf<Candidate>()
    private val issues = linkedSetOf<String>()
    private var clock = -1L
    private var sequence = 0L
    private var cycleSequence = 0L

    /** The first callback for a door establishes its state; only subsequent changes count. */
    @Suppress("UNUSED_PARAMETER")
    fun onDoor(doorId: String, open: Boolean, atMillis: Long, stopped: Boolean) {
        if (!acceptTime(atMillis)) return
        if (doorId.isBlank()) {
            issues.add("INVALID_DOOR_ID")
            return
        }
        unknownDoors.remove(doorId)
        val previous = doors.put(doorId, open)
        if (previous != null && previous != open && supportedSeats(doorId).isNotEmpty()) {
            val group = groupDoor(doorId)
            if (open) {
                openedDoors[doorId] = atMillis
                if (openCycles[group] == null) {
                    // Overlapping/reopened rear doors belong to the same unresolved stop.
                    val previousCycle = doorCycles.lastOrNull {
                        group == DOOR_REAR_LEFT && it.doorId == group &&
                            elapsed(atMillis, it.closedAt) <= ASSOCIATION_MILLIS
                    }
                    if (previousCycle != null) {
                        doorCycles.remove(previousCycle)
                        openCycles[group] = OpenCycle(previousCycle.id, group, previousCycle.openedAt)
                    } else openCycles[group] = OpenCycle("$sessionId-door-${++cycleSequence}", group, atMillis)
                }
            } else {
                openedDoors.remove(doorId)
                if (doors.none { (id, isOpen) -> isOpen && groupDoor(id) == group }) {
                    openCycles.remove(group)?.let { cycle ->
                        doorCycles.add(DoorCycle(cycle.id, group, cycle.openedAt, atMillis))
                    }
                }
            }
        }
        evaluate(atMillis)
    }

    /**
     * A missing/error door reading is not a closed door. Correlation history is cleared
     * conservatively within the affected front/rear group. Unrelated groups retain their
     * evidence. Affected seat changes keep their time/distance, but require user review.
     * The next valid callback for this door is a baseline, not an invented transition.
     */
    fun onDoorUnknown(doorId: String, atMillis: Long) {
        if (!acceptTime(atMillis)) return
        if (doorId.isBlank()) {
            issues.add("INVALID_DOOR_ID")
            return
        }
        doors.remove(doorId)
        val affectedSeats = supportedSeats(doorId)
        openedDoors.keys.removeAll { id -> supportedSeats(id).any { it in affectedSeats } }
        openCycles.keys.removeAll { id -> supportedSeats(id).any { it in affectedSeats } }
        doorCycles.removeAll { cycle -> supportedSeats(cycle.doorId).any { it in affectedSeats } }
        affectedSeats.forEach { seats.getValue(it).pending?.doorEvidenceLost = true }
        deferred.filter { it.seat in affectedSeats }.forEach { it.doorEvidenceLost = true }
        if (unknownDoors.add(doorId)) issues.add("DOOR_SIGNAL_UNKNOWN:$doorId")
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSeat(
        seat: Seat,
        occupancy: Occupancy,
        atMillis: Long,
        odometerKm: BigDecimal?,
        odometerReceivedAtMillis: Long,
        stopped: Boolean
    ) {
        if (!acceptTime(atMillis)) return
        // Finish evidence that already remained stable before this new transition.
        evaluate(atMillis)
        val state = seats.getValue(seat)
        if (occupancy == Occupancy.UNKNOWN) {
            val pending = state.pending
            if (pending != null && !pending.emitted && elapsed(atMillis, pending.observedAt) >= STABILITY_MILLIS) {
                emit(pending, false, "SEAT_SIGNAL_LOST")
            }
            state.raw = Occupancy.UNKNOWN
            state.rawChangedAt = atMillis
            state.stable = Occupancy.UNKNOWN
            state.needsBaseline = true
            state.pending = null
            if (!state.unknownReported) issues.add("SEAT_SIGNAL_UNKNOWN:${seat.name}")
            state.unknownReported = true
            return
        }
        state.unknownReported = false
        if (state.raw == occupancy && !state.needsBaseline) {
            evaluate(atMillis)
            return
        }

        val initial = state.needsBaseline
        val earlier = state.pending
        if (earlier != null && !earlier.emitted && elapsed(atMillis, earlier.observedAt) >= STABILITY_MILLIS) {
            if (earlier.seat != Seat.FRONT_PASSENGER && (matchingCycle(earlier) != null || awaitingClose(earlier))) {
                deferred.add(earlier)
            } else emit(earlier, false, "CHANGE_BEFORE_CORRELATION")
        }
        state.raw = occupancy
        state.rawChangedAt = atMillis
        state.needsBaseline = false
        // A brief departure from the committed stable value is only sensor bounce.
        if (!initial && state.stable == occupancy) {
            state.pending = null
            return
        }
        // An initial empty reading is a baseline, never a newly observed exit.
        if ((initial || state.stable == Occupancy.UNKNOWN) && occupancy == Occupancy.EMPTY) {
            state.stable = Occupancy.EMPTY
            state.pending = null
            return
        }
        val distanceValid = odometerKm != null && odometerKm.signum() >= 0 &&
            odometerReceivedAtMillis >= 0 && odometerReceivedAtMillis <= atMillis
        state.pending = Pending(
            seat, occupancy == Occupancy.OCCUPIED, atMillis,
            if (distanceValid) odometerKm else null,
            if (distanceValid) odometerReceivedAtMillis else -1L,
            initial
        )
    }

    /** Kept as a compatibility hook; motion does not erase observed door/seat evidence. */
    @Suppress("UNUSED_PARAMETER")
    fun onMotion(stopped: Boolean, nowMillis: Long) {
        if (!acceptTime(nowMillis)) return
        evaluate(nowMillis)
    }

    /** Clears all sensor baselines and emits a coverage issue instead of fabricating events. */
    fun onDisconnected(nowMillis: Long) {
        if (!acceptTime(nowMillis)) return
        (deferred + seats.values.mapNotNull { it.pending }).sortedBy { it.observedAt }.forEach { pending ->
            if (!pending.emitted && elapsed(nowMillis, pending.observedAt) >= STABILITY_MILLIS) {
                emit(pending, false, "OBSERVATION_GAP")
            }
        }
        seats.values.forEach { state ->
            state.raw = Occupancy.UNKNOWN
            state.stable = Occupancy.UNKNOWN
            state.needsBaseline = true
            state.unknownReported = false
            state.pending = null
        }
        doors.clear()
        unknownDoors.clear()
        openedDoors.clear()
        openCycles.clear()
        doorCycles.clear()
        deferred.clear()
        issues.add("OBSERVATION_GAP")
    }

    /** Call periodically while signals remain valid, even if no callbacks change. */
    fun tick(nowMillis: Long) {
        if (!acceptTime(nowMillis)) return
        evaluate(nowMillis)
    }

    fun drainCandidates(): List<Candidate> = output.toList().also { output.clear() }

    /** Adapters should surface these as trip-quality/review items, not as seat changes. */
    fun drainIssues(): List<String> = issues.toList().also { issues.clear() }

    private fun acceptTime(now: Long): Boolean {
        if (now < 0 || now < clock) {
            issues.add("OUT_OF_ORDER_SIGNAL")
            return false
        }
        clock = now
        return true
    }

    private fun evaluate(now: Long) {
        // Commit stability for both rear sensors before taking their shared final count.
        seats.values.forEach { state ->
            val pending = state.pending ?: return@forEach
            if (elapsed(now, pending.observedAt) >= STABILITY_MILLIS) {
                state.stable = if (pending.occupied) Occupancy.OCCUPIED else Occupancy.EMPTY
            }
        }
        (deferred + seats.values.mapNotNull { it.pending }).sortedBy { it.observedAt }.forEach { pending ->
            if (pending.emitted || elapsed(now, pending.observedAt) < STABILITY_MILLIS) return@forEach
            val cycle = matchingCycle(pending)
            val rear = pending.seat != Seat.FRONT_PASSENGER
            when {
                pending.doorEvidenceLost -> emit(pending, false, "DOOR_SIGNAL_UNKNOWN")
                cycle != null && rear && elapsed(now, cycle.closedAt) < REAR_FINALIZE_MILLIS -> Unit
                cycle != null -> {
                    val count = if (rear) rearCount(cycle, now) else null
                    emit(pending, true, if (rear && count == null) "REAR_GROUP_UNSTABLE" else null, cycle.id, count)
                }
                awaitingClose(pending) -> Unit // Preserve original observation until related doors close.
                pending.initial -> emit(pending, false, "INITIAL_OCCUPANCY")
                elapsed(now, pending.observedAt) > ASSOCIATION_MILLIS -> emit(pending, false, "NO_RELATED_DOOR")
            }
        }
        deferred.removeAll { it.emitted }
        doorCycles.removeAll { elapsed(now, it.closedAt) > ASSOCIATION_MILLIS + STABILITY_MILLIS }
    }

    private fun matchingCycle(pending: Pending): DoorCycle? = doorCycles.lastOrNull { cycle ->
        pending.seat in supportedSeats(cycle.doorId) && pending.observedAt >= cycle.openedAt &&
            (pending.observedAt <= cycle.closedAt || pending.observedAt - cycle.closedAt <= ASSOCIATION_MILLIS)
    }

    private fun awaitingClose(pending: Pending): Boolean = openCycles.values.any { cycle ->
        pending.seat in supportedSeats(cycle.doorId) && pending.observedAt >= cycle.openedAt
    }

    private fun rearCount(cycle: DoorCycle, now: Long): Int? {
        val group = listOf(seats.getValue(Seat.REAR_LEFT), seats.getValue(Seat.REAR_RIGHT))
        if (group.any { it.raw == Occupancy.UNKNOWN || it.stable != it.raw ||
                it.rawChangedAt > cycle.closedAt + ASSOCIATION_MILLIS ||
                elapsed(now, it.rawChangedAt) < STABILITY_MILLIS }) return null
        return group.count { it.raw == Occupancy.OCCUPIED }
    }

    private fun emit(pending: Pending, correlated: Boolean, reason: String?, cycleId: String? = null, rearCount: Int? = null) {
        if (pending.emitted) return
        pending.emitted = true
        val finalReason = listOfNotNull(reason, if (pending.odometer == null) "MISSING_DISTANCE" else null)
            .joinToString(";").ifEmpty { null }
        output.add(Candidate(
            "$sessionId-${++sequence}", pending.seat, pending.occupied, pending.observedAt,
            pending.odometer, pending.odometerAt, correlated, finalReason, cycleId, rearCount
        ))
    }

    private fun elapsed(now: Long, before: Long): Long = if (now >= before) now - before else 0L

    private fun groupDoor(doorId: String): String = when (doorId) {
        DOOR_REAR_LEFT, DOOR_REAR_RIGHT -> DOOR_REAR_LEFT
        else -> doorId
    }

    private fun supportedSeats(doorId: String): Set<Seat> = when (doorId) {
        DOOR_FRONT_PASSENGER -> setOf(Seat.FRONT_PASSENGER)
        DOOR_REAR_LEFT, DOOR_REAR_RIGHT -> setOf(Seat.REAR_LEFT, Seat.REAR_RIGHT)
        else -> emptySet() // Driver, unsupported or unrecognized doors cannot identify passenger boarding.
    }

    companion object {
        // Matches the typed DoorValue names mapped by the Pleos adapter.
        const val DOOR_FRONT_DRIVER = "ROW_1_LEFT"
        const val DOOR_FRONT_PASSENGER = "ROW_1_RIGHT"
        const val DOOR_REAR_LEFT = "ROW_2_LEFT"
        const val DOOR_REAR_RIGHT = "ROW_2_RIGHT"
        const val ASSOCIATION_MILLIS = 5_000L
        const val STABILITY_MILLIS = 3_000L
        const val REAR_FINALIZE_MILLIS = ASSOCIATION_MILLIS + STABILITY_MILLIS
    }
}
