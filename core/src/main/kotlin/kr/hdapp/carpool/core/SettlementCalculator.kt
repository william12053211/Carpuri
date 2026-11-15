package kr.hdapp.carpool.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Pure calculation. Decimal distances are exact; concurrent vehicle shares use twelfths; toll shares use an exact common denominator. */
object SettlementCalculator {
    fun calculate(trip: Trip): SettlementResult {
        require(trip.phase != Phase.RECORDING) { "운행을 종료한 뒤 정산해주세요." }
        require(trip.events.none { it.status == EventStatus.PENDING }) { "미확인 승하차를 먼저 정리해주세요." }
        require(trip.issues.none { !it.resolved }) { "거리 오류와 관측 공백을 먼저 검토해주세요." }
        require(trip.rateWonPerKm.signum() >= 0) { "단가가 올바르지 않습니다." }
        val people = trip.people.associateBy { it.id }
        require(people.size == trip.people.size) { "이용자 식별자가 중복되었습니다." }
        require(trip.people.count { it.isDriver } == 1 && people[trip.driverId]?.isDriver == true) { "운전자가 필요합니다." }
        require(trip.people.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "이용자 이름을 확인해주세요." }
        val end = requireNotNull(trip.endKm) { "종료 거리를 확인해주세요." }
        require(end.signum() >= 0)
        trip.startKm?.let { require(it.signum() >= 0 && it <= end) { "운행 시작·종료 거리를 확인해주세요." } }
        require(trip.rides.map { it.id }.distinct().size == trip.rides.size) { "탑승 구간 ID가 중복되었습니다." }
        val rides = trip.rides.filterNot { it.cancelled }
        rides.forEach { ride ->
            require(people[ride.personId]?.isDriver == false) { "동승자 정보를 확인해주세요." }
            val rideEnd = requireNotNull(ride.endKm) { "종료하지 않은 탑승 구간이 있습니다." }
            require(ride.startKm.signum() >= 0 && rideEnd >= ride.startKm && rideEnd <= end) { "탑승 구간 거리가 올바르지 않습니다." }
            trip.startKm?.let { require(ride.startKm >= it) { "운행 시작 이전의 탑승 기록입니다." } }
        }
        for (i in rides.indices) for (j in i + 1 until rides.size) {
            val a = rides[i]; val b = rides[j]
            val overlaps = a.startKm < b.endKm!! && b.startKm < a.endKm!!
            require(!overlaps || (a.personId != b.personId && (a.seat != b.seat || a.seat != Seat.FRONT_PASSENGER && b.seat != Seat.FRONT_PASSENGER))) { "같은 사람 또는 좌석의 탑승 구간이 겹칩니다." }
        }
        val boundaries = rides.flatMap { listOf(it.startKm, it.endKm!!) }.sorted().fold(mutableListOf<BigDecimal>()) { acc, km ->
            if (acc.lastOrNull()?.compareTo(km) != 0) acc.add(km)
            acc
        }
        val segments = mutableListOf<SharedSegment>()
        val vehicleTwelfths = people.keys.associateWith { BigDecimal.ZERO }.toMutableMap()
        val distance = people.keys.associateWith { BigDecimal.ZERO }.toMutableMap()
        var sharedKm = BigDecimal.ZERO
        var totalExact = BigDecimal.ZERO
        for (index in 0 until boundaries.lastIndex) {
            val start = boundaries[index]; val finish = boundaries[index + 1]
            val activeRides = rides.filter { it.startKm <= start && it.endKm!! >= finish && it.endKm > it.startKm }
            require(activeRides.count { it.seat != Seat.FRONT_PASSENGER } <= 2) { "뒷좌석 동시 이용자는 두 명을 넘을 수 없습니다." }
            val riders = activeRides.map { it.personId }.toSet()
            if (riders.isEmpty()) continue // Driver-only gaps never enter shared cost.
            require(riders.size <= 3) { "동시에 탑승하는 동승자는 최대 3명입니다." }
            val members = riders + trip.driverId
            val km = finish - start
            val cost = km.multiply(BigDecimal(trip.rateWonPerKm))
            val shareTwelfths = cost.multiply(BigDecimal(12 / members.size))
            members.forEach { id ->
                vehicleTwelfths[id] = vehicleTwelfths.getValue(id) + shareTwelfths
                distance[id] = distance.getValue(id) + km
            }
            totalExact += cost; sharedKm += km
            segments.add(SharedSegment(start, finish, members))
        }
        require(trip.tollStatus != TollStatus.UNREVIEWED) { "통행료를 입력하거나 '통행료 없음'을 선택해주세요." }
        require((trip.tollStatus == TollStatus.NONE && trip.tolls.isEmpty()) || (trip.tollStatus == TollStatus.ITEMS && trip.tolls.isNotEmpty())) { "통행료 선택 상태를 확인해주세요." }
        require(trip.tolls.map { it.id }.distinct().size == trip.tolls.size) { "통행료 항목 ID가 중복되었습니다." }
        // A trip can have more than three distinct riders as people get on/off.
        // Keep exact fractions even when a toll is split among 5+ trip members.
        val tollDenominator = trip.tolls.fold(BigInteger.ONE) { common, toll ->
            val size = BigInteger.valueOf(toll.participantIds.size.toLong())
            require(size.signum() > 0) { "통행료 분담 대상을 선택해주세요." }
            common.divide(common.gcd(size)).multiply(size)
        }
        val tollUnits = people.keys.associateWith { BigInteger.ZERO }.toMutableMap()
        var totalToll = BigInteger.ZERO
        trip.tolls.forEach { toll ->
            require(toll.amountWon.signum() > 0 && toll.id.isNotBlank()) { "통행료 금액을 확인해주세요." }
            require(toll.participantIds.contains(trip.driverId) && toll.participantIds.all { people.containsKey(it) }) { "통행료 대상에는 운전자와 이번 운행 이용자만 지정해주세요." }
            val shareUnits = toll.amountWon.multiply(tollDenominator.divide(BigInteger.valueOf(toll.participantIds.size.toLong())))
            toll.participantIds.forEach { tollUnits[it] = tollUnits.getValue(it) + shareUnits }
            totalToll += toll.amountWon
        }
        val totalVehicle = totalExact.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
        val vehicle = mutableMapOf<String, BigInteger>()
        val tolls = mutableMapOf<String, BigInteger>()
        people.keys.filter { it != trip.driverId }.forEach { id ->
            vehicle[id] = vehicleTwelfths.getValue(id).divide(BigDecimal(12), 0, RoundingMode.DOWN).toBigIntegerExact()
            tolls[id] = tollUnits.getValue(id).divide(tollDenominator)
        }
        vehicle[trip.driverId] = totalVehicle - vehicle.values.fold(BigInteger.ZERO, BigInteger::add)
        tolls[trip.driverId] = totalToll - tolls.values.fold(BigInteger.ZERO, BigInteger::add)
        val lines = trip.people.map { person ->
            val sum = vehicle.getValue(person.id) + tolls.getValue(person.id)
            PersonSettlement(person.id, person.name, person.isDriver, distance.getValue(person.id), vehicle.getValue(person.id), tolls.getValue(person.id), sum, if (person.isDriver) BigInteger.ZERO else sum)
        }
        val total = totalVehicle + totalToll
        check(lines.fold(BigInteger.ZERO) { acc, line -> acc + line.totalWon } == total)
        return SettlementResult(trip.id, trip.rateWonPerKm, lines, segments, sharedKm, totalVehicle, totalToll, total, lines.fold(BigInteger.ZERO) { acc, line -> acc + line.sendToDriverWon })
    }
}
