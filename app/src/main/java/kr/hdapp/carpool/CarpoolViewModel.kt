package kr.hdapp.carpool

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import androidx.core.content.ContextCompat
import kr.hdapp.carpool.core.*
import kr.hdapp.carpool.vehicle.PleosVehicleSource
import kr.hdapp.carpool.vehicle.VehicleListener
import kr.hdapp.carpool.vehicle.MotionGate
import kr.hdapp.carpool.vehicle.SeatSignalState
import java.math.BigDecimal
import java.util.UUID

/** One application-owned controller; the observation service, not an Activity, owns its lifetime. */
class CarpoolViewModel(private val application: Application) : VehicleListener {
    // Authorization lives only in this process. A stored flag must not restart observation at boot.
    var observationRequested by mutableStateOf(false); private set
    private var appOpenedInProcess = false
    var observing by mutableStateOf(false); private set
    private var serviceStartPending = false
    private val store = TripStore(application)
    private var engine = TripEngine()
    private var detector = BoardingDetector()
    private val source = PleosVehicleSource(application)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var speedAt = -1L
    private var odometerAt = -1L
    private var speed: Double? = null
    private var physicalSpeed: Double? = null
    private var physicalSpeedAt = -1L
    private var parked: Boolean? = null
    private var gearAt = -1L
    var currentParked by mutableStateOf<Boolean?>(null); private set
    private var lastGoodOdometer: BigDecimal? = null
    private var realKeyboardAllowed: Boolean? = null
    private var realEditingAllowed: Boolean? = null
    private var simSpeed = 0.0
    private val simDoors = mutableMapOf<String, Boolean>()
    private var lastStopped = false
    private val seatSignals = SeatSignalState()
    var seatReceivedAt by mutableStateOf(Seat.values().associateWith { -1L }); private set
    var observationNowMillis by mutableStateOf(0L); private set

    var snapshot by mutableStateOf(AppSnapshot()); private set
    var status by mutableStateOf("차량 서비스 연결 준비 중"); private set
    var error by mutableStateOf<String?>(null); private set
    var storageFailure by mutableStateOf(false); private set
    var simulated by mutableStateOf(false); private set
    var currentOdometer by mutableStateOf<BigDecimal?>(null); private set
    var currentSpeed by mutableStateOf<Double?>(null); private set
    var seats by mutableStateOf(Seat.values().associateWith { null as Boolean? }); private set
    var canEdit by mutableStateOf(false); private set
    var canType by mutableStateOf(false); private set
    var canUseDeveloperTools by mutableStateOf(false); private set
    private var platformDisplayRestricted = true
    var compactDisplay by mutableStateOf(true); private set
    var displayMaxStringLength by mutableStateOf(0); private set
    var displayMaxItems by mutableStateOf(0); private set
    var inputReason by mutableStateOf("차량 정지·플랫폼 제한 확인 중"); private set
    var diagnostics by mutableStateOf(emptyList<String>()); private set

    init {
        try {
            store.load()?.let { restored ->
                val cleaned = SavedRecordCleanup.apply(restored)
                engine = TripEngine(cleaned)
                engine.reconcileAutomaticExits()
                simulated = restored.currentTrip?.events?.any { it.source == "SIMULATION" } == true
                if (restored.currentTrip?.phase == Phase.RECORDING) {
                    engine.addIssue("앱 복원 전 관측 공백이 있습니다. 승하차·누적 거리를 확인하고 필요한 기록을 수정해주세요.")
                }
                if (engine.snapshot != restored) store.save(engine.snapshot)
                snapshot = engine.snapshot
            }
        } catch (failure: Exception) {
            storageFailure = true
            error = "저장 기록을 읽지 못했습니다. 기존 파일은 보존했습니다. ${failure.message.orEmpty()}"
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.elapsedRealtime()
            if (simulated) { speed = simSpeed; speedAt = now; odometerAt = now }
            observationNowMillis = now
            if (!simulated) {
                seatSignals.expire(now, SEAT_FRESH_MS).forEach { seat ->
                    seats = seatSignals.values.toMap()
                    detector.onSeat(seat, BoardingDetector.Occupancy.UNKNOWN, now,
                        validDistance(now), odometerAt, isStopped(now))
                }
            }
            updateGates(now)
            detector.onMotion(isStopped(now), now)
            detector.tick(now)
            drainDetector()
            handler.postDelayed(this, 250L)
        }
    }

    /** The first visible app opening starts this session; returning after a pause respects that pause. */
    fun onAppOpened() {
        if (!appOpenedInProcess) start() else ensureObservationStarted()
    }

