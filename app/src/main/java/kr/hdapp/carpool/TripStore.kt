package kr.hdapp.carpool

import android.content.Context
import android.util.AtomicFile
import kr.hdapp.carpool.core.AppSnapshot
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Private, device-local state. This format is never accepted from external files. */
class TripStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("carpool-state-v1.bin"))

    private data class Envelope(val version: Int, val snapshot: AppSnapshot) : Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    fun exists(): Boolean = file.baseFile.exists() || file.baseFile.resolveSibling(file.baseFile.name + ".bak").exists()

    fun load(): AppSnapshot? {
        if (!exists()) return null
        return file.openRead().use { input ->
            ObjectInputStream(input).use {
                val envelope = it.readObject() as? Envelope
                    ?: error("저장 형식을 확인할 수 없습니다. 기존 파일은 보존했습니다.")
                require(envelope.version == 1) { "지원하지 않는 기록 버전입니다. 기존 파일은 보존했습니다." }
                envelope.snapshot
            }
        }
    }

    fun save(snapshot: AppSnapshot) {
        val out = file.startWrite()
        try {
            // Do not close the wrapper before AtomicFile performs fsync and the rename.
            val objects = ObjectOutputStream(out)
            objects.writeObject(Envelope(1, snapshot))
            objects.flush()
            file.finishWrite(out)
        } catch (failure: Throwable) {
            file.failWrite(out)
            throw failure
        }
    }
}
