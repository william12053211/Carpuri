package kr.hdapp.carpool.core

import java.math.BigDecimal
import java.util.UUID

/** Owns locally reviewed facts. Sensor evidence alone never assigns a person's identity. */
class TripEngine(initial: AppSnapshot = AppSnapshot()) {
    // elapsedRealtime may restart after reboot. Preserve each event's clock session;
    // across restored engine sessions use persisted receipt order, never compare clocks.
    private val observationSessionId = UUID.randomUUID().toString()
    var snapshot: AppSnapshot = initial
        private set

    init { require(initial.schemaVersion == 1) { "저장 형식을 확인할 수 없습니다. 기록을 보존한 채 복구가 필요합니다." } }

    fun configure(name: String, rateText: String) {
        require(name.trim().isNotEmpty()) { "운전자 이름을 입력해주세요." }
        val rate = Inputs.rate(rateText)
        snapshot = snapshot.copy(settings = Settings(name.trim(), rate.toString()))
    }

    fun recordCandidate(
        id: String, seat: Seat, occupied: Boolean, observedAtMillis: Long,
        odometerKm: BigDecimal?, odometerReceivedAtMillis: Long, reason: String? = null,
        source: String = "UNSPECIFIED", doorCycleId: String? = null, rearOccupiedCountAfter: Int? = null
    ) {
        require(id.isNotBlank())
        require(rearOccupiedCountAfter == null || rearOccupiedCountAfter in 0..2) { "뒷좌석 점유 수를 확인해주세요." }
        require(doorCycleId == null || doorCycleId.isNotBlank()) { "문 개폐 사건 ID를 확인해주세요." }
        require(odometerKm == null || odometerKm.signum() >= 0) { "유효하지 않은 누적 거리입니다." }
        val old = snapshot.currentTrip
        if (old != null && old.phase != Phase.RECORDING) return // Frozen review/confirmed trip ignores later sensors.
        if (old == null && !occupied) return
        val trip = old ?: createTrip()
        val existing = trip.events.find { it.id == id }
        if (existing != null) {
            require(existing.seat == seat && existing.occupied == occupied && existing.observedAtMillis == observedAtMillis && sameKm(existing.observedKm, odometerKm) && existing.doorCycleId == doorCycleId && existing.rearOccupiedCountAfter == rearOccupiedCountAfter) { "동일 사건 ID에 서로 다른 신호가 수신되었습니다." }
            return
        }
        val initialOccupancy = reason.orEmpty().split(';').contains("INITIAL_OCCUPANCY")
        val reobservationDetail = "${seat.label} 점유 재관측: 시각 $observedAtMillis ms, 거리 ${odometerKm?.stripTrailingZeros()?.toPlainString() ?: "미확인"} km. 기존 기록을 보존하고 공백 중 승하차 확인을 요청했습니다."
        val previouslyReobserved = trip.audit.find { it.action == "REOBSERVE_OCCUPANCY" && it.targetId == id }
        if (previouslyReobserved != null) {
            require(occupied && initialOccupancy && previouslyReobserved.detail == reobservationDetail) { "동일 사건 ID에 서로 다른 신호가 수신되었습니다." }
            return
        }
        val latestSeatEvent = trip.events.lastOrNull { it.seat == seat }
        val openRide = trip.rides.any { !it.cancelled && it.seat == seat && it.endKm == null }
        // A fresh sensor baseline after a gap is not evidence of another boarding.
        // An observed empty seat, including a rejected/non-passenger exit, breaks this
        // episode. Retain the old facts, but never infer identity across the gap.
        if (occupied && initialOccupancy &&
            (latestSeatEvent?.occupied == true || latestSeatEvent == null && openRide)) {
            val message = "${seat.label}: 관측 공백 뒤 점유 상태를 다시 받았습니다. 기존 기록을 보존했지만 같은 사람인지 알 수 없으므로 공백 중 승하차를 검토해주세요."
            val issues = if (trip.issues.any { !it.resolved && it.message == message }) trip.issues
                else trip.issues + ReviewIssue(newId(), message)
            setTrip(log(trip.copy(issues = issues), "REOBSERVE_OCCUPANCY", id, reobservationDetail))
            return
        }
        val event = BoardingEvent(id, seat, occupied, observedAtMillis, odometerKm, odometerReceivedAtMillis, reason, source = source, observationSessionId = observationSessionId,
            doorCycleId = doorCycleId, rearOccupiedCountAfter = rearOccupiedCountAfter)
        val pendingBoarding = trip.events.any { (it.seat == seat || isRear(seat) && isRear(it.seat)) && it.occupied && it.status == EventStatus.PENDING }
        val rowRide = isRear(seat) && trip.rides.any { !it.cancelled && isRear(it.seat) && it.endKm == null }
        if (!occupied && !openRide && !rowRide && !pendingBoarding) {
            // Keep the real EMPTY transition so a later occupied baseline can start a
            // new episode. This observation is not a user-cancelled passenger exit.
            val observation = event.copy(status = EventStatus.CANCELLED,
                reason = listOfNotNull("NOT_A_PASSENGER_EXIT", reason).joinToString(";"))
            setTrip(log(trip.copy(events = trip.events + observation), "OBSERVE_EMPTY", id,
                "${seat.label} 빈 좌석 관측을 보존했습니다. 연결할 탑승 기록이 없어 하차 확인이나 비용 구간을 만들지 않았습니다."))
            return
        }
        setTrip(trip.copy(events = trip.events + event, startKm = minKm(trip.startKm, odometerKm)))
        resolveUnambiguousExits()
    }

    fun boardNew(eventId: String, name: String) {
        val trip = editable()
        val event = pendingEvent(trip, eventId, true)
        val normalizedName = PassengerNames.requireValid(name)
        val person = Person(newId(), normalizedName)
        attachRide(trip.copy(people = trip.people + person), event, person.id, "새 이용자 등록")
    }