    /** Called only from a visible Activity or a user action. */
    fun start() {
        appOpenedInProcess = true
        observationRequested = true
        ensureObservationStarted()
    }

    fun ensureObservationStarted() {
        if (!observationRequested || observing || serviceStartPending) return
        serviceStartPending = true
        try {
            ContextCompat.startForegroundService(application,
                Intent(application, VehicleObservationService::class.java).setAction(VehicleObservationService.ACTION_START))
        } catch (failure: Exception) {
            observationStartFailed(failure.message.orEmpty())
        }
    }

    fun stop() {
        observationRequested = false
        application.stopService(Intent(application, VehicleObservationService::class.java))
        stopObserving()
    }

    internal fun observationStartFailed(detail: String) {
        serviceStartPending = false
        observing = false
        error = "백그라운드 관측을 시작하지 못했습니다. 앱에서 관측 재개를 눌러주세요. $detail"
        status = "관측 서비스 시작 실패"
    }

    internal fun startObserving() {
        serviceStartPending = false
        if (!observationRequested || started) return
        started = true
        observing = true
        if (simulated) initializeSimulationDoors(SystemClock.elapsedRealtime())
        source.start(this)
        handler.post(ticker)
    }

    internal fun stopObserving() {
        serviceStartPending = false
        observing = false
        if (!started) return
        started = false
        handler.removeCallbacks(ticker)
        source.stop()
        if (snapshot.currentTrip?.phase == Phase.RECORDING) {
            mutate(false) { addIssue("차량 관측 서비스가 중단되었습니다. 공백 중 승하차 여부와 이용 구간을 검토해주세요.") }
        }
        detector.onDisconnected(SystemClock.elapsedRealtime())
        drainDetector()
        detector = BoardingDetector()
        status = "관측 일시 중지"
        physicalSpeed = null; physicalSpeedAt = -1L; parked = null; gearAt = -1L
        speed = null; speedAt = -1L; odometerAt = -1L
        currentSpeed = null; currentOdometer = null
        clearSeatObservations()
        realKeyboardAllowed = null; realEditingAllowed = null
        platformDisplayRestricted = true; displayMaxStringLength = 0; displayMaxItems = 0
        updateGates(SystemClock.elapsedRealtime())
    }

    fun dismissError() { error = null }
    fun blockingPendingEvents(eventId: String): List<BoardingEvent> = runCatching { engine.blockingPendingEvents(eventId) }.getOrDefault(emptyList())
    fun seatRegistration(seat: Seat): SeatRegistration = engine.seatRegistration(seat)
    fun blockingRiders(eventId: String): List<Person> = runCatching { engine.blockingRiders(eventId) }.getOrDefault(emptyList())
    fun blockingBoardingEvents(eventId: String): List<BoardingEvent> = runCatching { engine.blockingBoardingEvents(eventId) }.getOrDefault(emptyList())
    fun continuingRearCandidates(eventId: String): List<Person> = runCatching { engine.continuingRearCandidates(eventId) }.getOrDefault(emptyList())
    fun exitCandidates(eventId: String): List<Person> = runCatching { engine.exitCandidates(eventId) }.getOrDefault(emptyList())
    fun reboardCandidates(eventId: String): List<Person> = runCatching { engine.reboardCandidates(eventId) }.getOrDefault(emptyList())

    fun mutate(needsTyping: Boolean = false, action: TripEngine.() -> Unit): Boolean {
        if (storageFailure) { error = "저장 오류를 해결하기 전에는 기록을 변경할 수 없습니다."; return false }
        val before = engine.snapshot
        var saving = false
        return try {
            engine.action()
            saving = true
            store.save(engine.snapshot)
            snapshot = engine.snapshot
            true
        } catch (failure: Exception) {
            engine = TripEngine(before)
            snapshot = before
            if (saving) {
                storageFailure = true
                error = "기록을 저장하지 못해 변경을 중단했습니다. 기존 저장 파일은 보존했습니다. 앱을 다시 열어 기록 공백을 검토해주세요. ${failure.message.orEmpty()}"
            } else error = failure.message ?: "처리하지 못했습니다. 입력을 확인해주세요."
            false
        }
    }

    /** All user changes pass through this gate; sensor ingestion uses mutate directly. */
    fun userAction(typing: Boolean = false, action: TripEngine.() -> Unit): Boolean {
        updateGates(SystemClock.elapsedRealtime())
        if (!(if (typing) canType else canEdit)) { error = inputReason; return false }
        return mutate(typing, action).also { success -> if (success) error = null }
    }

