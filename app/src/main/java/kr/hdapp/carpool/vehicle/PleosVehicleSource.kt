package kr.hdapp.carpool.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.VehicleGear
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.hdapp.carpool.core.Seat
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * Typed, read-only Vehicle SDK 2.3.2 + public AAOS adapter. No reflected or private API.
 * Uses the seat-occupancy compatibility AAR from app/libs: raw 1=empty, 2=occupied,
 * all other/absent values=unknown, in both getter and subscription paths.
 * The unpatched SDK collapses raw 2/0/null to false; do not invert its Boolean here.
 * SDK sources document getCurrentDistanceDriven/registerDistanceDriven as km (trip APIs are m).
 * SDK has no sample timestamp: receive-time freshness cannot prove underlying sensor freshness.
 * 3 s speed / 5 s SDK liveness limits below are APP policies, not Pleos requirements.
 * Owned by the foreground observation service, independent of Activity visibility.
 * System process death or vehicle power-off can still interrupt observation.
 */
class PleosVehicleSource(context: Context) : VehicleSource {
    private val context = context.applicationContext
    override val label = "차량 연결 · Pleos SDK 2.3.2"
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    override fun start(listener: VehicleListener) {
        stop()
        session = Session(listener).also { it.start() }
    }

    override fun stop() {
        session?.stop()
        session = null
    }