    /** Read-only preflight for the registration dialog; no mileage is needed to show old detections. */
    fun blockingPendingEvents(eventId: String): List<BoardingEvent> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val event = trip.events.find { it.id == eventId && it.status == EventStatus.PENDING } ?: return emptyList()
        return blockingPendingEvents(trip, event)
    }

    /** Names only actual registered occupants who would prevent attaching this boarding. */
    fun blockingRiders(eventId: String): List<Person> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val event = trip.events.find { it.id == eventId && it.occupied && it.status == EventStatus.PENDING } ?: return emptyList()
        return blockingRiders(trip, event)
    }

    /** Exact original registration records for an explicitly confirmed correction in the UI. */
    fun blockingBoardingEvents(eventId: String): List<BoardingEvent> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val target = trip.events.find { it.id == eventId && it.occupied && it.status == EventStatus.PENDING } ?: return emptyList()
        val personIds = blockingRiders(trip, target).map { it.id }.toSet()
        val boardIds = trip.rides.filter { ride ->
            !ride.manual && !ride.cancelled && ride.personId in personIds && possiblyActiveAtEvent(trip, ride, target)
        }.mapNotNull { it.boardEventId }.toSet()
        return trip.events.filter { it.id in boardIds && it.occupied && it.status == EventStatus.CONFIRMED && it.personId in personIds }
    }

    fun dismissPendingEvent(eventId: String) = dismissPendingEvents(setOf(eventId))

    /** Explicitly reject unreviewed sensor evidence without changing any registered ride or its cost. */
    fun dismissPendingEvents(eventIds: Set<String>) {
        val trip = editable()
        if (eventIds.isEmpty()) return
        val requested = eventIds.map { id ->
            requireNotNull(trip.events.find { it.id == id }) { "넘길 감지를 찾을 수 없습니다. 화면을 다시 확인해주세요." }
        }
        require(requested.all { it.status == EventStatus.PENDING }) {
            "이미 사람·짐으로 확인한 기록은 오류 넘기기로 지울 수 없습니다. 해당 기록에서 수정해주세요."
        }
        require(trip.rides.none { !it.cancelled && (it.boardEventId in eventIds || it.exitEventId in eventIds) }) {
            "등록된 이용자와 연결된 기록입니다. 해당 탑승 기록에서 수정해주세요."
        }
        val rejected = trip.copy(events = trip.events.map { event ->
            if (event.id in eventIds) event.copy(status = EventStatus.CANCELLED,
                reason = addReason(event.reason, "DISMISSED_PENDING")) else event
        })
        // Empty observations following only rejected, never-registered boardings cannot
        // be passenger exits. Protect every known/possibly active ride, including missing km.
        val rejectedBoards = requested.filter { it.occupied }
        val orphanIds = rejected.events.filter { empty ->
            !empty.occupied && empty.status == EventStatus.PENDING &&
                rejectedBoards.any { board -> sameSeatGroup(board.seat, empty.seat) && eventPrecedes(rejected, board.id, empty.id) } &&
                !hasEarlierUnclassifiedBoarding(rejected, empty) &&
                rejected.rides.none { ride -> sameSeatGroup(ride.seat, empty.seat) && possiblyActiveAtEvent(rejected, ride, empty) }
        }.map { it.id }.toSet()
        val resolved = rejected.copy(events = rejected.events.map { event ->
            if (event.id in orphanIds) event.copy(status = EventStatus.CANCELLED,
                reason = addReason(event.reason, "NOT_A_PASSENGER_EXIT")) else event
        })
        var next = log(resolved, "DISMISS_PENDING", requested.joinToString(",") { it.id },
            "사용자가 미확인 감지 ${requested.size}건을 오류로 넘김; 등록된 사람·탑승 구간은 유지")
        if (orphanIds.isNotEmpty()) next = log(next, "DISMISS_ORPHAN_EMPTY", orphanIds.joinToString(","),
            "넘긴 미등록 탑승 감지 뒤의 빈 좌석 ${orphanIds.size}건을 함께 정리; 연결된 이용자 없음")
        setTrip(next)
        // A skipped rear detection must not implicitly choose an existing rear person's exit.
        resolveUnambiguousExits(frontOnly = true)
    }

    /** Either both explicit dismissals and the new registration succeed, or nothing changes. */
    fun boardNewAfterDismissing(eventId: String, name: String, eventIds: Set<String>) {
        val trip = editable()
        pendingEvent(trip, eventId, true)
        require(eventId !in eventIds) { "등록할 현재 탑승 감지는 넘길 수 없습니다." }
        val blockers = blockingPendingEvents(eventId).map { it.id }.toSet()
        require(eventIds.all { it in blockers }) { "이전에 남아 있는 미확인 감지만 선택해서 넘겨주세요." }
        val trial = TripEngine(snapshot)
        trial.dismissPendingEvents(eventIds)
        trial.boardNew(eventId, name)
        snapshot = trial.snapshot
    }

    fun reboard(eventId: String, personId: String) {
        val trip = editable()
        val event = pendingEvent(trip, eventId, true)
        require(trip.people.any { it.id == personId && !it.isDriver }) { "이번 운행 이용자를 선택해주세요." }
        val at = requireDistance(event)
        require(trip.rides.any { !it.cancelled && it.personId == personId && it.endKm != null && it.endKm <= at && (it.manual || it.exitEventId != null && eventPrecedes(trip, it.exitEventId, event.id)) }) { "하차를 직접 확인한 이용자만 재탑승할 수 있습니다." }
        require(trip.rides.none { !it.cancelled && it.personId == personId && activeAtEvent(trip, it, event) }) { "이미 탑승 중인 이용자입니다." }
        attachRide(trip, event, personId, "사용자가 재탑승 선택")
    }

    fun reboardCandidates(eventId: String): List<Person> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val event = trip.events.find { it.id == eventId && it.status == EventStatus.PENDING && it.occupied } ?: return emptyList()
        val km = event.effectiveKm ?: return emptyList()
        return trip.people.filter { person -> !person.isDriver &&
            trip.rides.any { !it.cancelled && it.personId == person.id && it.endKm != null && it.endKm <= km && (it.manual || it.exitEventId != null && eventPrecedes(trip, it.exitEventId, event.id)) } &&
            trip.rides.none { !it.cancelled && it.personId == person.id && activeAtEvent(trip, it, event) }
        }
    }

    fun markLuggage(eventId: String) {
        val trip = editable(); val event = pendingEvent(trip, eventId, true)
        setTrip(log(trip.copy(events = trip.events.map { if (it.id == event.id) it.copy(status = EventStatus.LUGGAGE) else it }), "LUGGAGE", event.id, "사용자가 짐 / 탑승자 아님으로 확인"))
        resolveUnambiguousExits()
    }

    /** Seat cards show the identity the user confirmed, never infer it from a sensor boolean. */
    fun seatRegistration(seat: Seat): SeatRegistration {
        val trip = snapshot.currentTrip ?: return SeatRegistration(seat, null, null, null)
        val ordered = trip.events.sortedWith { a, b ->
            if (a.id == b.id) 0 else if (eventPrecedes(trip, a.id, b.id)) -1 else 1
        }
        val pending = ordered.lastOrNull { it.seat == seat && it.status == EventStatus.PENDING }
        val pendingBoard = ordered.lastOrNull { it.seat == seat && it.occupied && it.status == EventStatus.PENDING }
        if (pendingBoard != null) return SeatRegistration(seat, pendingBoard, null, pending)

        val assigned = seatAssignments(trip, ordered)
        val ride = trip.rides.lastOrNull { !it.cancelled && it.exitEventId == null && it.personId == assigned[seat]?.first }
        val person = trip.people.firstOrNull { it.id == ride?.personId }
        val ownBoard = ordered.firstOrNull { it.id == ride?.takeUnless { it.manual }?.boardEventId }
        if (person != null && ownBoard != null) return SeatRegistration(seat, ownBoard, person, pending)

        // Keep corrections on the same card after a reviewed rear move. Markers refer to
        // existing review evidence; the original sensor seat/time/distance is never changed.
        val historical = ordered.mapNotNull { event ->
            val codes = event.reason.orEmpty().split(';')
            val correctedSeat = codes.lastOrNull { it.startsWith("REGISTRATION_SEAT:") }?.substringAfter(':')
            val locatedHere = if (event.status != EventStatus.CONFIRMED && correctedSeat != null)
                correctedSeat == seat.name else event.seat == seat
            if (!locatedHere || !event.occupied || event.source == "MANUAL" || "REAR_CONTINUATION" in codes ||
                trip.rides.any { !it.cancelled && it.boardEventId == event.id && it.exitEventId == null &&
                    assigned.entries.any { entry -> entry.value.first == it.personId && entry.key != seat } }) null
            else {
                val locationId = codes.lastOrNull { it.startsWith("REGISTRATION_SEAT_EVENT:") }?.substringAfter(':')
                val location = if (event.status != EventStatus.CONFIRMED && correctedSeat != null)
                    ordered.firstOrNull { it.id == locationId } ?: event else event
                location to event
            }
        }.maxByOrNull { ordered.indexOf(it.first) }?.second
        return SeatRegistration(seat, historical, null, pending)
    }

    /** Value = confirmed person and the event which established their latest position. */
    private fun seatAssignments(trip: Trip, ordered: List<BoardingEvent>): Map<Seat, Pair<String, String>> {
        val assigned = mutableMapOf<Seat, Pair<String, String>>()
        ordered.filter { it.status == EventStatus.CONFIRMED && it.personId != null }.forEach { event ->
            val personId = requireNotNull(event.personId)
            if (event.occupied) {
                // A cancelled ride can retain its original evidence, but must not occupy a card.
                if (trip.rides.any { !it.cancelled && it.personId == personId &&
                        (it.boardEventId == event.id || activeAtEvent(trip, it, event)) }) {
                    assigned.entries.removeAll { it.value.first == personId }
                    assigned[event.seat] = personId to event.id
                }
            } else {
                val previousSeat = assigned.entries.firstOrNull { it.value.first == personId }?.key
                val remaining = assigned[event.seat]?.first?.takeIf { it != personId }
                assigned.entries.removeAll { it.value.first == personId }
                assigned.remove(event.seat)
                // Selecting who left is also an explicit review of the remaining rear identity.
                if (isRear(event.seat) && previousSeat != null && remaining != null)
                    assigned[previousSeat] = remaining to event.id
            }
        }
        return assigned
    }

    /** Save a classification correction in one transaction, including its charge interval. */
    fun correctBoarding(eventId: String, status: EventStatus, name: String = "") {
        editable()
        require(status in setOf(EventStatus.CONFIRMED, EventStatus.LUGGAGE, EventStatus.CANCELLED)) {
            "사람·짐·오류 중 선택해주세요."
        }
        val trial = TripEngine(snapshot)
        trial.correctBoardingInPlace(eventId, status, name)
        snapshot = trial.snapshot
    }

    private fun correctBoardingInPlace(eventId: String, status: EventStatus, name: String) {
        val trip = editable()
        val event = requireNotNull(trip.events.find { it.id == eventId && it.occupied }) { "수정할 탑승 기록을 찾을 수 없습니다." }
        require(event.source != "MANUAL" && !event.reason.orEmpty().split(';').contains("REAR_CONTINUATION")) {
            "이용자의 원래 탑승 등록에서 수정해주세요."
        }
        val oldRide = trip.rides.singleOrNull { it.boardEventId == eventId }
        if (status == EventStatus.CONFIRMED) {
            val normalizedName = PassengerNames.requireValid(name)
            if (oldRide != null) {
                require(trip.people.any { it.id == oldRide.personId && !it.isDriver }) { "등록된 이용자를 찾을 수 없습니다." }
                if (!oldRide.cancelled && event.status == EventStatus.CONFIRMED) {
                    renamePerson(oldRide.personId, normalizedName)
                    return
                }
                require(blockingPendingEvents(trip, event).isEmpty()) { "이 탑승 이전에 남은 승하차를 먼저 확인해주세요." }
                val restored = trip.copy(
                    people = trip.people.map { if (it.id == oldRide.personId) it.copy(name = normalizedName) else it },
                    rides = trip.rides.map { if (it.id == oldRide.id) it.copy(cancelled = false,
                        endKm = it.endKm ?: if (trip.phase == Phase.REVIEW) trip.endKm else null) else it },
                    events = trip.events.map { e -> when {
                        e.id == eventId -> e.copy(status = EventStatus.CONFIRMED, personId = oldRide.personId)
                        e.id == oldRide.exitEventId -> e.copy(status = EventStatus.CONFIRMED, personId = oldRide.personId)
                        e.personId == oldRide.personId && e.reason.orEmpty().split(';').contains("CORRECTION_CONTINUATION:$eventId") ->
                            e.copy(status = EventStatus.CONFIRMED)
                        else -> e
                    } }
                )
                validateRides(restored)
                setTrip(log(restored, "CORRECT_BOARDING", eventId, "사람으로 수정; 기존 이용자와 최초 탑승·하차 거리 복원"))
            } else {
                setTrip(trip.copy(events = trip.events.map { if (it.id == eventId)
                    it.copy(status = EventStatus.PENDING, personId = null) else it }))
                boardNew(eventId, normalizedName)
            }
            restoreDiscardedEmptyAfter(eventId)
            resolveUnambiguousExits()
            return
        }

        val continuationIds = if (oldRide == null) emptySet() else trip.events.filter { e ->
            e.occupied && e.status == EventStatus.CONFIRMED && e.personId == oldRide.personId &&
                e.reason.orEmpty().split(';').contains("REAR_CONTINUATION") && activeAtEvent(trip, oldRide, e)
        }.map { it.id }.toSet()
        val ordered = trip.events.sortedWith { a, b ->
            if (a.id == b.id) 0 else if (eventPrecedes(trip, a.id, b.id)) -1 else 1
        }
        val location = seatAssignments(trip, ordered).entries.firstOrNull { it.value.first == oldRide?.personId }
        val correctionReason = if (location == null) event.reason else {
            val retained = event.reason.orEmpty().split(';').filter { it.isNotEmpty() &&
                !it.startsWith("REGISTRATION_SEAT:") && !it.startsWith("REGISTRATION_SEAT_EVENT:") }
            (retained + "REGISTRATION_SEAT:${location.key.name}" + "REGISTRATION_SEAT_EVENT:${location.value.second}").joinToString(";")
        }
        val next = trip.copy(
            rides = trip.rides.map { if (it.id == oldRide?.id) it.copy(cancelled = true) else it },
            events = trip.events.map { e -> when {
                e.id == eventId -> e.copy(status = status, reason = correctionReason)
                e.id == oldRide?.exitEventId -> e.copy(status = EventStatus.CANCELLED)
                e.id in continuationIds -> e.copy(status = EventStatus.CANCELLED,
                    reason = addReason(e.reason, "CORRECTION_CONTINUATION:$eventId"))
                else -> e
            } }
        )
        validateRides(next)
        setTrip(log(next, "CORRECT_BOARDING", eventId,
            if (status == EventStatus.LUGGAGE) "짐으로 수정; 해당 탑승 구간만 정산에서 제외" else "오류로 수정; 해당 탑승 구간만 정산에서 제외"))
        resolveUnambiguousExits()
    }

    /** A wrong non-person choice may have hidden the following real empty observation. */
    private fun restoreDiscardedEmptyAfter(eventId: String) {
        val trip = editable()
        val board = trip.events.first { it.id == eventId }
        val nextBoard = trip.events.filter { it.occupied && it.seat == board.seat && eventPrecedes(trip, board.id, it.id) }
            .minWithOrNull { a, b -> if (eventPrecedes(trip, a.id, b.id)) -1 else 1 }
        val restoreIds = trip.events.filter { e ->
            !e.occupied && (e.seat == board.seat || isRear(e.seat) && isRear(board.seat) && e.rearOccupiedCountAfter == 0) &&
                e.status == EventStatus.CANCELLED && e.personId == null &&
                e.reason.orEmpty().split(';').contains("NOT_A_PASSENGER_EXIT") &&
                !e.reason.orEmpty().split(';').contains("REAR_CONTINUATION") &&
                eventPrecedes(trip, board.id, e.id) && (nextBoard == null || eventPrecedes(trip, e.id, nextBoard.id))
        }.map { it.id }.toSet()
        if (restoreIds.isNotEmpty()) setTrip(log(trip.copy(events = trip.events.map { e ->
            if (e.id in restoreIds) e.copy(status = EventStatus.PENDING) else e
        }), "RESTORE_EMPTY_REVIEW", eventId, "사람으로 수정하여 뒤따른 빈 좌석의 하차 확인 복원; 원관측 거리 유지"))
    }

    /** Rear identity is a row-level decision; the vacated cushion does not identify a person. */
    fun exitCandidates(eventId: String): List<Person> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val event = trip.events.find { it.id == eventId && !it.occupied && it.status == EventStatus.PENDING } ?: return emptyList()
        if (event.effectiveKm == null || hasEarlierUnclassifiedBoarding(trip, event)) return emptyList()
        val ids = exitRides(trip, event).map { it.personId }.toSet()
        return trip.people.filter { it.id in ids }
    }

    fun confirmExit(eventId: String, personId: String? = null) {
        val trip = editable()
        val existing = trip.events.find { it.id == eventId } ?: error("사건을 찾을 수 없습니다.")
        if (!existing.occupied && existing.status == EventStatus.CONFIRMED) {
            require(personId == null || personId == existing.personId) { "이미 다른 이용자의 하차로 확인했습니다." }
            return
        }
        val event = pendingEvent(trip, eventId, false)
        require(!hasEarlierUnclassifiedBoarding(trip, event)) { "먼저 이 하차 이전에 감지된 탑승을 사람·짐·오류로 확인해주세요." }
        val choices = exitRides(trip, event)
        val ride = if (personId == null) {
            require(choices.size <= 1) { "뒷좌석에서 내린 이용자를 선택해주세요. 좌석 위치만으로는 구분할 수 없습니다." }
            choices.singleOrNull()
        } else choices.find { it.personId == personId }
        requireNotNull(ride) { "연결할 탑승 기록이 없습니다. 먼저 이전 탑승을 확인하거나 감지를 취소해주세요." }
        val km = requireDistance(event)
        val next = trip.copy(
            rides = trip.rides.map { if (it.id == ride.id) it.copy(endKm = km, exitEventId = event.id) else it },
            events = trip.events.map { if (it.id == event.id) it.copy(status = EventStatus.CONFIRMED, personId = ride.personId) else it }
        )
        validateRides(next)
        setTrip(log(next, "EXIT", event.id, "사용자가 ${trip.people.first { it.id == ride.personId }.name} 하차 확인; 최초 관측 거리 $km km"))
        resolveUnambiguousExits()
    }

    /** An explicitly selected existing rider can stay aboard while changing rear cushions. */
    fun continuingRearCandidates(eventId: String): List<Person> {
        val trip = snapshot.currentTrip ?: return emptyList()
        val event = trip.events.find { it.id == eventId && it.occupied && isRear(it.seat) && it.status == EventStatus.PENDING } ?: return emptyList()
        if (event.doorCycleId == null || event.effectiveKm == null) return emptyList()
        val ids = trip.rides.filter { !it.cancelled && isRear(it.seat) && activeAtEvent(trip, it, event) }.map { it.personId }.toSet()
        return trip.people.filter { it.id in ids }
    }

    fun continueRearRide(eventId: String, personId: String) {
        val trip = editable(); val event = pendingEvent(trip, eventId, true)
        require(isRear(event.seat) && continuingRearCandidates(eventId).any { it.id == personId }) { "계속 타고 있던 뒷좌석 이용자를 선택해주세요." }
        require(event.doorCycleId != null) { "연결할 문 개폐 기록이 없습니다. 이전 승하차를 검토해주세요." }
        val sameCycle = trip.events.filter { it.doorCycleId == event.doorCycleId && isRear(it.seat) }
        val earlierExits = sameCycle.filter { !it.occupied && it.status == EventStatus.PENDING }
        require(earlierExits.all { exitRides(trip, it).map { r -> r.personId }.toSet() == setOf(personId) }) { "먼저 같은 문 개폐에서 실제로 내린 이용자를 선택해주세요." }
        val changed = trip.events.map { e ->
            when {
                e.id == event.id -> e.copy(status = EventStatus.CONFIRMED, personId = personId,
                    reason = addReason(e.reason, "REAR_CONTINUATION"))
                e in earlierExits -> e.copy(status = EventStatus.CANCELLED, reason = addReason(e.reason, "REAR_CONTINUATION"))
                else -> e
            }
        }
        setTrip(log(trip.copy(events = changed), "CONTINUE_REAR", event.id, "사용자가 기존 이용자의 계속 탑승 확인; 요금 구간을 끊거나 새로 만들지 않음"))
    }

    fun cancelEvent(eventId: String) {
        val trip = editable(); val event = trip.events.find { it.id == eventId } ?: error("사건을 찾을 수 없습니다.")
        if (event.status == EventStatus.CANCELLED) return
        val nextRides = trip.rides.map { ride ->
            when {
                ride.boardEventId == eventId -> ride.copy(cancelled = true)
                ride.exitEventId == eventId -> ride.copy(endKm = if (trip.phase == Phase.REVIEW) trip.endKm else null, exitEventId = null)
                else -> ride
            }
        }
        val next = trip.copy(rides = nextRides, events = trip.events.map { if (it.id == eventId) it.copy(status = EventStatus.CANCELLED) else it })
        validateRides(next)
        setTrip(log(next, "CANCEL_EVENT", eventId, "사용자가 감지 취소 / 계속 탑승을 확인"))
        if (event.occupied) resolveUnambiguousExits()
    }

    fun correctEventDistance(eventId: String, km: BigDecimal, reason: String) {
        require(km.signum() >= 0 && reason.isNotBlank()) { "수정 거리와 사유를 입력해주세요." }
        val trip = editable(); val event = trip.events.find { it.id == eventId } ?: error("사건을 찾을 수 없습니다.")
        require(trip.endKm == null || km <= trip.endKm) { "종료 거리 이후로 수정할 수 없습니다." }
        val rides = trip.rides.map { ride ->
            when {
                ride.boardEventId == eventId -> ride.copy(startKm = km)
                ride.exitEventId == eventId -> ride.copy(endKm = km)
                else -> ride
            }
        }
        val next = trip.copy(startKm = minKm(trip.startKm, km), rides = rides, events = trip.events.map { if (it.id == eventId) it.copy(correctedKm = km) else it })
        validateRides(next)
        setTrip(log(next, "CORRECT_EVENT", eventId, "원관측 ${event.observedKm}; 수정 $km km; $reason"))
        resolveUnambiguousExits(frontOnly = true)
    }

    fun endTrip(endKm: BigDecimal) {
        require(endKm.signum() >= 0) { "유효한 종료 거리를 확인해주세요." }
        val trip = editable()
        if (trip.phase == Phase.REVIEW) return
        val relevantEvents = trip.events.filter { it.status == EventStatus.PENDING || it.status == EventStatus.CONFIRMED }
        val relevantDistances = relevantEvents.mapNotNull { it.effectiveKm } + trip.rides.filterNot { it.cancelled }.map { it.startKm }
        require(relevantDistances.all { it <= endKm }) { "종료 거리가 기존 사건 거리보다 작습니다. 거리 오류를 수정해주세요." }
        // Rejected evidence remains in events/audit, but must not set the payable trip boundary.
        val reviewedStart = relevantDistances.minOrNull() ?: endKm
        val next = trip.copy(phase = Phase.REVIEW, startKm = reviewedStart, endKm = endKm, rides = trip.rides.map { if (!it.cancelled && it.endKm == null) it.copy(endKm = endKm) else it })
        validateRides(next)
        setTrip(log(next, "END", trip.id, "종료 거리 $endKm km 고정"))
    }

    fun updateRide(rideId: String, startKm: BigDecimal, endKm: BigDecimal, reason: String) {
        val trip = review()
        require(reason.isNotBlank()) { "수정 사유를 입력해주세요." }
        val old = trip.rides.find { it.id == rideId } ?: error("탑승 기록을 찾을 수 없습니다.")
        val next = trip.copy(startKm = minKm(trip.startKm, startKm), rides = trip.rides.map { if (it.id == rideId) it.copy(startKm = startKm, endKm = endKm) else it })
        validateRides(next)
        setTrip(log(next, "CORRECT_RIDE", rideId, "${old.startKm}~${old.endKm} → $startKm~$endKm km; $reason"))
    }

    fun cancelRide(rideId: String, reason: String) {
        val trip = review()
        require(reason.isNotBlank()) { "취소 사유를 입력해주세요." }
        require(trip.rides.any { it.id == rideId }) { "탑승 기록을 찾을 수 없습니다." }
        setTrip(log(trip.copy(rides = trip.rides.map { if (it.id == rideId) it.copy(cancelled = true) else it }), "CANCEL_RIDE", rideId, reason))
    }

    /** Adds an explicitly reviewed gap record. -1 marks unavailable sensor times, never a fabricated observation. */
    fun addManualRide(id: String, personId: String?, name: String, seat: Seat, startKm: BigDecimal, endKm: BigDecimal, reason: String) {
        val trip = review()
        require(id.isNotBlank() && reason.isNotBlank()) { "누락 기록의 확인 사유를 입력해주세요." }
        val existing = trip.rides.find { it.id == id }
        if (existing != null) {
            require(existing.manual && existing.seat == seat && sameKm(existing.startKm, startKm) && sameKm(existing.endKm, endKm) && (personId == null || personId == existing.personId)) { "동일 기록 ID에 서로 다른 내용이 있습니다." }
            return
        }
        val person = if (personId != null) {
            requireNotNull(trip.people.find { it.id == personId && !it.isDriver }) { "이번 운행의 동승자를 선택해주세요." }
        } else {
            Person(newId(), PassengerNames.requireValid(name))
        }
        require(startKm.signum() >= 0 && endKm >= startKm && trip.endKm != null && endKm <= trip.endKm) { "종료 거리 안에서 올바른 탑승 구간을 입력해주세요." }
        val board = BoardingEvent("manual-board:$id", seat, true, -1L, null, -1L, reason, EventStatus.CONFIRMED, person.id, startKm, "MANUAL", "manual:$id")
        val exit = BoardingEvent("manual-exit:$id", seat, false, -1L, null, -1L, reason, EventStatus.CONFIRMED, person.id, endKm, "MANUAL", "manual:$id")
        val ride = Ride(id, person.id, seat, startKm, endKm, board.id, exit.id, manual = true)
        val next = trip.copy(
            people = if (personId == null) trip.people + person else trip.people,
            startKm = minKm(trip.startKm, startKm), events = trip.events + listOf(board, exit), rides = trip.rides + ride
        )
        validateRides(next)
        setTrip(log(next, "MANUAL_RIDE", id, "센서 기록 없음; 사용자가 $startKm~$endKm km 확인: $reason"))
    }

    fun renamePerson(personId: String, name: String) {
        val normalizedName = PassengerNames.requireValid(name)
        val trip = editable(); require(trip.people.any { it.id == personId })
        val old = trip.people.first { it.id == personId }.name
        setTrip(log(trip.copy(people = trip.people.map { if (it.id == personId) it.copy(name = normalizedName) else it }), "RENAME", personId, "$old → $normalizedName"))
    }

    fun updateTripRate(rateText: String, reason: String) {
        val trip = review(); val rate = Inputs.rate(rateText)
        require(reason.isNotBlank()) { "단가 수정 사유를 입력해주세요." }
        setTrip(log(trip.copy(rateWonPerKm = rate), "RATE", trip.id, "${trip.rateWonPerKm} → $rate 원/km; $reason"))
    }

    fun upsertToll(id: String, amountText: String, participantIds: Set<String>, memo: String = "") {
        val trip = review(); val amount = Inputs.toll(amountText)
        require(id.isNotBlank())
        require(participantIds.contains(trip.driverId)) { "운전자는 통행료 분담에 반드시 포함됩니다." }
        require(participantIds.all { personId -> trip.people.any { it.id == personId } }) { "이번 운행 이용자를 선택해주세요." }
        val toll = Toll(id, amount, participantIds.toSet(), memo)
        if (trip.tolls.find { it.id == id } == toll) return
        val list = if (trip.tolls.any { it.id == id }) trip.tolls.map { if (it.id == id) toll else it } else trip.tolls + toll
        setTrip(log(trip.copy(tolls = list, tollStatus = TollStatus.ITEMS), "TOLL", id, "$amount 원 / ${participantIds.size}명"))
    }

    fun removeToll(id: String) {
        val trip = review(); val list = trip.tolls.filterNot { it.id == id }
        if (trip.tolls.size == list.size) return
        setTrip(log(trip.copy(tolls = list, tollStatus = if (list.isEmpty()) TollStatus.UNREVIEWED else TollStatus.ITEMS), "REMOVE_TOLL", id, "사용자가 통행료 삭제"))
    }

    fun setNoTolls() {
        val trip = review()
        require(trip.tolls.isEmpty()) { "입력한 통행료를 먼저 삭제해주세요." }
        setTrip(log(trip.copy(tollStatus = TollStatus.NONE), "NO_TOLLS", trip.id, "사용자가 통행료 없음을 선택"))
    }

    fun addIssue(reason: String): String? {
        val trip = snapshot.currentTrip ?: return null
        if (trip.phase != Phase.RECORDING) return null // Later movement cannot alter finished review/results.
        val existing = trip.issues.find { !it.resolved && it.message == reason }
        if (existing != null) return existing.id
        val issue = ReviewIssue(newId(), reason)
        setTrip(trip.copy(issues = trip.issues + issue))
        return issue.id
    }

    fun resolveIssue(id: String, note: String) {
        val trip = review(); require(note.isNotBlank()) { "기록 검토 결과를 입력해주세요." }
        require(trip.issues.any { it.id == id })
        setTrip(log(trip.copy(issues = trip.issues.map { if (it.id == id) it.copy(resolved = true, resolution = note) else it }), "RESOLVE_ISSUE", id, note))
    }

    fun preview(): SettlementResult = SettlementCalculator.calculate(requireNotNull(snapshot.currentTrip) { "운행 기록이 없습니다." })

    fun confirm(): SettlementResult {
        val trip = requireNotNull(snapshot.currentTrip) { "운행 기록이 없습니다." }
        if (trip.phase == Phase.CONFIRMED) return requireNotNull(trip.result)
        val result = preview()
        val confirmed = log(trip.copy(phase = Phase.CONFIRMED, result = result), "CONFIRM", trip.id, "총 ${result.totalWon}원 확정")
        snapshot = snapshot.copy(currentTrip = confirmed, history = snapshot.history.filterNot { it.id == confirmed.id } + confirmed)
        return result
    }

    fun newTrip() {
        require(snapshot.currentTrip == null || snapshot.currentTrip?.phase == Phase.CONFIRMED) { "현재 운행을 먼저 정산하거나 명시적으로 삭제해주세요." }
        snapshot = snapshot.copy(currentTrip = null)
    }

    /** Call only after explicit user confirmation. Never used for lifecycle cleanup. */
    fun discardCurrentTrip() { snapshot = snapshot.copy(currentTrip = null) }

    fun deleteHistory(tripId: String) {
        require(snapshot.currentTrip?.id != tripId || snapshot.currentTrip?.phase == Phase.CONFIRMED) { "진행 중인 운행은 이력에서 삭제할 수 없습니다." }
        snapshot = snapshot.copy(history = snapshot.history.filterNot { it.id == tripId }, currentTrip = if (snapshot.currentTrip?.id == tripId) null else snapshot.currentTrip)
    }

    private fun createTrip(): Trip {
        val settings = snapshot.settings
        require(settings.driverName.isNotBlank()) { "운전자와 단가를 먼저 설정해주세요." }
        val driver = Person(newId(), settings.driverName, true)
        return Trip(newId(), Phase.RECORDING, driver.id, Inputs.rate(settings.rateWonPerKm), listOf(driver))
    }

    private fun attachRide(trip: Trip, event: BoardingEvent, personId: String, action: String) {
        val pending = blockingPendingEvents(trip, event)
        require(pending.isEmpty()) {
            "이전에 남은 감지 ${pending.size}건이 있습니다. 실제 승하차를 확인하거나 오류로 넘긴 뒤 등록해주세요."
        }
        val km = requireDistance(event)
        require(trip.rides.none { !it.cancelled && it.personId == personId && activeAtEvent(trip, it, event) }) { "이미 탑승 중인 이용자입니다." }
        val registered = blockingRiders(trip, event)
        require(registered.isEmpty()) {
            "${registered.joinToString(", ") { it.name }}님의 탑승 기록이 남아 있습니다. 실제 하차를 확인하거나 이번 감지를 오류로 넘겨주세요."
        }
        val ride = Ride(newId(), personId, event.seat, km, if (trip.phase == Phase.REVIEW) trip.endKm else null, event.id)
        val next = trip.copy(rides = trip.rides + ride, events = trip.events.map { if (it.id == event.id) it.copy(status = EventStatus.CONFIRMED, personId = personId) else it })
        validateRides(next)
        setTrip(log(next, "BOARD", event.id, "$action; 후보 거리 $km km 사용; ${event.reason.orEmpty()}"))
        resolveUnambiguousExits()
    }

    private fun validateRides(trip: Trip) {
        val rides = trip.rides.filterNot { it.cancelled }
        rides.forEach { ride ->
            require(ride.startKm.signum() >= 0 && (ride.endKm == null || ride.endKm >= ride.startKm)) { "탑승 시작·종료 거리를 확인해주세요." }
            require(trip.endKm == null || ride.endKm != null && ride.endKm <= trip.endKm) { "종료 거리 이후의 탑승 기록입니다." }
        }
        rides.filter { isRear(it.seat) }.forEach { ride ->
            val event = trip.events.find { it.id == ride.boardEventId }
            val simultaneous = rides.count { other ->
                if (!isRear(other.seat) || other.id == ride.id) false
                else if (event != null && !ride.manual) activeAtEvent(trip, other, event)
                else other.startKm <= ride.startKm && (other.endKm == null || other.endKm > ride.startKm)
            }
            require(simultaneous < 2) { "뒷좌석은 동시에 두 명까지만 탑승할 수 있습니다. 이전 하차를 먼저 확인해주세요." }
        }
        for (i in rides.indices) for (j in i + 1 until rides.size) {
            val a = rides[i]; val b = rides[j]
            val overlap = (a.endKm == null || b.startKm < a.endKm) && (b.endKm == null || a.startKm < b.endKm)
            val simultaneousOpen = a.endKm == null && b.endKm == null
            val chronologyOverlap = !a.manual && !b.manual && a.boardEventId != null && b.boardEventId != null &&
                (a.exitEventId == null || eventPrecedes(trip, b.boardEventId, a.exitEventId)) &&
                (b.exitEventId == null || eventPrecedes(trip, a.boardEventId, b.exitEventId))
            require(!(overlap || simultaneousOpen || chronologyOverlap) || (a.personId != b.personId && (a.seat != b.seat || isRear(a.seat) && isRear(b.seat)))) { "같은 사람 또는 좌석에 겹치는 탑승 기록이 있습니다. 이전 하차를 먼저 확인해주세요." }
        }
    }

    private fun isRear(seat: Seat) = seat != Seat.FRONT_PASSENGER
    private fun addReason(reason: String?, code: String): String = listOfNotNull(reason, code).joinToString(";")

    private fun sameSeatGroup(a: Seat, b: Seat) = a == b || isRear(a) && isRear(b)

    private fun blockingPendingEvents(trip: Trip, event: BoardingEvent): List<BoardingEvent> = trip.events.filter { prior ->
        prior.status == EventStatus.PENDING && eventPrecedes(trip, prior.id, event.id) && sameSeatGroup(prior.seat, event.seat)
    }.sortedWith { a, b -> if (eventPrecedes(trip, a.id, b.id)) -1 else 1 }

    private fun blockingRiders(trip: Trip, event: BoardingEvent): List<Person> {
        val active = trip.rides.filter { sameSeatGroup(it.seat, event.seat) && possiblyActiveAtEvent(trip, it, event) }
        val ids = if (!isRear(event.seat) || active.size >= 2) active.map { it.personId }.toSet()
            else {
                val assigned = rearSeatAssignmentsBefore(trip, event)[event.seat]
                active.filter { it.personId == assigned }.map { it.personId }.toSet()
            }
        return trip.people.filter { it.id in ids }
    }

    /** Missing mileage cannot be used as proof that a registered passenger has disappeared. */
    private fun possiblyActiveAtEvent(trip: Trip, ride: Ride, event: BoardingEvent): Boolean {
        if (ride.cancelled) return false
        if (!ride.manual && ride.boardEventId != null) {
            return eventPrecedes(trip, ride.boardEventId, event.id) &&
                (ride.exitEventId == null || !eventPrecedes(trip, ride.exitEventId, event.id))
        }
        val km = event.effectiveKm ?: return true
        return ride.startKm <= km && (ride.endKm == null || ride.endKm > km)
    }

    private fun hasEarlierUnclassifiedBoarding(trip: Trip, event: BoardingEvent): Boolean = trip.events.any {
        it.occupied && it.status == EventStatus.PENDING && eventPrecedes(trip, it.id, event.id) &&
            (it.seat == event.seat || isRear(it.seat) && isRear(event.seat))
    }

    private fun exitRides(trip: Trip, event: BoardingEvent): List<Ride> {
        val km = event.effectiveKm ?: return emptyList()
        return trip.rides.filter { !it.cancelled && (if (isRear(event.seat)) isRear(it.seat) else it.seat == event.seat) &&
            it.startKm <= km && activeAtEvent(trip, it, event) && (it.endKm == null || it.exitEventId == null && it.endKm >= km) }
    }

    /** Replay only confirmed identities; a physical seat transition never silently chooses a person. */
    private fun rearSeatAssignmentsBefore(trip: Trip, target: BoardingEvent): Map<Seat, String> {
        val assigned = mutableMapOf<Seat, String>()
        trip.events.filter { isRear(it.seat) && it.status == EventStatus.CONFIRMED && it.personId != null && eventPrecedes(trip, it.id, target.id) }
            .sortedWith { a, b -> if (eventPrecedes(trip, a.id, b.id)) -1 else 1 }.forEach { e ->
            val person = requireNotNull(e.personId)
            if (e.occupied) {
                assigned.entries.removeAll { it.value == person }
                assigned[e.seat] = person
            } else {
                val departedSeat = assigned.entries.firstOrNull { it.value == person }?.key
                val remainingOnVacated = assigned[e.seat]?.takeIf { it != person }
                assigned.entries.removeAll { it.value == person }
                assigned.remove(e.seat)
                if (departedSeat != null && remainingOnVacated != null) assigned[departedSeat] = remainingOnVacated
            }
        }
        return assigned
    }

    /** Apply the new front-seat rule to persisted observations; existing rear reviews stay intact. */
    fun reconcileAutomaticExits() {
        if (snapshot.currentTrip == null || snapshot.currentTrip?.phase == Phase.CONFIRMED) return
        val trial = TripEngine(snapshot)
        trial.resolveUnambiguousExits(frontOnly = true)
        snapshot = trial.snapshot
    }

    private fun resolveUnambiguousExits(frontOnly: Boolean = false) {
        while (true) {
            val trip = snapshot.currentTrip ?: return
            if (trip.phase == Phase.CONFIRMED) return
            val nonPassenger = trip.events.firstOrNull { e ->
                (!frontOnly || e.seat == Seat.FRONT_PASSENGER) &&
                    !e.occupied && e.status == EventStatus.PENDING && e.effectiveKm != null &&
                    e.doorCycleId != null && (e.seat == Seat.FRONT_PASSENGER || e.rearOccupiedCountAfter != null) &&
                    !hasEarlierUnclassifiedBoarding(trip, e) && exitRides(trip, e).isEmpty()
            }
            if (nonPassenger != null) {
                setTrip(log(trip.copy(events = trip.events.map { if (it.id == nonPassenger.id) it.copy(status = EventStatus.CANCELLED,
                    reason = addReason(it.reason, "NOT_A_PASSENGER_EXIT")) else it }), "OBSERVE_EMPTY", nonPassenger.id, "짐·오류로 확인된 점유 뒤 빈 좌석; 요금 구간 없음"))
                continue
            }
            val event = trip.events.firstOrNull { e ->
                (!frontOnly || e.seat == Seat.FRONT_PASSENGER) &&
                    !e.occupied && e.status == EventStatus.PENDING && e.effectiveKm != null &&
                    e.doorCycleId != null &&
                    (if (isRear(e.seat)) e.rearOccupiedCountAfter == 0 else reliableFrontExit(trip, e)) &&
                    !hasEarlierUnclassifiedBoarding(trip, e) && exitRides(trip, e).size == 1 &&
                    trip.events.none { prior -> !prior.occupied && sameSeatGroup(prior.seat, e.seat) &&
                        prior.status == EventStatus.PENDING && eventPrecedes(trip, prior.id, e.id) }
            } ?: return
            val ride = exitRides(trip, event).single()
            val next = trip.copy(
                rides = trip.rides.map { if (it.id == ride.id) it.copy(endKm = requireDistance(event), exitEventId = event.id) else it },
                events = trip.events.map { if (it.id == event.id) it.copy(status = EventStatus.CONFIRMED, personId = ride.personId) else it }
            )
            validateRides(next)
            val front = event.seat == Seat.FRONT_PASSENGER
            setTrip(log(next, if (front) "AUTO_FRONT_EXIT" else "AUTO_REAR_EXIT", event.id,
                if (front) "조수석 문 개폐 뒤 빈 좌석이 안정적으로 확인되어 등록 이용자의 하차를 자동 연결; 원관측 거리 유지"
                else "문 개폐가 끝난 뒤 뒷좌석이 모두 비었고 당시 이용자가 한 명이어서 자동 하차; 원관측 거리 유지"))
        }
    }


    /** The detector supplies a cycle only after a complete related door cycle and stable EMPTY.
     * Do not infer a front passenger's identity across a restored observation session. */
    private fun reliableFrontExit(trip: Trip, event: BoardingEvent): Boolean {
        if (event.reason.orEmpty().split(';').any { it in setOf(
                "OBSERVATION_GAP", "SEAT_SIGNAL_LOST", "DOOR_SIGNAL_UNKNOWN", "NO_RELATED_DOOR",
                "CHANGE_BEFORE_CORRELATION", "INITIAL_OCCUPANCY") }) return false
        val ride = exitRides(trip, event).singleOrNull() ?: return false
        val board = trip.events.firstOrNull { it.id == ride.boardEventId } ?: return false
        return !ride.manual && board.observationSessionId == event.observationSessionId
    }

    private fun editable(): Trip = requireNotNull(snapshot.currentTrip) { "운행 기록이 없습니다." }.also { require(it.phase != Phase.CONFIRMED) { "확정한 정산은 변경할 수 없습니다." } }
    private fun review(): Trip = editable().also { require(it.phase == Phase.REVIEW) { "운행 종료 후 검토 화면에서 처리해주세요." } }
    private fun pendingEvent(trip: Trip, id: String, occupied: Boolean): BoardingEvent {
        val event = trip.events.find { it.id == id } ?: error("사건을 찾을 수 없습니다.")
        require(event.status == EventStatus.PENDING && event.occupied == occupied) { "이미 처리됐거나 종류가 다른 승하차입니다." }
        return event
    }
    private fun requireDistance(event: BoardingEvent): BigDecimal = requireNotNull(event.effectiveKm) { "당시 누적 거리가 없습니다. 거리를 확인하여 수정해주세요." }
    private fun activeAtEvent(trip: Trip, ride: Ride, event: BoardingEvent): Boolean {
        if (ride.manual) return ride.startKm <= requireDistance(event) && (ride.endKm == null || ride.endKm > requireDistance(event))
        val board = ride.boardEventId ?: return ride.startKm <= requireDistance(event) && (ride.endKm == null || ride.endKm > requireDistance(event))
        return eventPrecedes(trip, board, event.id) && (ride.exitEventId == null || !eventPrecedes(trip, ride.exitEventId, event.id))
    }
    private fun eventPrecedes(trip: Trip, earlierId: String, laterId: String): Boolean {
        val earlier = trip.events.find { it.id == earlierId } ?: return false
        val later = trip.events.find { it.id == laterId } ?: return false
        if (earlier.observationSessionId != later.observationSessionId) return trip.events.indexOf(earlier) < trip.events.indexOf(later)
        return earlier.observedAtMillis < later.observedAtMillis || earlier.observedAtMillis == later.observedAtMillis && trip.events.indexOf(earlier) < trip.events.indexOf(later)
    }
    private fun setTrip(trip: Trip) { snapshot = snapshot.copy(currentTrip = trip.copy(result = null)) }
    private fun log(trip: Trip, action: String, id: String, detail: String): Trip = trip.copy(audit = trip.audit + AuditEntry(action, id, detail))
    private fun minKm(a: BigDecimal?, b: BigDecimal?): BigDecimal? = when { a == null -> b; b == null -> a; else -> a.min(b) }
    private fun sameKm(a: BigDecimal?, b: BigDecimal?): Boolean = if (a == null || b == null) a == b else a.compareTo(b) == 0
    private fun newId(): String = UUID.randomUUID().toString()
}