    fun configure(name: String, rate: String): Boolean {
        val firstSetup = snapshot.settings.driverName.isBlank()
        val saved = userAction(true) { configure(name, rate) }
        if (saved && firstSetup) resetDetection()
        return saved
    }
    fun endTrip(): Boolean {
        val distance = validDistance(SystemClock.elapsedRealtime())
        if (distance == null) { error = "현재 유효한 누적 거리가 없습니다. 연결과 거리 값을 확인해주세요."; return false }
        return userAction { endTrip(distance) }
    }

    private fun physicalInteractionSafe(now: Long): Boolean =
        MotionGate.isSafe(physicalSpeed, physicalSpeedAt, parked, gearAt, now)

    private fun isStopped(now: Long): Boolean = started &&
        MotionGate.isSafe(speed, speedAt, if (simulated) null else parked, gearAt, now) &&
        (!simulated || physicalInteractionSafe(now))

    private fun updateGates(now: Long) {
        val stopped = isStopped(now)
        if (odometerAt < 0 || now - odometerAt !in 0..ODOMETER_FRESH_MS) currentOdometer = null
        currentSpeed = if (speedAt >= 0L && now - speedAt in 0..SPEED_FRESH_MS) speed else null
        currentParked = if (gearAt >= 0L && now - gearAt in 0..MotionGate.FRESH_MS) parked else null
        val physicalSafe = started && physicalInteractionSafe(now)
        compactDisplay = !physicalSafe || platformDisplayRestricted
        canUseDeveloperTools = BuildConfig.DEBUG && physicalSafe && realEditingAllowed == true && !storageFailure
        canEdit = stopped && realEditingAllowed == true && !storageFailure
        canType = canEdit && realKeyboardAllowed == true
        inputReason = when {
            storageFailure -> "저장 오류로 변경을 제한했습니다"
            !started -> "관측 일시 중지 · 관측 재개 후 차량 상태를 확인해주세요"
            !physicalSafe && currentSpeed == null -> "P단·정지 신호 미확인 · 차량 상태 확인 필요"
            !stopped -> "이동 중 또는 차량 상태 미확인 · 감지 기록과 입력 내용은 유지됩니다"
            realEditingAllowed == null || realKeyboardAllowed == null -> "플랫폼 UX 제한 확인 중"
            realEditingAllowed != true -> "플랫폼에서 조작을 제한하고 있습니다"
            realKeyboardAllowed != true -> "P단 또는 정지 확인 · 플랫폼에서 키보드를 제한하고 있습니다"
            else -> "P단 또는 정지 확인 · 입력 가능"
        }
        if (stopped != lastStopped) { lastStopped = stopped }
    }

    override fun onParked(value: Boolean?, receivedAt: Long) {
        parked = value
        gearAt = receivedAt
        updateGates(SystemClock.elapsedRealtime())
    }

    private fun validDistance(now: Long): BigDecimal? = currentOdometer?.takeIf {
        odometerAt >= 0 && now - odometerAt in 0..ODOMETER_FRESH_MS
    }

    override fun onSpeed(metersPerSecond: Double?, receivedAt: Long) {
        physicalSpeed = metersPerSecond?.takeIf { it.isFinite() }
        physicalSpeedAt = receivedAt
        if (simulated) { updateGates(SystemClock.elapsedRealtime()); return }
        speed = metersPerSecond?.takeIf { it.isFinite() }
        speedAt = receivedAt
        updateGates(SystemClock.elapsedRealtime())
        detector.onMotion(isStopped(SystemClock.elapsedRealtime()), receivedAt)
        drainDetector()
    }

    override fun onUx(keyboardAllowed: Boolean?, editingAllowed: Boolean?) {
        // Real platform restrictions remain authoritative in simulation too.
        realKeyboardAllowed = keyboardAllowed
        realEditingAllowed = editingAllowed
        updateGates(SystemClock.elapsedRealtime())
    }

    override fun onDisplayRestrictions(restricted: Boolean, maxStringLength: Int, maxContentItems: Int) {
        platformDisplayRestricted = restricted
        displayMaxStringLength = maxStringLength.coerceAtLeast(0)
        displayMaxItems = maxContentItems.coerceAtLeast(0)
        updateGates(SystemClock.elapsedRealtime())
    }

    override fun onOdometer(km: BigDecimal?, receivedAt: Long) {
        if (simulated) return
        receiveOdometer(km, receivedAt)
    }

    private fun receiveOdometer(km: BigDecimal?, receivedAt: Long) {
        if (km == null || km.signum() < 0) { currentOdometer = null; odometerAt = -1L; return }
        val previous = lastGoodOdometer
        if (previous != null && km < previous) {
            noteProblem("누적 주행거리 역행 감지")
            if (snapshot.currentTrip?.phase == Phase.RECORDING) mutate { addIssue("누적 주행거리가 이전보다 감소했습니다. 초기화·단위를 확인하고 구간을 수정해주세요.") }
            currentOdometer = null; odometerAt = -1L
            return
        }
        currentOdometer = km; odometerAt = receivedAt
        lastGoodOdometer = km
    }

