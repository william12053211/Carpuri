package kr.hdapp.carpool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.hdapp.carpool.CarpoolViewModel
import kr.hdapp.carpool.BuildConfig
import kr.hdapp.carpool.R
import kr.hdapp.carpool.core.*
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

private val Navy = Color(0xFF0C1626)
private val SurfaceNavy = Color(0xFF17263A)
private val Mint = Color(0xFF74E3C8)
private val Muted = Color(0xFFADBCD0)
private val Orange = Color(0xFFFFC779)

@Composable
fun CarpoolApp(vm: CarpoolViewModel) {
    val scheme = darkColorScheme(primary = Mint, onPrimary = Navy, background = Navy,
        surface = SurfaceNavy, onSurface = Color(0xFFF3F6FA), secondary = Color(0xFF94B9FF))
    val standard = Typography()
    val typography = standard.copy(
        bodyLarge = standard.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodyMedium = standard.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        bodySmall = standard.bodySmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
        labelLarge = standard.labelLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
        labelMedium = standard.labelMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        labelSmall = standard.labelSmall.copy(fontSize = 20.sp, lineHeight = 28.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            // Apply insets once, outside both scroll and compact content. Never infer
            // a vehicle bar's position from screen pixels or change display density.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Content(vm)
            }
        }
    }
}

