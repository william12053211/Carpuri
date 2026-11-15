package kr.hdapp.carpool.core

/** Only the exact temporary name reported by the user is treated as a placeholder. */
object PassengerNames {
    fun isKnownPlaceholder(name: String): Boolean = name.trim().equals("fdfd", ignoreCase = true)

    fun display(name: String): String = name.trim().takeIf {
        it.isNotEmpty() && !isKnownPlaceholder(it)
    } ?: "이름 입력"

    fun requireValid(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && !isKnownPlaceholder(normalized)) { "이름을 다시 입력해주세요." }
        return normalized
    }
}