    override fun onSeat(seat: Seat, occupied: Boolean?, receivedAt: Long) {
        if (simulated) return
        receiveSeat(seat, occupied, receivedAt)
    }

    private fun receiveSeat(seat: Seat, occupied: Boolean?, receivedAt: Long) {
        if (!seatSignals.accept(seat, occupied, receivedAt)) return
        seats = seatSignals.values.toMap()
        seatReceivedAt = seatSignals.receivedAt.toMap()
        observationNowMillis = receivedAt
        detector.onSeat(seat, when (occupied) { true -> BoardingDetector.Occupancy.OCCUPIED; false -> BoardingDetector.Occupancy.EMPTY; null -> BoardingDetector.Occupancy.UNKNOWN },
            receivedAt, validDistance(receivedAt), odometerAt, isStopped(receivedAt))
        drainDetector()
    }

    override fun onDoor(id: String, open: Boolean?, receivedAt: Long) {
        if (simulated) return
        if (open == null) {
            detector.onDoorUnknown(id, receivedAt)
            drainDetector()
            return
        }
        detector.onDoor(id, open, receivedAt, isStopped(receivedAt))
        drainDetector()
    }

    override fun onProblem(message: String) { noteProblem(message) }

    override fun onConnection(connected: Boolean, detail: String) {
        status = detail
        if (!connected && !simulated) {
            physicalSpeed = null; physicalSpeedAt = -1L; parked = null; gearAt = -1L
            speed = null; currentOdometer = null; odometerAt = -1L
            clearSeatObservations()
            detector.onDisconnected(SystemClock.elapsedRealtime())
            drainDetector()
        }
    }

    private fun noteProblem(message: String) {
        if (diagnostics.lastOrNull() != message) {
            diagnostics = (diagnostics + message).takeLast(12)
            // Vehicle diagnostics only; no passenger names or trip records are logged.
            Log.w("carpuri.Vehicle", message)
        }
    }

    private fun drainDetector() {
        detector.drainCandidates().forEach { candidate ->
            val trip = snapshot.currentTrip
            if (snapshot.settings.driverName.isBlank() || snapshot.settings.rateWonPerKm.isBlank()) return@forEach
            if (trip != null && trip.phase != Phase.RECORDING) return@forEach
            // Keep stable empty observations during a trip so a later occupancy is a
            // new episode, including after luggage. The engine suppresses exit cards
            // when no passenger ride can be attached to this empty observation.
            if (!candidate.occupied && trip == null) return@forEach
            mutate {
                recordCandidate(candidate.id, candidate.seat, candidate.occupied, candidate.observedAtMillis,
                    candidate.odometerKm, candidate.odometerReceivedAtMillis, candidate.reason, if (simulated) "SIMULATION" else "PLEOS",
                    doorCycleId = candidate.doorCycleId, rearOccupiedCountAfter = candidate.rearOccupiedCountAfter)
                if (trip == null) {
                    seats.filterValues { it == null }.keys.forEach { seat ->
                        addIssue("${seat.label}의 점유 상태가 미확인입니다. 해당 좌석에 사람이 있었는지 확인하고 누락 구간을 정리해주세요.")
                    }
                }
            }
        }
        detector.drainIssues().distinct().forEach { issue ->
            noteProblem(issue)
            if (snapshot.currentTrip?.phase == Phase.RECORDING && snapshot.currentTrip?.issues?.none { it.message == qualityMessage(issue) && !it.resolved } == true) {
                mutate { addIssue(qualityMessage(issue)) }
            }
        }
    }

    fun enableSimulation(enable: Boolean) {
        updateGates(SystemClock.elapsedRealtime())
        if (!canUseDeveloperTools) return
        if (snapshot.currentTrip != null && snapshot.currentTrip?.phase != Phase.CONFIRMED) { error = "운행 중에는 실제·모의 입력을 바꿀 수 없습니다."; return }
        simulated = enable
        detector = BoardingDetector()
        clearSeatObservations()
        lastGoodOdometer = null
        if (enable) {
            simSpeed = 0.0; speed = 0.0; speedAt = SystemClock.elapsedRealtime()
            currentOdometer = BigDecimal.ZERO; odometerAt = speedAt
            initializeSimulationDoors(speedAt)
            Seat.values().forEach { receiveSeat(it, false, speedAt) }
        } else {
            speed = null; currentOdometer = null; speedAt = -1L; odometerAt = -1L
            if (started) { source.stop(); source.start(this) }
        }
        updateGates(SystemClock.elapsedRealtime())
    }