    private inner class Session(private val out: VehicleListener) {
        private val io = Executors.newSingleThreadExecutor()
        private var active = true
        private var vehicle: Vehicle? = null
        private var initialized = false
        private var initializationFinished = false
        private var inFlight = 0
        private var releaseRequested = false
        private val pending = mutableSetOf<String>()
        private val lastValues = mutableMapOf<String, Long>()
        private val signalOrder = SignalReadOrder()
        private val reported = mutableSetOf<String>()
        private val doorSubscriptions = mutableMapOf<DoorArea, DoorPositionListener>()
        private val seatSubscriptions = mutableMapOf<SeatArea, SeatOccupancyListener>()
        private val readableSeats = mutableSetOf<SeatArea>()
        private var odometerSubscribed = false
        private var car: Car? = null
        private var propertyManager: CarPropertyManager? = null
        private var uxManager: CarUxRestrictionsManager? = null
        private var speedReadPending = false
        private var speedSubscribed = false
        private var gearReadPending = false
        private var gearSubscribed = false
        private var sdkTick = 0
        private var capabilityAttempts = 0
        private var doorCapabilityDone = false
        private var seatCapabilityDone = false
        private var odometerCapabilityDone = false
        private val seatAreas = linkedMapOf(
            Seat.FRONT_PASSENGER to SeatArea(SeatValue.ROW_1_RIGHT),
            Seat.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
            Seat.REAR_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT)
        )
        private val doorAreas = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
            DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT).associate { it.name to DoorArea(it) }
        private val distanceListener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = deliver { bump("odo"); odometer(value) }
            override fun onFailed(e: Exception) = deliver { invalidOdometer("거리 수신 오류: ${e.message}") }
        }
        private val speedListener = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = deliver { speed(value) }
            override fun onErrorEvent(propertyId: Int, areaId: Int) = deliver {
                out.onSpeed(null, now()); problem("속도 센서 오류 · 입력이 잠겼습니다")
            }
        }
        private val gearListener = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) = deliver { bump("gear"); gear(value) }
            override fun onErrorEvent(propertyId: Int, areaId: Int) = deliver {
                out.onParked(null, now()); problem("기어 센서 오류 · 정지 속도 신호로만 입력 확인")
            }
        }
        private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { value ->
            deliver { ux(value) }
        }
        private val heartbeat = object : Runnable {
            override fun run() {
                if (!active) return
                readSpeed()
                if (gearSubscribed) readGear()
                if (odometerSubscribed) readOdometer()
                if (++sdkTick % 3 == 0) {
                    if (initialized && capabilityAttempts < 5 &&
                        !(doorCapabilityDone && seatCapabilityDone && odometerCapabilityDone)) inspectCapabilities()
                    doorSubscriptions.keys.toList().forEach { readDoor(it) }
                    readableSeats.toList().forEach { readSeat(it) }
                }
                val time = now()
                if (time - (lastValues["speed"] ?: 0L) > 3_000L) out.onSpeed(null, time)
                if (time - (lastValues["gear"] ?: 0L) > 3_000L) out.onParked(null, time)
                if (time - (lastValues["odo"] ?: 0L) > 5_000L) out.onOdometer(null, time)
                doorSubscriptions.keys.forEach { area ->
                    val key = "door:${doorId(area)}"
                    if (time - (lastValues[key] ?: 0L) > 5_000L) out.onDoor(doorId(area), null, time)
                }
                readableSeats.forEach { area ->
                    val seat = seatAreas.entries.first { it.value == area }.key
                    if (time - (lastValues["seat:$seat"] ?: 0L) > 5_000L && lastValues.remove("seat:$seat") != null) {
                        bump("seat:$seat")
                        out.onSeat(seat, null, time)
                    }
                }
                main.postDelayed(this, 1_000L)
            }
        }

        fun start() {
            out.onConnection(false, "차량 서비스 연결 확인 중")
            out.onSpeed(null, now()); out.onParked(null, now()); unknownUx(); out.onOdometer(null, now())
            seatAreas.keys.forEach { out.onSeat(it, null, now()) }
            doorAreas.keys.forEach { out.onDoor(it, null, now()) }
            connectCar()
            io.execute {
                var created: Vehicle? = null
                try {
                    val v = Vehicle(context)
                    created = v
                    v.initialize()
                    main.post {
                        vehicle = v; initialized = true; initializationFinished = true
                        if (active) inspectCapabilities() else releaseWhenIdle()
                    }
                } catch (e: Exception) {
                    // No property requests have started if initialize failed.
                    runCatching { created?.release() }
                    main.post {
                        initializationFinished = true
                        if (active) { problem("Pleos 초기화 실패: ${e.message}"); out.onConnection(false, "Pleos 연결 실패") }
                        releaseWhenIdle()
                    }
                }
            }
            main.post(heartbeat)
        }

        private fun inspectCapabilities() {
            val v = vehicle ?: return
            capabilityAttempts++
            if (!doorCapabilityDone) request<Pair<AccessType, Map<DoorArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>>>(
                "capDoor", { (access, bounds) ->
                    doorCapabilityDone = true
                    out.onConnection(true, "Pleos 응답 확인 · 개별 신호 상태를 확인하세요")
                    if (!readable(access)) { problem("문 상태 조회 권한/지원 없음"); return@request }
                    for ((id, area) in doorAreas) {
                        val range = bounds[area]
                        // Only the official sample's proven binary mapping is enabled.
                        if (range == null || range.minValue != 0 || range.maxValue != 1) {
                            problem("$id 문 상태 미지원 또는 0/1 외 형식 · 수동 검토 필요"); continue
                        }
                        val callback = object : DoorPositionListener {
                            override fun onPositionUpdated(pos: Int?) = deliver { bump("door:$id"); door(area, pos) }
                            override fun onFailed(e: Exception) = deliver { out.onDoor(id, null, now()); problem("$id 문 수신 오류: ${e.message}") }
                        }
                        readDoor(area) {
                            try { v.getDoor().registerDoorPosition(area, callback); doorSubscriptions[area] = callback }
                            catch (e: Exception) { out.onDoor(id, null, now()); problem("$id 문 구독 실패: ${e.message}") }
                        }
                    }
                    out.onConnection(true, "Pleos 응답 확인 · 개별 신호 상태를 확인하세요")
                }, { problem("문 지원 확인 실패: ${it.message}") }
            ) { ok, fail -> v.getDoor().checkDoorPositionCapabilities({ cfg, map -> ok(cfg.accessType to map) }, fail) }
            if (!seatCapabilityDone) request<Pair<AccessType, List<SeatArea>>>("capSeat", { (access, areas) ->
                seatCapabilityDone = true
                out.onConnection(true, "Pleos 응답 확인 · 개별 신호 상태를 확인하세요")
                if (!readable(access)) { problem("좌석 조회 권한/지원 없음"); return@request }
                for ((seat, area) in seatAreas) {
                    if (area !in areas) { problem("${seat.name} 점유 센서 미지원 · 빈 좌석이 아닙니다"); continue }
                    readableSeats += area
                    val callback = object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(value: Boolean?) = deliverSignal("seat:$seat") { this@Session.seat(area, value) }
                        override fun onFailed(e: Exception) = deliverSignal("seat:$seat") { out.onSeat(seat, null, now()); problem("$seat 좌석 수신 오류: ${e.message}") }
                    }
                    readSeat(area) {
                        try { v.getSeat().registerSeatOccupancy(area, callback); seatSubscriptions[area] = callback }
                        catch (e: Exception) { out.onSeat(seat, null, now()); problem("$seat 좌석 구독 실패: ${e.message}") }
                    }
                }
            }, { problem("좌석 지원 확인 실패: ${it.message}") }) { ok, fail ->
                v.getSeat().checkSeatOccupancyCapabilities({ cfg, areas -> ok(cfg.accessType to areas) }, fail)
            }
            if (!odometerCapabilityDone) request<AccessType>("capOdo", { access ->
                odometerCapabilityDone = true
                out.onConnection(true, "Pleos 응답 확인 · 개별 신호 상태를 확인하세요")
                if (!readable(access)) { problem("누적 거리 조회 권한/지원 없음"); return@request }
                readOdometer {
                    try { v.getOdometer().registerDistanceDriven(distanceListener); odometerSubscribed = true }
                    catch (e: Exception) { invalidOdometer("거리 구독 실패: ${e.message}") }
                }
            }, { invalidOdometer("거리 지원 확인 실패: ${it.message}") }) { ok, fail ->
                v.getOdometer().checkCurrentDistanceDrivenCapabilities({ ok(it.accessType) }, fail)
            }
        }

        private fun readDoor(area: DoorArea, after: () -> Unit = {}) {
            val v = vehicle ?: return
            val key = "door:${doorId(area)}"
            val revision = signalOrder.version(key)
            request<Int?>("getDoor:${doorId(area)}", {
                if (signalOrder.isCurrent(key, revision)) door(area, it)
                after()
            }, {
                if (signalOrder.isCurrent(key, revision)) out.onDoor(doorId(area), null, now())
                problem("문 조회 오류: ${it.message}"); after()
            }) { ok, fail -> v.getDoor().getCurrentDoorPosition(area, ok, fail) }
        }
        private fun readSeat(area: SeatArea, after: () -> Unit = {}) {
            val v = vehicle ?: return
            val seat = seatAreas.entries.first { it.value == area }.key
            val key = "seat:$seat"
            val revision = signalOrder.version(key)
            request<Boolean?>("getSeat:$seat", {
                if (signalOrder.isCurrent(key, revision)) this@Session.seat(area, it)
                after()
            }, {
                if (signalOrder.isCurrent(key, revision)) out.onSeat(seat, null, now())
                problem("$seat 좌석 조회 오류: ${it.message}"); after()
            }) { ok, fail -> v.getSeat().getCurrentSeatOccupancy(area, ok, fail) }
        }
        private fun readOdometer(after: () -> Unit = {}) {
            val v = vehicle ?: return
            val revision = signalOrder.version("odo")
            request<Float?>("getOdo", {
                if (signalOrder.isCurrent("odo", revision)) odometer(it)
                after()
            }, {
                if (signalOrder.isCurrent("odo", revision)) invalidOdometer("거리 조회 오류: ${it.message}")
                after()
            }) { ok, fail -> v.getOdometer().getCurrentDistanceDriven(ok, fail) }
        }
        private fun door(area: DoorArea, value: Int?) {
            val id = doorId(area)
            val open = when (value) { 0 -> false; 1 -> true; else -> null }
            lastValues["door:$id"] = now()
            out.onDoor(id, open, now())
        }
        private fun seat(area: SeatArea, value: Boolean?) {
            val seat = seatAreas.entries.first { it.value == area }.key
            lastValues["seat:$seat"] = now()
            // Already normalized by the compatibility AAR, including null for UNKNOWN.
            out.onSeat(seat, value, now())
        }
        private fun odometer(value: Float?) {
            lastValues["odo"] = now()
            val km = value?.takeIf { it.isFinite() && it >= 0f }?.let { BigDecimal(it.toString()) }
            out.onOdometer(km, now())
            if (value != null && km == null) problem("유효하지 않은 누적 거리 · 검토가 필요합니다")
        }
        private fun invalidOdometer(message: String) { out.onOdometer(null, now()); problem(message) }

        private fun connectCar() {
            if (Build.VERSION.SDK_INT < 29 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                problem("AAOS 차량 플랫폼을 확인할 수 없습니다 · 속도/입력 제한은 미확인입니다")
                return
            }
            try {
                car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                    if (!active) { connectedCar.disconnect(); return@createCar }
                    if (!ready) {
                        propertyManager = null; uxManager = null
                        out.onSpeed(null, now()); out.onParked(null, now()); unknownUx()
                        out.onConnection(false, "AAOS 연결 끊김 · 관측 공백 확인 필요")
                        problem("AAOS 연결이 끊겼습니다")
                    } else {
                        car = connectedCar
                        try {
                            uxManager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            uxManager?.registerListener(uxListener)
                            ux(uxManager?.currentCarUxRestrictions)
                        } catch (e: Exception) { unknownUx(); problem("운전 중 제한 조회 실패: ${e.message}") }
                        try {
                            propertyManager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                            val manager = propertyManager ?: error("CarPropertyManager 없음")
                            if (manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null) {
                                problem("차량 속도 API 미지원 또는 조회 권한 없음")
                            } else {
                                speedSubscribed = manager.registerCallback(speedListener,
                                    VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
                                if (!speedSubscribed) problem("차량 속도 구독 실패")
                                readSpeed()
                            }
                        } catch (e: Exception) { out.onSpeed(null, now()); problem("차량 속도 권한/연결 확인 필요: ${e.message}") }
                        try {
                            val manager = propertyManager ?: error("CarPropertyManager 없음")
                            if (manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) {
                                problem("P단 신호 미지원 · 정지 속도 신호로 입력 확인")
                            } else {
                                gearSubscribed = manager.registerCallback(gearListener,
                                    VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                                if (!gearSubscribed) problem("기어 신호 구독 실패")
                                readGear()
                            }
                        } catch (e: Exception) { out.onParked(null, now()); problem("기어 조회 권한/연결 확인 필요: ${e.message}") }
                    }
                }
            } catch (e: Exception) { out.onSpeed(null, now()); unknownUx(); problem("AAOS 연결 실패: ${e.message}") }
        }
        private fun readSpeed() {
            val manager = propertyManager ?: return
            if (speedReadPending || !active) return
            speedReadPending = true
            io.execute {
                try {
                    val value = manager.getProperty(java.lang.Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                    deliver { speedReadPending = false; if (value != null) speed(value) else out.onSpeed(null, now()) }
                } catch (e: Exception) { deliver { speedReadPending = false; out.onSpeed(null, now()); problem("속도 조회 실패: ${e.message}") } }
            }
        }
        private fun readGear() {
            val manager = propertyManager ?: return
            if (gearReadPending || !active) return
            gearReadPending = true
            val revision = signalOrder.version("gear")
            io.execute {
                try {
                    val value = manager.getProperty(java.lang.Integer::class.java, VehiclePropertyIds.GEAR_SELECTION, 0)
                    deliver {
                        gearReadPending = false
                        if (signalOrder.isCurrent("gear", revision)) {
                            if (value != null) gear(value) else out.onParked(null, now())
                        }
                    }
                } catch (e: Exception) {
                    deliver {
                        gearReadPending = false
                        if (signalOrder.isCurrent("gear", revision)) out.onParked(null, now())
                        problem("기어 조회 실패: ${e.message}")
                    }
                }
            }
        }
        private fun gear(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
            // GEAR_SELECTION is ON_CHANGE: an unchanged P legitimately keeps its old timestamp.
            // A successful AVAILABLE read/notification establishes liveness, not a new gear change.
            val valid = value.status == CarPropertyValue.STATUS_AVAILABLE &&
                value.timestamp > 0 && value.timestamp <= SystemClock.elapsedRealtimeNanos()
            val measured = (value.value as? Number)?.toInt()
            val parked = if (valid && measured != null && measured != VehicleGear.GEAR_UNKNOWN)
                measured == VehicleGear.GEAR_PARK else null
            if (parked != null) lastValues["gear"] = now()
            out.onParked(parked, now())
        }
        private fun speed(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED) return
            val ageNs = SystemClock.elapsedRealtimeNanos() - value.timestamp
            val valid = value.status == CarPropertyValue.STATUS_AVAILABLE && value.timestamp > 0 && ageNs in 0..3_000_000_000L
            val measured = (value.value as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val speed = if (valid) measured else null
            // Preserve reverse motion sign. Consumer only considers exactly zero stopped.
            if (speed != null) lastValues["speed"] = now()
            out.onSpeed(speed, now())
        }
        private fun unknownUx() {
            out.onUx(null, null)
            out.onDisplayRestrictions(true, 0, 0)
        }
        private fun ux(value: CarUxRestrictions?) {
            if (value == null) { unknownUx(); return }
            val flags = if (value.isRequiresDistractionOptimization) value.activeRestrictions else 0
            out.onUx(flags and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD == 0,
                flags and CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP == 0)
            val compact = flags and (CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT or
                CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH or
                CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) != 0
            out.onDisplayRestrictions(compact,
                if (flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH != 0)
                    value.maxRestrictedStringLength.coerceAtLeast(0) else Int.MAX_VALUE,
                if (flags and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0)
                    value.maxCumulativeContentItems.coerceAtLeast(0) else Int.MAX_VALUE)
        }

        private fun <T> request(key: String, onValue: (T) -> Unit, onError: (Exception) -> Unit,
                                invoke: ((T) -> Unit, (Exception) -> Unit) -> Unit) {
            if (!active || !pending.add(key)) return
            inFlight++
            var completed = false
            var timeout: Runnable? = null
            fun finish(action: () -> Unit) {
                main.post {
                    if (!completed) {
                        completed = true
                        timeout?.let { main.removeCallbacks(it) }
                        pending.remove(key); inFlight--
                        if (active) action()
                        releaseWhenIdle()
                    }
                }
            }
            // The SDK exposes no per-request cancellation. After 8s we abandon the
            // result and ignore any late callback; stop can then release the session.
            // This is an app timeout, not a claimed SDK timeout/guarantee.
            timeout = Runnable { finish { onError(java.util.concurrent.TimeoutException("$key 응답 대기 시간 초과")) } }
            main.postDelayed(timeout!!, 8_000L)
            try { invoke({ value -> finish { onValue(value) } }, { error -> finish { onError(error) } }) }
            catch (e: Exception) { finish { onError(e) } }
        }
        fun stop() {
            if (!active) return
            active = false
            main.removeCallbacks(heartbeat)
            val v = vehicle
            if (v != null) {
                doorSubscriptions.forEach { (area, callback) -> runCatching { v.getDoor().unregisterDoorPosition(area, callback) } }
                seatSubscriptions.forEach { (area, callback) -> runCatching { v.getSeat().unregisterSeatOccupancy(area, callback) } }
                if (odometerSubscribed) runCatching { v.getOdometer().unregisterDistanceDriven(distanceListener) }
            }
            doorSubscriptions.clear(); seatSubscriptions.clear(); readableSeats.clear(); odometerSubscribed = false
            if (speedSubscribed) runCatching { propertyManager?.unregisterCallback(speedListener) }
            if (gearSubscribed) runCatching { propertyManager?.unregisterCallback(gearListener) }
            runCatching { uxManager?.unregisterListener() }; runCatching { car?.disconnect() }
            propertyManager = null; uxManager = null; car = null
            releaseWhenIdle()
        }
        private fun releaseWhenIdle() {
            if (active || !initializationFinished || inFlight != 0 || releaseRequested) return
            releaseRequested = true
            val v = vehicle
            io.execute { runCatching { v?.release() }; io.shutdown() }
        }
        private fun deliver(block: () -> Unit) { main.post { if (active) block() } }
        private fun bump(key: String) { signalOrder.changed(key) }
        private fun deliverSignal(key: String, block: () -> Unit) {
            // Fence immediately on callback receipt, before its queued main-thread delivery.
            val revision = signalOrder.changed(key)
            deliver { if (signalOrder.isCurrent(key, revision)) block() }
        }
        private fun problem(message: String) { if (reported.add(message)) out.onProblem(message) }
        private fun doorId(area: DoorArea) = doorAreas.entries.first { it.value == area }.key
        private fun readable(access: AccessType) = access == AccessType.READ || access == AccessType.READ_WRITE
        private fun now() = SystemClock.elapsedRealtime()
    }
}
