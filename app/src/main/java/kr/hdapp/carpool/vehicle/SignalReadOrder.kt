package kr.hdapp.carpool.vehicle

/** Fences asynchronous reads against more recent subscription updates, failures and expiry. */
internal class SignalReadOrder {
    private val versions = mutableMapOf<String, Long>()
    @Synchronized fun version(key: String): Long = versions[key] ?: 0L
    @Synchronized fun changed(key: String): Long = (version(key) + 1L).also { versions[key] = it }
    @Synchronized fun isCurrent(key: String, version: Long): Boolean = version(key) == version
}
