package kr.hdapp.carpool.core

/** One-time, idempotent cleanup of obsolete UI data in an editable current trip. */
object SavedRecordCleanup {
    private const val OBSOLETE_DISTANCE_JUMP_MESSAGE =
        "누적 주행거리가 급증했습니다. 거리 단위·누락 여부를 확인하고 구간을 검토해주세요."

    fun apply(snapshot: AppSnapshot): AppSnapshot {
        val trip = snapshot.currentTrip ?: return snapshot
        if (trip.phase == Phase.CONFIRMED) return snapshot
        val peopleWithUsage = trip.rides.filterNot { it.cancelled }.map { it.personId }.toSet() +
            trip.tolls.flatMap { it.participantIds }
        // A discarded, non-billable identity must not create a new name-confirmation blocker.
        val people = trip.people.map { person ->
            if (!person.isDriver && PassengerNames.isKnownPlaceholder(person.name))
                person.copy(name = if (person.id in peopleWithUsage) "" else "미등록 이용자") else person
        }
        val issues = trip.issues.filterNot { it.message == OBSOLETE_DISTANCE_JUMP_MESSAGE }
        if (people == trip.people && issues == trip.issues) return snapshot
        // Identity, original seat evidence, distances, rides, tolls and all history remain intact.
        return snapshot.copy(currentTrip = trip.copy(people = people, issues = issues))
    }
}
