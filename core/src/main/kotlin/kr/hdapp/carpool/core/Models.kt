package kr.hdapp.carpool.core

import java.io.Serializable
import java.math.BigDecimal
import java.math.BigInteger

enum class Seat(val label: String) { FRONT_PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽") }
enum class Phase { RECORDING, REVIEW, CONFIRMED }
enum class EventStatus { PENDING, CONFIRMED, LUGGAGE, CANCELLED }
enum class TollStatus { UNREVIEWED, NONE, ITEMS }

data class Settings(val driverName: String = "", val rateWonPerKm: String = "") : Serializable
data class Person(val id: String, val name: String, val isDriver: Boolean = false) : Serializable
data class BoardingEvent(
    val id: String, val seat: Seat, val occupied: Boolean,
    val observedAtMillis: Long, val observedKm: BigDecimal?, val odometerReceivedAtMillis: Long,
    val reason: String? = null, val status: EventStatus = EventStatus.PENDING,
    val personId: String? = null, val correctedKm: BigDecimal? = null,
    val source: String = "UNSPECIFIED", val observationSessionId: String = "",
    val doorCycleId: String? = null, val rearOccupiedCountAfter: Int? = null
) : Serializable {
    companion object { private const val serialVersionUID: Long = 4935864523296795641L }
    val effectiveKm: BigDecimal? get() = correctedKm ?: observedKm
}
data class Ride(
    val id: String, val personId: String, val seat: Seat,
    val startKm: BigDecimal, val endKm: BigDecimal? = null,
    val boardEventId: String? = null, val exitEventId: String? = null,
    val cancelled: Boolean = false, val manual: Boolean = false
) : Serializable
data class Toll(val id: String, val amountWon: BigInteger, val participantIds: Set<String>, val memo: String = "") : Serializable
data class ReviewIssue(val id: String, val message: String, val resolved: Boolean = false, val resolution: String = "") : Serializable
data class AuditEntry(val action: String, val targetId: String, val detail: String, val wallTimeMillis: Long = System.currentTimeMillis()) : Serializable
data class Trip(
    val id: String, val phase: Phase, val driverId: String, val rateWonPerKm: BigInteger,
    val people: List<Person>, val events: List<BoardingEvent> = emptyList(), val rides: List<Ride> = emptyList(),
    val startKm: BigDecimal? = null, val endKm: BigDecimal? = null,
    val tollStatus: TollStatus = TollStatus.UNREVIEWED, val tolls: List<Toll> = emptyList(),
    val issues: List<ReviewIssue> = emptyList(), val audit: List<AuditEntry> = emptyList(),
    val result: SettlementResult? = null
) : Serializable
data class AppSnapshot(
    val schemaVersion: Int = 1, val settings: Settings = Settings(), val currentTrip: Trip? = null,
    val history: List<Trip> = emptyList()
) : Serializable
data class SharedSegment(val startKm: BigDecimal, val endKm: BigDecimal, val participantIds: Set<String>) : Serializable {
    val distanceKm: BigDecimal get() = endKm.subtract(startKm)
}
data class PersonSettlement(
    val personId: String, val name: String, val isDriver: Boolean, val distanceKm: BigDecimal,
    val vehicleWon: BigInteger, val tollWon: BigInteger, val totalWon: BigInteger, val sendToDriverWon: BigInteger
) : Serializable
data class SettlementResult(
    val tripId: String, val rateWonPerKm: BigInteger, val people: List<PersonSettlement>,
    val segments: List<SharedSegment>, val sharedDistanceKm: BigDecimal,
    val totalVehicleWon: BigInteger, val totalTollWon: BigInteger, val totalWon: BigInteger,
    val receivableWon: BigInteger
) : Serializable

object Inputs {
    fun rate(text: String): BigInteger = integer(text, false)
    fun toll(text: String): BigInteger = integer(text, true)
    private fun integer(text: String, positive: Boolean): BigInteger {
        val value = text.trim()
        require(value.matches(Regex("[0-9]+"))) { "금액은 빈칸 없이 0 이상의 정수로 입력해주세요." }
        return BigInteger(value).also { require(!positive || it.signum() > 0) { "통행료는 0원보다 커야 합니다." } }
    }
    fun distance(text: String): BigDecimal {
        val value = text.trim()
        require(value.matches(Regex("[0-9]+(\\.[0-9]+)?"))) { "누적 거리는 0 이상의 숫자로 입력해주세요." }
        return BigDecimal(value)
    }
}