    fun simulateSeat(seat: Seat, occupied: Boolean, withDoor: Boolean = true) {
        updateGates(SystemClock.elapsedRealtime())
        if (!simulated || !canUseDeveloperTools) return
        val now = SystemClock.elapsedRealtime()
        if (withDoor) {
            val doorId = when (seat) {
                Seat.FRONT_PASSENGER -> BoardingDetector.DOOR_FRONT_PASSENGER
                Seat.REAR_LEFT -> BoardingDetector.DOOR_REAR_LEFT
                Seat.REAR_RIGHT -> BoardingDetector.DOOR_REAR_RIGHT
            }
            detector.onDoor(doorId, true, now, isStopped(now))
            detector.onDoor(doorId, false, now, isStopped(now))
            simDoors[doorId] = false
        }
        odometerAt = now
        receiveSeat(seat, occupied, now)
    }

    fun simulateDistance(delta: String) {
        updateGates(SystemClock.elapsedRealtime())
        if (!simulated || !canUseDeveloperTools) return
        try {
            val from = requireNotNull(currentOdometer) { "모의 누적 거리를 먼저 직접 설정해주세요. 복원 이전의 미관측 거리는 추정하지 않습니다." }
            currentOdometer = from.add(Inputs.distance(delta)); odometerAt = SystemClock.elapsedRealtime()
        }
        catch (failure: Exception) { error = failure.message }
    }

    fun setSimulationOdometer(text: String) {
        updateGates(SystemClock.elapsedRealtime())
        if (!simulated || !canUseDeveloperTools || !canType) return
        try { currentOdometer = Inputs.distance(text); odometerAt = SystemClock.elapsedRealtime() }
        catch (failure: Exception) { error = failure.message }
    }

    fun simulateMotion(moving: Boolean) { updateGates(SystemClock.elapsedRealtime()); if (simulated && canUseDeveloperTools) { simSpeed = if (moving) 10.0 else 0.0; speed = simSpeed; speedAt = SystemClock.elapsedRealtime(); updateGates(speedAt) } }
    fun simulateUnknown(seat: Seat) { updateGates(SystemClock.elapsedRealtime()); if (simulated && canUseDeveloperTools) receiveSeat(seat, null, SystemClock.elapsedRealtime()) }
    fun resetDetection() {
        detector = BoardingDetector()
        if (snapshot.currentTrip == null) { lastGoodOdometer = null }
        clearSeatObservations()
        if (simulated) {
            val now = SystemClock.elapsedRealtime()
            initializeSimulationDoors(now)
            Seat.values().forEach { receiveSeat(it, false, now) }
        } else if (started) { source.stop(); source.start(this) }
    }

    private fun clearSeatObservations() {
        seatSignals.clear()
        seats = seatSignals.values.toMap()
        seatReceivedAt = seatSignals.receivedAt.toMap()
    }

    private fun initializeSimulationDoors(now: Long) {
        simDoors.clear()
        listOf(BoardingDetector.DOOR_FRONT_PASSENGER, BoardingDetector.DOOR_REAR_LEFT,
            BoardingDetector.DOOR_REAR_RIGHT).forEach { id ->
            detector.onDoor(id, false, now, isStopped(now))
            simDoors[id] = false
        }
    }

    private fun qualityMessage(code: String): String = when {
        code.startsWith("SEAT_SIGNAL_UNKNOWN:") -> "${Seat.values().firstOrNull { it.name == code.substringAfter(':') }?.label ?: "좌석"} 상태를 읽지 못한 구간이 있습니다. 승하차와 이용 구간을 확인해주세요."
        code.startsWith("DOOR_SIGNAL_UNKNOWN:") -> "문 상태를 읽지 못한 구간이 있습니다. 승하차 누락 여부를 확인해주세요."
        code == "OBSERVATION_GAP" -> "차량 연결에 관측 공백이 있습니다. 공백 중 승하차와 누적 거리를 확인해주세요."
        code == "OUT_OF_ORDER_SIGNAL" -> "수신 순서가 맞지 않는 신호를 제외했습니다. 승하차 기록을 검토해주세요."
        else -> "차량 신호를 확인해야 하는 구간이 있습니다. 승하차 기록을 검토해주세요."
    }

    companion object {
        const val SPEED_FRESH_MS = 3_000L
        const val ODOMETER_FRESH_MS = 5_000L
        const val SEAT_FRESH_MS = 5_000L
    }
}