@Composable
private fun Content(vm: CarpoolViewModel) {
    val snapshot = vm.snapshot
    val trip = snapshot.currentTrip
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var developerOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var historyId by rememberSaveable { mutableStateOf("") }
    var dialog by rememberSaveable { mutableStateOf("") }
    var targetId by rememberSaveable { mutableStateOf("") }
    var draftA by rememberSaveable { mutableStateOf("") }
    var draftB by rememberSaveable { mutableStateOf("") }
    var draftNote by rememberSaveable { mutableStateOf("") }
    var selectedPerson by rememberSaveable { mutableStateOf("") }
    var expandedEvents by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showExistingPeople by rememberSaveable { mutableStateOf(false) }
    var previousBoardToCancel by rememberSaveable { mutableStateOf("") }
    var boardingDrafts by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    var tollMembers by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var editItemId by rememberSaveable { mutableStateOf("") }
    var manualName by rememberSaveable { mutableStateOf("") }
    var manualSeat by rememberSaveable { mutableStateOf(Seat.FRONT_PASSENGER.name) }
    var editingSeat by rememberSaveable { mutableStateOf(Seat.FRONT_PASSENGER.name) }
    var seatChoice by rememberSaveable { mutableStateOf(EventStatus.CONFIRMED.name) }
    var settingsName by rememberSaveable { mutableStateOf(vm.snapshot.settings.driverName) }
    var settingsRate by rememberSaveable { mutableStateOf(vm.snapshot.settings.rateWonPerKm) }

    // Keep drafts above the compact branch. Observation runs independently of this UI;
    // choosing names and editing records remain gated by the vehicle/platform state.
    if (vm.compactDisplay) {
        Column(Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (vm.displayMaxItems >= 1 && vm.displayMaxStringLength > 0) {
                val label = when {
                    !vm.observationRequested -> "차량 관측 일시중지"
                    !vm.observing && vm.error != null -> "관측 시작 실패 · ${vm.error}"
                    !vm.observing -> "차량 관측 연결 확인 중 · ${vm.status}"
                    vm.currentSpeed == null && vm.currentParked != true -> "차량 상태 확인 중 · ${vm.status}"
                    snapshot.currentTrip?.phase == Phase.RECORDING -> "승하차 기록 중 · P단 또는 정차 후 확인"
                    else -> "승하차 감지 중 · P단 또는 정차 후 등록"
                }
                Text(label.take(vm.displayMaxStringLength), color = Mint, fontSize = 26.sp)
            }
            // Resuming monitoring does not edit a trip or request text entry. Stopping
            // clears live motion/UX evidence, so requiring canEdit here would deadlock.
            if (!vm.observing) {
                Spacer(Modifier.height(18.dp))
                Action(if (vm.observationRequested) "관측 다시 시작" else "관측 시작",
                    modifier = Modifier.widthIn(max = 320.dp)) { vm.start() }
            }
        }
        return
    }

    fun open(kind: String, id: String = "", a: String = "", b: String = "", note: String = "") {
        dialog = kind; targetId = id; draftA = if (kind == "NEW_PERSON") boardingDrafts[id].orEmpty() else a; draftB = b; draftNote = note
        selectedPerson = ""; showExistingPeople = false; previousBoardToCancel = ""
        vm.dismissError()
    }
    fun openSeatEdit(registration: SeatRegistration) {
        val event = registration.boardingEvent ?: return
        open("EDIT_SEAT", event.id, registration.person?.name
            ?: trip?.people?.firstOrNull { it.id == event.personId }?.name
            ?: boardingDrafts[event.id].orEmpty())
        editingSeat = registration.seat.name
        seatChoice = when (event.status) {
            EventStatus.LUGGAGE -> EventStatus.LUGGAGE.name
            EventStatus.CANCELLED -> EventStatus.CANCELLED.name
            else -> EventStatus.CONFIRMED.name
        }
    }
    fun openToll(toll: Toll? = null) {
        editItemId = toll?.id ?: UUID.randomUUID().toString()
        tollMembers = ArrayList(toll?.participantIds ?: setOfNotNull(trip?.driverId))
        open("TOLL", toll?.id.orEmpty(), toll?.amountWon?.toString().orEmpty(), note = toll?.memo.orEmpty())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("함께 타고, 공평하게", color = Mint, fontSize = 20.sp)
                Text(stringResource(R.string.app_name), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { settingsOpen = true }, enabled = vm.canEdit) { Text("설정", fontSize = 20.sp) }
        }
        InfoBanner(if (vm.simulated) "앱 내부 모의 입력 · 웹 콘솔의 좌석 변경은 반영되지 않습니다"
            else "Pleos 차량·에뮬레이터 입력", if (vm.simulated) Orange else Mint)
        Text(vm.inputReason, color = if (vm.canType) Mint else Orange, fontSize = 20.sp)
        Panel {
            Text(when {
                vm.observing -> "백그라운드 관측 중"
                vm.observationRequested -> "관측 서비스 시작 확인 중"
                else -> "관측 일시중지"
            }, color = if (vm.observing) Mint else Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (vm.observing) "다른 앱을 열어도 승하차와 거리를 기록합니다. 등록·분류는 P단 또는 정차 후 할 수 있습니다."
                else if (vm.observationRequested) "차량 연결 상태를 확인해주세요. 아직 관측이 시작되지 않았다면 다시 시작할 수 있습니다."
                else "일시중지한 동안의 승하차는 기록되지 않습니다. 다시 시작한 뒤 누락 구간을 확인해주세요.", color = Muted)
            Action(if (vm.observing) "관측 일시중지" else "관측 시작", enabled = vm.canEdit || !vm.observing, secondary = true) {
                if (vm.observing) open("PAUSE_OBSERVATION") else vm.start()
            }
        }

        if (snapshot.settings.driverName.isBlank() || snapshot.settings.rateWonPerKm.isBlank()) {
            SettingsPanel(vm, initial = true, settingsName, { settingsName = it }, settingsRate, { settingsRate = it }, onDone = {})
        } else if (historyOpen) {
            Action("현재 운행으로 돌아가기") { historyOpen = false; historyId = "" }
            val saved = snapshot.history.find { it.id == historyId }
            if (saved?.result != null) saved.result?.let { ResultPanel(it, saved.events.any { event -> event.source == "SIMULATION" }) }
            else {
                SectionTitle("저장한 정산")
                if (snapshot.history.isEmpty()) Text("아직 저장한 정산이 없습니다.", color = Muted)
                snapshot.history.asReversed().forEach { past ->
                    Panel {
                        Text("${past.people.firstOrNull { it.isDriver }?.name.orEmpty()}님의 정산", fontSize = 20.sp)
                        Text("${won(past.result?.totalWon ?: BigInteger.ZERO)} · ${past.id.take(8)}", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("결과 보기", modifier = Modifier.weight(1f)) { historyId = past.id }
                            Action("삭제", vm.canEdit, Modifier.weight(1f), secondary = true) { open("DELETE_HISTORY", past.id) }
                        }
                    }
                }
            }
        } else if (trip?.phase == Phase.CONFIRMED && trip.result != null) {
            trip.result?.let { ResultPanel(it, trip.events.any { event -> event.source == "SIMULATION" }) }
            Action("새 운행 준비", vm.canEdit) {
                if (vm.userAction { newTrip() }) vm.resetDetection()
            }
        } else {
            val appliedRate = trip?.rateWonPerKm?.toString() ?: snapshot.settings.rateWonPerKm
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (trip?.phase) { Phase.REVIEW -> "정산 검토"; Phase.RECORDING -> "운행 기록 중"; else -> "첫 동승자를 기다립니다" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("차량 전체 ${appliedRate}원/km · ${if (trip != null) "이번 운행 고정 단가" else "다음 운행 적용 단가"}", color = Muted, fontSize = 20.sp)
            if (trip == null) Text("문을 열고 닫은 뒤 5초 안의 좌석 점유를 연결합니다. 뒷좌석 변화는 같은 문 여닫기 동안 함께 확인하므로 카드가 잠시 뒤 표시될 수 있습니다.", color = Muted, fontSize = 20.sp)
            if (trip?.phase != Phase.REVIEW) {
                val driverName = trip?.people?.firstOrNull { it.isDriver }?.name ?: snapshot.settings.driverName
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeatTile("운전석", driverName, "운전자 · 분담 포함", Mint, Modifier.weight(1f))
                    PassengerTile(vm, vm.seatRegistration(Seat.FRONT_PASSENGER), Modifier.weight(1f), ::openSeatEdit)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PassengerTile(vm, vm.seatRegistration(Seat.REAR_LEFT), Modifier.weight(1f), ::openSeatEdit)
                    PassengerTile(vm, vm.seatRegistration(Seat.REAR_RIGHT), Modifier.weight(1f), ::openSeatEdit)
                }
                val rearRiderIds = trip?.rides?.filter { !it.cancelled && it.endKm == null && it.seat != Seat.FRONT_PASSENGER }?.map { it.personId }.orEmpty()
                val rearNames = trip?.people?.filter { it.id in rearRiderIds }?.joinToString(" · ") { PassengerNames.display(it.name) }.orEmpty()
                Text("뒷좌석 탑승자: ${rearNames.ifBlank { "등록된 이용자 없음" }}", color = Muted, fontSize = 20.sp)
                Text("누적 주행거리 ${distance(vm.currentOdometer)} km", color = Muted)
            }

            val pending = trip?.events?.filter { it.status == EventStatus.PENDING }.orEmpty()
            if (pending.isNotEmpty()) {
                SectionTitle("확인할 승하차 ${pending.size}건")
                Text("사람·짐·오류 중 선택하세요. 잘못 뜬 항목은 바로 넘길 수 있습니다.", color = Muted)
                pending.forEach { event ->
                    Panel {
                        val currentOccupancy = vm.seats[event.seat]
                        Text("${event.seat.label} · ${pendingObservationLabel(event.occupied, currentOccupancy)}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        if (event.occupied) {
                            if (currentOccupancy == false) Text("앞서 탄 사람이 있었다면 등록하고, 잘못 뜬 항목이면 넘기세요.", color = Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Action("사람 등록", vm.canEdit, Modifier.weight(1f)) { open("NEW_PERSON", event.id) }
                                Action("짐", vm.canEdit, Modifier.weight(1f), secondary = true) { vm.userAction { markLuggage(event.id) } }
                                Action("오류로 넘기기", vm.canEdit, Modifier.weight(1f), secondary = true) { vm.userAction { dismissPendingEvent(event.id) } }
                            }
                        } else {
                            val exitPeople = vm.exitCandidates(event.id)
                            if (event.seat != Seat.FRONT_PASSENGER && exitPeople.size > 1) {
                                Text("${exitPeople.joinToString(" · ") { PassengerNames.display(it.name) }} 중 누가 내렸나요?", color = Muted)
                                Action("누가 내렸나요?", vm.canEdit) { open("EXIT_PERSON", event.id) }
                            } else {
                                Action(exitPeople.singleOrNull()?.let { "${PassengerNames.display(it.name)} 하차 확인" } ?: "하차 확인",
                                    vm.canEdit && exitPeople.isNotEmpty()) { vm.userAction { confirmExit(event.id) } }
                                if (exitPeople.isEmpty()) Text("아직 하차할 사람을 연결하지 못했습니다. 실제 하차라면 이전 탑승을 확인하고, 잘못 뜬 항목이면 오류로 넘기세요.", color = Muted)
                            }
                            Action("계속 타고 있음 / 오류로 넘기기", vm.canEdit, secondary = true) { vm.userAction { dismissPendingEvent(event.id) } }
                        }
                        TextButton(onClick = { expandedEvents = ArrayList(if (event.id in expandedEvents) expandedEvents - event.id else expandedEvents + event.id) }) {
                            Text(if (event.id in expandedEvents) "상세 접기" else "거리·상세 보기")
                        }
                        if (event.id in expandedEvents) {
                            Text("감지 당시 ${distance(event.observedKm)} km · 현재 ${seatValueLabel(currentOccupancy)}", color = Muted)
                            event.correctedKm?.let { Text("수정 거리 ${distance(it)} km", color = Mint) }
                            event.reason?.let { Text(evidenceReason(it), color = Muted, fontSize = 20.sp) }
                            Action("거리 확인·수정", vm.canType, secondary = true) { open("EVENT_DISTANCE", event.id, event.effectiveKm?.toPlainString().orEmpty()) }
                        }
                    }
                }
            }

            val recentExits = trip?.events?.filter { !it.occupied && it.status == EventStatus.CONFIRMED }?.takeLast(3).orEmpty()
            if (recentExits.isNotEmpty() && trip?.phase == Phase.RECORDING) {
                SectionTitle("최근 하차 기록")
                recentExits.asReversed().forEach { event ->
                    val personName = trip.people.firstOrNull { it.id == event.personId }?.let { PassengerNames.display(it.name) } ?: "이용자"
                    Panel {
                        Text("$personName · ${distance(event.effectiveKm)} km에서 하차", fontSize = 20.sp)
                        Text(if (event.seat == Seat.FRONT_PASSENGER) "조수석 하차는 등록된 이용자에게 자동으로 연결됩니다."
                            else "뒷좌석에 한 명만 있었던 하차는 자동으로 연결됩니다.", color = Muted)
                        Action("계속 타고 있음 · 하차 취소", vm.canEdit, secondary = true) { vm.userAction { cancelEvent(event.id) } }
                    }
                }
            }

            if (trip?.phase == Phase.RECORDING) {
                Action("운행 종료 / 정산하기", vm.canEdit) { open("END", a = vm.currentOdometer?.toPlainString().orEmpty()) }
                if (trip.rides.none { !it.cancelled && it.endKm == null } && trip.people.size > 1) {
                    Text("동승자가 모두 내렸습니다. 재탑승을 기다리거나 운행을 종료해주세요.", color = Muted)
                }
            }

            if (trip?.phase == Phase.REVIEW) {
                Text("종료 누적 거리 ${distance(trip.endKm)} km에 고정되었습니다.", color = Mint)
                val unresolved = trip.issues.filterNot { it.resolved }
                if (unresolved.isNotEmpty()) SectionTitle("기록 품질 확인 ${unresolved.size}건")
                unresolved.forEach { issue ->
                    Panel {
                        Text(evidenceReason(issue.message), color = Orange, fontSize = 20.sp)
                        Action("확인했어요", vm.canEdit, secondary = true) { vm.userAction { resolveIssue(issue.id, "사용자가 기록 확인을 선택했습니다.") } }
                    }
                }
                SectionTitle("사람별 이용 기록")
                trip.people.filterNot { it.isDriver }.forEach { person ->
                    Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(PassengerNames.display(person.name), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { open("RENAME", person.id, person.name) }, enabled = vm.canType) { Text("이름 수정") }
                        }
                        trip.rides.filter { it.personId == person.id }.forEach { ride ->
                            Text("${ride.seat.label} · ${distance(ride.startKm)} → ${distance(ride.endKm)} km${if (ride.cancelled) " · 취소됨" else ""}", color = if (ride.cancelled) Muted else Color.White)
                            if (trip.events.firstOrNull { it.id == ride.boardEventId }?.source == "MANUAL") Text("사용자가 추가한 구간 · 원본 센서 관측 없음", color = Orange)
                            if (!ride.cancelled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Action("거리 수정", vm.canType, Modifier.weight(1f), secondary = true) { open("RIDE", ride.id, ride.startKm.toPlainString(), ride.endKm?.toPlainString().orEmpty()) }
                                    Action("구간 취소", vm.canEdit, Modifier.weight(1f), secondary = true) { open("CANCEL_RIDE", ride.id) }
                                }
                            }
                        }
                    }
                }
                Action("누락된 탑승 기록 추가", vm.canType, secondary = true) {
                    editItemId = UUID.randomUUID().toString(); manualName = ""; manualSeat = Seat.FRONT_PASSENGER.name
                    open("MANUAL_RIDE")
                }
                Action("이번 운행 단가 정정", vm.canType, secondary = true) { open("RATE", a = trip.rateWonPerKm.toString()) }
                SectionTitle("통행료")
                Text("운전자는 항상 포함됩니다. 이미 내린 동승자도 선택할 수 있습니다.", color = Muted)
                trip.tolls.forEach { toll ->
                    Panel {
                        Text("${toll.memo.ifBlank { "통행료" }} · ${won(toll.amountWon)}", fontSize = 20.sp)
                        Text(trip.people.filter { it.id in toll.participantIds }.joinToString(" · ") { PassengerNames.display(it.name) }, color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("수정", vm.canType, Modifier.weight(1f), secondary = true) { openToll(toll) }
                            Action("삭제", vm.canEdit, Modifier.weight(1f), secondary = true) { open("DELETE_TOLL", toll.id) }
                        }
                    }
                }
                Action("통행료 추가", vm.canType) { openToll() }
                Action(if (trip.tollStatus == TollStatus.NONE) "✓ 통행료 없음 선택됨" else "통행료 없음", vm.canEdit && trip.tolls.isEmpty(), secondary = true) { vm.userAction { setNoTolls() } }
                if (trip.tollStatus == TollStatus.UNREVIEWED) Text("통행료 입력 여부를 아직 선택하지 않았습니다.", color = Orange)
                val preview = remember(trip) { runCatching { SettlementCalculator.calculate(trip) } }
                preview.getOrNull()?.let { result ->
                    Panel {
                        Text("검토 합계 ${won(result.totalWon)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("운전자가 받을 금액 ${won(result.receivableWon)}", color = Mint, fontSize = 20.sp)
                        result.people.forEach { Text("${it.name}  ${won(it.totalWon)}") }
                    }
                }
                if (preview.isFailure) Text(preview.exceptionOrNull()?.message.orEmpty(), color = Orange)
                Action("정산 확정", vm.canEdit && preview.isSuccess) { vm.userAction { confirm() } }
            }
            if (trip != null && trip.phase != Phase.CONFIRMED) TextButton(onClick = { open("DISCARD") }, enabled = vm.canEdit) { Text("이번 운행 기록 삭제", color = Orange) }
        }

        HorizontalDivider(color = SurfaceNavy)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { historyOpen = !historyOpen; historyId = "" }) { Text("저장한 정산") }
            if (BuildConfig.DEBUG) TextButton(onClick = { developerOpen = !developerOpen }) { Text("개발 도구") }
        }
        if (developerOpen && BuildConfig.DEBUG) DeveloperPanel(vm)
    }

    if (settingsOpen) AlertDialog(onDismissRequest = { settingsOpen = false }, title = { Text("운전자 · 단가 설정") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SettingsPanel(vm, false, settingsName, { settingsName = it }, settingsRate, { settingsRate = it }) { settingsOpen = false } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { settingsOpen = false }) { Text("닫기") } })

    if (dialog.isNotEmpty()) {
        val dialogTrip = vm.snapshot.currentTrip
        val typing = dialog in setOf("NEW_PERSON", "EVENT_DISTANCE", "RIDE", "RENAME", "RATE", "TOLL", "MANUAL_RIDE") || (dialog == "END" && vm.currentOdometer == null) ||
            (dialog == "EDIT_SEAT" && seatChoice == EventStatus.CONFIRMED.name)
        val readyToRegister = dialog != "NEW_PERSON" || (draftA.isNotBlank() &&
            vm.blockingPendingEvents(targetId).isEmpty() && vm.blockingRiders(targetId).isEmpty() &&
            dialogTrip?.events?.firstOrNull { it.id == targetId }?.effectiveKm != null)
        val editingEvent = dialogTrip?.events?.firstOrNull { it.id == targetId && it.occupied }
        val needsSeatDistance = dialog == "EDIT_SEAT" && seatChoice == EventStatus.CONFIRMED.name && editingEvent?.effectiveKm == null
        val readyToEditSeat = dialog != "EDIT_SEAT" ||
            (editingEvent != null && (seatChoice != EventStatus.CONFIRMED.name ||
                draftA.isNotBlank() && (!needsSeatDistance || draftB.isNotBlank())))
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(when (dialog) {
                "NEW_PERSON" -> "누가 탔나요?"; "REBOARD" -> "재탑승 이용자 선택"; "EVENT_DISTANCE" -> "탑승·하차 거리 수정"
                "EXIT_PERSON" -> "실제로 내린 사람 선택"
                "EDIT_SEAT" -> "${Seat.valueOf(editingSeat).label} 수정"
                "RIDE" -> "이용 구간 수정"; "CANCEL_RIDE" -> "이 구간을 취소할까요?"; "RENAME" -> "이름 수정"
                "RATE" -> "이번 운행 단가 수정"; "TOLL" -> "통행료와 분담 대상"; "ISSUE" -> "이 기록을 확인했나요?"
                "MANUAL_RIDE" -> "누락된 이용 구간 추가"; "END" -> "운행을 종료할까요?"; "DELETE_TOLL" -> "이 통행료를 삭제할까요?"; "DELETE_HISTORY" -> "저장한 정산을 삭제할까요?"
                "PAUSE_OBSERVATION" -> "차량 관측을 일시중지할까요?"
                else -> "이번 운행 기록을 삭제할까요?"
            }) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!(if (typing) vm.canType else vm.canEdit)) Text("${vm.inputReason}\n작성한 내용은 유지됩니다.", color = Orange)
                    vm.error?.let { message -> Text(message, color = Orange) }
                    when (dialog) {
                        "NEW_PERSON" -> {
                            Input("이름", draftA, { draftA = it; boardingDrafts = HashMap(boardingDrafts).apply { put(targetId, it) } }, vm.canType)
                            val continuingPeople = vm.continuingRearCandidates(targetId)
                            val reboardingPeople = vm.reboardCandidates(targetId)
                            if (continuingPeople.isNotEmpty() || reboardingPeople.isNotEmpty()) {
                                TextButton(onClick = { showExistingPeople = !showExistingPeople }, enabled = vm.canEdit) {
                                    Text(if (showExistingPeople) "기존 이용자 접기" else "이미 등록한 사람 선택")
                                }
                                if (showExistingPeople) {
                                    continuingPeople.forEach { person ->
                                        Action("${PassengerNames.display(person.name)} · 계속 타고 있어요", vm.canEdit, secondary = true) {
                                            if (vm.userAction { continueRearRide(targetId, person.id) }) dialog = ""
                                        }
                                    }
                                    reboardingPeople.forEach { person ->
                                        Action("${PassengerNames.display(person.name)} · 다시 탔어요", vm.canEdit, secondary = true) {
                                            if (vm.userAction { reboard(targetId, person.id) }) dialog = ""
                                        }
                                    }
                                }
                            }
                            val blockers = vm.blockingPendingEvents(targetId)
                            if (blockers.isNotEmpty()) {
                                Text("먼저 정리할 이전 항목 ${blockers.size}건", fontWeight = FontWeight.Bold)
                                Text("잘못 뜬 항목이면 바로 넘기세요. 입력한 이름은 그대로 유지됩니다.", color = Muted)
                                blockers.forEach { previous ->
                                    Text("${previous.seat.label} · ${if (previous.occupied) "이전 탑승" else "이전 하차"} · ${distance(previous.effectiveKm)} km")
                                    Action("이 항목 오류로 넘기기", vm.canEdit, secondary = true) {
                                        vm.userAction { dismissPendingEvent(previous.id) }
                                    }
                                }
                                if (vm.blockingRiders(targetId).isEmpty()) {
                                    Action("이전 ${blockers.size}건 오류로 넘기고 등록", vm.canType && draftA.isNotBlank()) {
                                        if (vm.userAction(true) { boardNewAfterDismissing(targetId, draftA, blockers.map { it.id }.toSet()) }) dialog = ""
                                    }
                                }
                            }
                            val registeredBlockers = vm.blockingBoardingEvents(targetId)
                            registeredBlockers.forEach { previous ->
                                val previousName = dialogTrip?.people?.firstOrNull { it.id == previous.personId }?.let { PassengerNames.display(it.name) } ?: "이전 이용자"
                                Text("$previousName 님의 탑승 기록이 남아 있습니다.", color = Orange)
                                if (previousBoardToCancel == previous.id) {
                                    Text("${previousName}님의 이 탑승 구간을 정산에서 제외합니다. 실제로 탔던 기록이면 유지해주세요.", color = Muted)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Action("잘못된 등록 삭제", vm.canEdit, Modifier.weight(1f), secondary = true) {
                                            if (vm.userAction { cancelEvent(previous.id) }) previousBoardToCancel = ""
                                        }
                                        Action("기록 유지", vm.canEdit, Modifier.weight(1f), secondary = true) { previousBoardToCancel = "" }
                                    }
                                } else {
                                    Text("실제로 탔던 사람이면 하차 기록부터 확인해주세요.", color = Muted)
                                    Action("이전 등록이 오류예요", vm.canEdit, secondary = true) { previousBoardToCancel = previous.id }
                                }
                            }
                            val shownPeople = registeredBlockers.mapNotNull { it.personId }.toSet()
                            vm.blockingRiders(targetId).filter { it.id !in shownPeople }.forEach { person ->
                                Text("${PassengerNames.display(person.name)}님의 기존 이용 기록을 먼저 확인해주세요. 이 기록은 자동으로 삭제하지 않습니다.", color = Orange)
                                Action("닫고 이전 이용 기록 확인", vm.canEdit, secondary = true) { dialog = "" }
                            }
                            val target = dialogTrip?.events?.firstOrNull { it.id == targetId }
                            if (target?.effectiveKm == null) Text("탑승 거리가 없습니다. 이 창을 닫고 해당 카드의 거리·상세 보기에서 숫자만 입력해주세요.", color = Orange)
                        }
                        "EDIT_SEAT" -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(EventStatus.CONFIRMED to "사람", EventStatus.LUGGAGE to "짐", EventStatus.CANCELLED to "오류").forEach { (choice, label) ->
                                    FilterChip(
                                        selected = seatChoice == choice.name,
                                        onClick = { seatChoice = choice.name },
                                        label = { Text(label) },
                                        enabled = vm.canEdit,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (seatChoice == EventStatus.CONFIRMED.name) {
                                Input("이름", draftA, { draftA = it }, vm.canType)
                                if (needsSeatDistance) {
                                    Text("탑승 당시 거리 기록이 없어 확인한 값이 필요합니다.", color = Muted)
                                    Input("탑승 당시 누적 거리 (km)", draftB, { draftB = it }, vm.canType, true)
                                }
                            }
                            val original = dialogTrip?.events?.firstOrNull { it.id == targetId }
                            if (original?.status == EventStatus.CONFIRMED && seatChoice != EventStatus.CONFIRMED.name) {
                                Text("이 탑승 구간은 정산에서 제외됩니다.", color = Muted)
                            }
                            Text("저장을 누르면 변경됩니다.", color = Muted)
                        }
                        "RENAME" -> Input("이름", draftA, { draftA = it }, vm.canType)
                        "REBOARD" -> {
                            val eligible = vm.reboardCandidates(targetId)
                            if (eligible.isEmpty()) Text("이번 운행에서 하차한 이용자가 없습니다.")
                            eligible.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selectedPerson == person.id, { selectedPerson = person.id }, enabled = vm.canEdit)
                                Text(PassengerNames.display(person.name), fontSize = 20.sp)
                            } }
                        }
                        "EXIT_PERSON" -> {
                            Text("뒷좌석 안에서 자리를 옮길 수 있으므로 좌석 위치 대신 사람을 선택합니다. 하차 거리는 감지 당시 값으로 기록됩니다.")
                            if (dialogTrip?.events?.firstOrNull { it.id == targetId }?.rearOccupiedCountAfter == 1)
                                Text("추가 좌석 변화가 있으면 다음 카드에서 기존 이용자의 계속 탑승 또는 새 이용자의 탑승을 확인해주세요.", color = Muted)
                            val eligible = vm.exitCandidates(targetId)
                            if (eligible.isEmpty()) Text("연결할 뒷좌석 이용자가 없습니다. 이전 탑승 기록부터 확인해주세요.", color = Orange)
                            eligible.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selectedPerson == person.id, { selectedPerson = person.id }, enabled = vm.canEdit)
                                Text(PassengerNames.display(person.name), fontSize = 20.sp)
                            } }
                        }
                        "EVENT_DISTANCE" -> {
                            Text("실제 탑승·하차 때의 누적 거리를 입력하세요.")
                            Input("누적 거리 (km)", draftA, { draftA = it }, vm.canType, true)
                        }
                        "RIDE" -> {
                            Input("승차 누적 거리 (km)", draftA, { draftA = it }, vm.canType, true)
                            Input("하차 누적 거리 (km)", draftB, { draftB = it }, vm.canType, true)
                        }
                        "CANCEL_RIDE", "ISSUE" -> {
                            if (dialog == "CANCEL_RIDE") {
                                val ride = dialogTrip?.rides?.firstOrNull { it.id == targetId }
                                val personName = dialogTrip?.people?.firstOrNull { it.id == ride?.personId }?.let { PassengerNames.display(it.name) } ?: "이용자"
                                Text("$personName · ${distance(ride?.startKm)}~${distance(ride?.endKm)} km 구간을 정산에서 제외합니다.")
                            } else Text("이 기록의 내용을 확인한 것으로 표시합니다.")
                        }
                        "RATE" -> {
                            Text("이 운행의 모든 공유 구간을 새 단가로 다시 계산합니다.")
                            Input("차량 전체 원/km", draftA, { draftA = it }, vm.canType, true)
                        }
                        "MANUAL_RIDE" -> {
                            Text("센서 관측이 없는 기록을 직접 추가합니다. 확인한 사람·좌석·누적 거리만 입력해주세요.")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selectedPerson.isEmpty(), { selectedPerson = "" }, enabled = vm.canEdit)
                                Text("새 이용자")
                            }
                            dialogTrip?.people?.filterNot { it.isDriver }?.forEach { person ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selectedPerson == person.id, { selectedPerson = person.id }, enabled = vm.canEdit)
                                    Text(PassengerNames.display(person.name))
                                }
                            }
                            if (selectedPerson.isEmpty()) Input("새 이용자 이름", manualName, { manualName = it }, vm.canType)
                            Seat.values().forEach { seat -> Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(manualSeat == seat.name, { manualSeat = seat.name }, enabled = vm.canEdit)
                                Text(seat.label)
                            } }
                            Input("승차 누적 거리 (km)", draftA, { draftA = it }, vm.canType, true)
                            Input("하차 누적 거리 (km)", draftB, { draftB = it }, vm.canType, true)
                        }
                        "TOLL" -> {
                            Input("통행료 (원)", draftA, { draftA = it }, vm.canType, true)
                            dialogTrip?.people?.forEach { person ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(person.id in tollMembers, { checked ->
                                        tollMembers = ArrayList(if (checked) (tollMembers + person.id).distinct() else tollMembers - person.id)
                                    }, enabled = vm.canEdit && !person.isDriver)
                                    Text(PassengerNames.display(person.name) + if (person.isDriver) " · 운전자 필수" else "", fontSize = 20.sp)
                                }
                            }
                        }
                        "END" -> {
                            Text("현재 탑승자의 구간을 닫고 종료 거리를 고정합니다. 미확인 승하차는 다음 화면에서 검토할 수 있습니다.")
                            Text("현재 수신 거리: ${distance(vm.currentOdometer)} km", color = Mint)
                            if (vm.currentOdometer == null) {
                                Input("확인한 종료 누적 거리 (km)", draftA, { draftA = it }, vm.canType, true)
                                Text("센서 거리 누락은 검토 항목으로 남습니다.", color = Orange)
                            }
                        }
                        "PAUSE_OBSERVATION" -> Text("일시중지한 동안은 승하차와 거리 변화를 받지 않습니다. 다른 앱을 사용하는 동안에도 기록하려면 관측을 계속 켜두세요.")
                        else -> Text("삭제한 기록은 되돌릴 수 없습니다. 다른 운행 기록은 유지됩니다.")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = (if (typing) vm.canType else vm.canEdit) && readyToRegister && readyToEditSeat &&
                    (dialog !in setOf("EXIT_PERSON", "REBOARD") || selectedPerson.isNotBlank()), onClick = {
                    val completed = when (dialog) {
                        "NEW_PERSON" -> vm.userAction(true) { boardNew(targetId, draftA) }
                        "EDIT_SEAT" -> vm.userAction(typing) {
                            if (needsSeatDistance) correctEventDistance(targetId, Inputs.distance(draftB), "사용자가 좌석 수정에서 누락된 탑승 거리를 입력했습니다.")
                            correctBoarding(targetId, EventStatus.valueOf(seatChoice), draftA)
                        }
                        "REBOARD" -> vm.userAction { reboard(targetId, selectedPerson) }
                        "EXIT_PERSON" -> vm.userAction { confirmExit(targetId, selectedPerson) }
                        "EVENT_DISTANCE" -> vm.userAction(true) { correctEventDistance(targetId, Inputs.distance(draftA), "사용자가 화면에서 탑승·하차 거리를 수정했습니다.") }
                        "RIDE" -> vm.userAction(true) { updateRide(targetId, Inputs.distance(draftA), Inputs.distance(draftB), "사용자가 화면에서 이용 구간을 수정했습니다.") }
                        "CANCEL_RIDE" -> vm.userAction { cancelRide(targetId, "사용자가 화면에서 이 구간 취소를 확인했습니다.") }
                        "RENAME" -> vm.userAction(true) { renamePerson(targetId, draftA) }
                        "RATE" -> vm.userAction(true) { updateTripRate(draftA, "사용자가 화면에서 이번 운행 단가를 수정했습니다.") }
                        "MANUAL_RIDE" -> vm.userAction(true) { addManualRide(editItemId, selectedPerson.ifBlank { null }, manualName, Seat.valueOf(manualSeat), Inputs.distance(draftA), Inputs.distance(draftB), "사용자가 화면에서 누락된 탑승 구간을 추가했습니다.") }
                        "TOLL" -> vm.userAction(true) { upsertToll(editItemId, draftA, tollMembers.toSet(), draftNote) }
                        "ISSUE" -> vm.userAction { resolveIssue(targetId, "사용자가 기록 확인을 선택했습니다.") }
                        "END" -> if (vm.currentOdometer != null) vm.endTrip() else vm.userAction(true) {
                            addIssue("종료 시 센서 거리를 확보하지 못해 사용자가 종료 누적 거리를 입력했습니다. 모든 이용 구간을 검토해주세요.")
                            endTrip(Inputs.distance(draftA))
                        }
                        "DELETE_TOLL" -> vm.userAction { removeToll(targetId) }
                        "DELETE_HISTORY" -> vm.userAction { deleteHistory(targetId) }
                        "DISCARD" -> vm.userAction { discardCurrentTrip() }
                        "PAUSE_OBSERVATION" -> { vm.stop(); true }
                        else -> false
                    }
                    if (completed) { if (dialog == "DISCARD" || dialog == "DELETE_HISTORY" && vm.snapshot.currentTrip == null) vm.resetDetection(); dialog = "" }
                }) { Text(when (dialog) { "NEW_PERSON" -> "새 사람 등록"; "EDIT_SEAT" -> "저장"; else -> "확인" }, fontSize = 20.sp) }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("닫기", fontSize = 20.sp) } }
        )
    }
    if (dialog.isEmpty()) vm.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, title = { Text("확인이 필요합니다") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissError) { Text("확인") } }) }
}

