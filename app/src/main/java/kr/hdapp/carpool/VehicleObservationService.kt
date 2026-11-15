package kr.hdapp.carpool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Local vehicle signal observation continues when the UI is hidden or its task is removed. */
class VehicleObservationService : Service() {
    private val controller get() = (application as CarpoolApplication).controller
    private var foregroundReady = false
    private var ownsObservation = false

    private fun enterForeground(): Boolean {
        if (foregroundReady) return true
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "카푸리 차량 관측", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1,
            Intent(this, VehicleObservationService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_carpool)
            .setContentTitle("카푸리 차량 관측 중")
            .setContentText("다른 앱을 보는 동안에도 승하차·거리를 기록합니다")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "관측 일시 중지", pause)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            foregroundReady = true
        } catch (failure: Exception) {
            controller.observationStartFailed(failure.message.orEmpty())
            stopSelf()
        }
        return foregroundReady
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            if (ownsObservation) controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Ignore a system replay or an unrequested service creation before loading records,
        // showing a notification or binding to vehicle signals. A fresh app opening authorizes
        // this process session; that authorization is deliberately not restored from disk.
        if (intent?.action != ACTION_START) {
            if (!ownsObservation) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!(application as CarpoolApplication).observationStartAllowed) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY
        ownsObservation = true
        controller.startObserving()
        // Continue while navigation is foreground, but never resurrect after process death.
        // A later user opening starts a fresh session and records any observation gap.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ownsObservation) controller.stopObserving()
        if (foregroundReady) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        ownsObservation = false
        foregroundReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_START = "kr.hdapp.carpool.START_OBSERVATION"
        private const val CHANNEL = "carpool_observation"
        private const val NOTIFICATION_ID = 1201
        private const val ACTION_PAUSE = "kr.hdapp.carpool.PAUSE_OBSERVATION"
    }
}
