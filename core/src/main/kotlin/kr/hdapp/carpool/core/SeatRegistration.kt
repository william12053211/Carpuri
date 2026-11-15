package kr.hdapp.carpool.core

/** UI projection only. A seat's live sensor value remains separate from these reviewed facts. */
data class SeatRegistration(
    val seat: Seat,
    /** The original registration being edited, including after an explicitly reviewed rear seat move. */
    val boardingEvent: BoardingEvent?,
    val person: Person?,
    val pendingEvent: BoardingEvent?
)