@Composable
private fun SettingsPanel(vm: CarpoolViewModel, initial: Boolean, name: String, onName: (String) -> Unit, rate: String, onRate: (String) -> Unit, onDone: () -> Unit) {
    Panel {
        Text(if (initial) "첫 운행을 준비하세요" else "다음 운행에 적용할 설정", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Input("운전자 이름", name, onName, vm.canType)
        Input("차량 전체 1km당 비용 (원/km)", rate, onRate, vm.canType, true)
        Text("입력한 공동 비용을 운전자와 동승자가 구간별로 나눕니다. 0원도 설정할 수 있습니다.", color = Muted)
        if (!initial) Text("진행 중이거나 끝난 운행의 단가는 바뀌지 않습니다.", color = Orange)
        Action(if (initial) "저장하고 카풀 감지 시작" else "설정 저장", vm.canType) { if (vm.configure(name, rate)) onDone() }
    }
}

@Composable
private fun PassengerTile(vm: CarpoolViewModel, registration: SeatRegistration, modifier: Modifier, onEdit: (SeatRegistration) -> Unit) {
    val event = registration.pendingEvent
    val name = registration.person?.let { PassengerNames.display(it.name) }
    val currentOccupancy = vm.seats[registration.seat]
    val classification = registration.boardingEvent?.status
    val headline = name ?: if (currentOccupancy == false) "비어 있음" else when (classification) {
        EventStatus.LUGGAGE -> "짐"
        EventStatus.CANCELLED -> "오류로 처리"
        EventStatus.PENDING -> "확인 필요"
        else -> when (currentOccupancy) { true -> "점유 감지"; false -> "비어 있음"; null -> "상태 미확인" }
    }
    val state = buildString {
        append(seatValueLabel(currentOccupancy))
        when {
            event != null -> append("\n").append(pendingObservationLabel(event.occupied, currentOccupancy))
            name != null && currentOccupancy == false -> append("\n하차 여부 확인")
            name == null && classification == EventStatus.LUGGAGE && currentOccupancy == false -> append("\n최근 처리: 짐")
            name == null && classification == EventStatus.CANCELLED && currentOccupancy == false -> append("\n최근 처리: 오류")
            name == null && classification == EventStatus.PENDING -> append("\n사람·짐 여부 확인")
            name == null && classification == null && currentOccupancy == true -> append("\n탑승 감지 확인 중")
        }
    }
    SeatTile(registration.seat.label, headline, state,
        if (event != null || currentOccupancy == null) Orange else if (currentOccupancy == true) Mint else Muted,
        modifier, editEnabled = vm.canEdit && registration.boardingEvent != null, onEdit = { onEdit(registration) })
}

@Composable
private fun SeatTile(label: String, name: String, status: String, accent: Color, modifier: Modifier, detail: String? = null,
    editEnabled: Boolean = false, onEdit: (() -> Unit)? = null) {
    Card(modifier.heightIn(min = 160.dp), colors = CardDefaults.cardColors(containerColor = SurfaceNavy)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Muted, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (onEdit != null) TextButton(onClick = onEdit, enabled = editEnabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("수정", fontSize = 20.sp)
                }
            }
            Text(name, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(status, color = accent, fontSize = 20.sp)
            if (detail != null) Text(detail, color = Muted, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ResultPanel(result: SettlementResult, simulation: Boolean = false) {
    if (simulation) InfoBanner("모의 데이터로 만든 정산 · 실제 비용 청구용이 아닙니다", Orange)
    SectionTitle("정산 결과")
    Panel {
        Text("운전자가 받을 금액", color = Muted, fontSize = 20.sp)
        Text(won(result.receivableWon), color = Mint, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("공유 거리 ${distance(result.sharedDistanceKm)} km · 차량 전체 ${result.rateWonPerKm}원/km", color = Muted)
    }
    result.people.forEach { person ->
        Panel {
            Text(person.name + if (person.isDriver) " · 운전자" else "", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("이용 거리 ${distance(person.distanceKm)} km", color = Muted)
            AmountRow("차량비", person.vehicleWon)
            AmountRow("통행료", person.tollWon)
            HorizontalDivider(color = Muted.copy(alpha = .2f))
            AmountRow(if (person.isDriver) "본인 부담" else "운전자에게 보낼 금액", person.totalWon, true)
        }
    }
    Panel {
        AmountRow("전체 차량비", result.totalVehicleWon)
        AmountRow("전체 통행료", result.totalTollWon)
        AmountRow("전체 부담 합계", result.totalWon, true)
        Text("원 미만 잔액은 운전자 부담에 반영했습니다. 실제 송금 기능은 제공하지 않습니다.", color = Muted)
    }
}

@Composable
private fun DeveloperPanel(vm: CarpoolViewModel) {
    var simulationKm by rememberSaveable { mutableStateOf("") }
    Panel {
        SectionTitle("개발용 차량 입력")
        Text(vm.status, color = Muted)
        Text("현재 좌석 수신값", fontWeight = FontWeight.Bold)
        Seat.values().forEach { seat ->
            val area = when (seat) {
                Seat.FRONT_PASSENGER -> "ROW_1_RIGHT"
                Seat.REAR_LEFT -> "ROW_2_LEFT"
                Seat.REAR_RIGHT -> "ROW_2_RIGHT"
            }
            val input = if (vm.simulated) "모의 값" else "SDK 수신값"
            Text("${seat.label} · $area\n$input: ${vm.seats[seat]?.toString() ?: "미확인"} · ${seatReceiptLabel(vm, seat)}",
                color = Muted, fontSize = 18.sp)
        }
        Text("모의 입력은 실제 차량 데이터가 아닙니다. 실제 차량의 P단·정차 확인과 플랫폼 UX 제한은 계속 적용됩니다.", color = Orange)
        Action(if (vm.simulated) "실제 차량 입력으로 전환" else "모의 차량 입력 시작", vm.canUseDeveloperTools, secondary = true) { vm.enableSimulation(!vm.simulated) }
        if (vm.simulated) {
            Input("모의 누적 거리 직접 설정 (km)", simulationKm, { simulationKm = it }, vm.canUseDeveloperTools && vm.canType, true)
            Action("모의 거리 적용", vm.canUseDeveloperTools && vm.canType, secondary = true) { vm.setSimulationOdometer(simulationKm) }
            Text("누적 ${distance(vm.currentOdometer)} km · ${vm.currentSpeed?.let { if (it == 0.0) "정지" else "이동 중" } ?: "속도 미확인"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("+10 km", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateDistance("10") }
                Action("+20 km", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateDistance("20") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("모의 정지", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateMotion(false) }
                Action("모의 이동", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateMotion(true) }
            }
            Seat.values().forEach { seat ->
                Text(seat.label, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("승차", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateSeat(seat, true) }
                    Action("하차", enabled = vm.canUseDeveloperTools, modifier = Modifier.weight(1f), secondary = true) { vm.simulateSeat(seat, false) }
                }
            }
            Text("문 열기·닫기와 좌석 변화를 함께 공급합니다. 문 닫힘 기준 5초 연결과 3초 안정 확인을 적용하며, 뒷좌석은 관련 변화를 모아 최대 약 8초 뒤 표시합니다.", color = Muted)
        }
        if (vm.diagnostics.isNotEmpty()) {
            Text("연결 진단", fontWeight = FontWeight.Bold)
            vm.diagnostics.takeLast(5).forEach { Text(it, color = Muted, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun Input(label: String, value: String, changed: (String) -> Unit, enabled: Boolean, numeric: Boolean = false) {
    OutlinedTextField(value, changed, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 20.sp), keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
private fun Action(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, secondary: Boolean = false, action: () -> Unit) {
    if (secondary) OutlinedButton(action, modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled) { Text(label, fontSize = 20.sp) }
    else Button(action, modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceNavy)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
@Composable private fun InfoBanner(text: String, color: Color) { Surface(color = color.copy(alpha = .12f), shape = MaterialTheme.shapes.medium) { Text(text, Modifier.fillMaxWidth().padding(12.dp), color = color, fontSize = 20.sp) } }
@Composable private fun AmountRow(label: String, amount: BigInteger, bold: Boolean = false) { Column(Modifier.fillMaxWidth()) { Text(label, color = Muted); Text(won(amount), fontSize = if (bold) 24.sp else 20.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal) } }
private fun won(value: BigInteger): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원"
private fun distance(value: BigDecimal?): String = value?.stripTrailingZeros()?.toPlainString() ?: "미확인"
private fun seatValueLabel(occupied: Boolean?): String = when (occupied) {
    true -> "현재 점유 감지"
    false -> "현재 비어 있음"
    null -> "현재 상태 미확인"
}
private fun pendingObservationLabel(observedOccupied: Boolean, currentOccupied: Boolean?): String = when {
    observedOccupied && currentOccupied == false -> "이전 탑승 감지 · 현재 비어 있음"
    observedOccupied && currentOccupied == null -> "승차 확인 · 현재 상태 미확인"
    observedOccupied -> "누가 탔나요?"
    currentOccupied == true -> "이전 하차 감지 · 현재 점유"
    currentOccupied == null -> "하차 확인 · 현재 상태 미확인"
    else -> "하차 감지 · 현재 비어 있음"
}
private fun seatReceiptLabel(vm: CarpoolViewModel, seat: Seat): String {
    val input = if (vm.simulated) "앱 내부 모의 입력" else "Pleos 입력"
    val receivedAt = vm.seatReceivedAt[seat]
    if (receivedAt == null || receivedAt < 0L) return "$input · 수신 이력 없음"
    val elapsed = (vm.observationNowMillis - receivedAt).coerceAtLeast(0L)
    return "$input · ${if (elapsed < 1_000L) "방금 수신" else "${elapsed / 1_000L}초 전 수신"}"
}
private fun evidenceReason(reason: String): String = reason
    .replace("INITIAL_OCCUPANCY", "감지를 시작할 때 좌석이 사용 중이었습니다. 아래에서 이용자 또는 짐 여부를 확인해주세요. 감지 시작 이전 거리는 포함하지 않습니다.")
    .replace("NOT_A_PASSENGER_EXIT", "사람이 등록되지 않은 좌석의 비어 있음 관측입니다. 하차 정산 대상이 아닙니다.")
    .replace("STOP_NOT_CONFIRMED", "좌석 변화 당시 정지가 확인되지 않았습니다. 실제 승하차 여부를 확인해주세요.")
    .replace("NO_RELATED_DOOR", "관련 문이 열렸다 닫힌 뒤 5초 안의 탑승 조건을 확인하지 못했습니다. 실제 승하차인지 확인해주세요.")
    .replace("MISSING_DISTANCE", "당시 누적 거리가 없습니다. 확인한 거리로 수정해주세요.")
    .replace("SEAT_SIGNAL_LOST", "좌석 신호 연결이 끊겼습니다.")
    .replace("SEAT_SIGNAL_UNKNOWN", "좌석 상태 미확인")
    .replace("OBSERVATION_GAP", "차량 관측 공백이 있습니다. 공백 중 승하차와 구간을 확인해주세요.")
    .replace("OUT_OF_ORDER_SIGNAL", "신호 수신 순서가 맞지 않아 일부 입력을 제외했습니다.")
    .replace("REAR_CONTINUATION", "기존 뒷좌석 이용자가 계속 탑승한 것으로 확인했습니다.")
    .replace("REAR_GROUP_UNSTABLE", "뒷좌석 변화가 아직 불안정하거나 일부 신호가 미확인입니다. 탑승·하차 대상을 확인해주세요.")
