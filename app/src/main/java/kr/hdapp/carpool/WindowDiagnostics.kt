package kr.hdapp.carpool

import android.annotation.TargetApi
import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowInsets

/** Geometry only: never records people, trips, input text, or vehicle data. */
fun logWindowDiagnostics(activity: Activity) {
    if (!BuildConfig.DEBUG) return

    val decor = activity.window.decorView
    val location = IntArray(2)
    decor.getLocationOnScreen(location)
    val visible = Rect()
    val hasVisibleRect = decor.getGlobalVisibleRect(visible)
    val metrics = activity.resources.displayMetrics
    val configuration = activity.resources.configuration
    Log.i("CarpoolWindow", "decorScreen=(${location[0]},${location[1]}) " +
        "decorPx=${decor.width}x${decor.height} " +
        "visibleRect=${if (hasVisibleRect) visible.toShortString() else "none"} " +
        "resourcePx=${metrics.widthPixels}x${metrics.heightPixels} " +
        "density=${metrics.density} densityDpi=${metrics.densityDpi} " +
        "fontScale=${configuration.fontScale} orientation=${configuration.orientation} " +
        "displayId=${decor.display?.displayId} " +
        "multiWindow=${Build.VERSION.SDK_INT >= 24 && activity.isInMultiWindowMode}")

    if (Build.VERSION.SDK_INT >= 30) {
        val windowMetrics = activity.windowManager.currentWindowMetrics
        Log.i("CarpoolWindow", "windowBounds=${windowMetrics.bounds.toShortString()} " +
            describeInsets("metricsInsets", windowMetrics.windowInsets))
        val rootInsets = decor.rootWindowInsets
        Log.i("CarpoolWindow", rootInsets?.let { describeInsets("rootInsets", it) }
            ?: "rootInsets=unavailable")
    } else {
        @Suppress("DEPRECATION")
        val rootInsets = decor.rootWindowInsets
        if (rootInsets == null) {
            Log.i("CarpoolWindow", "rootInsets=unavailable")
        } else {
            @Suppress("DEPRECATION")
            val legacy = "rootInsets system=(${rootInsets.systemWindowInsetLeft}," +
                "${rootInsets.systemWindowInsetTop},${rootInsets.systemWindowInsetRight}," +
                "${rootInsets.systemWindowInsetBottom}) stable=(${rootInsets.stableInsetLeft}," +
                "${rootInsets.stableInsetTop},${rootInsets.stableInsetRight},${rootInsets.stableInsetBottom})"
            val cutout = if (Build.VERSION.SDK_INT >= 28) rootInsets.displayCutout?.let {
                " cutoutSafe=(${it.safeInsetLeft},${it.safeInsetTop},${it.safeInsetRight},${it.safeInsetBottom})"
            } ?: " cutoutSafe=none" else " cutoutSafe=unsupported"
            Log.i("CarpoolWindow", legacy + cutout + " imeSeparate=unavailableBeforeApi30")
        }
    }
}

/** Called only on API 30+. Ignoring IME visibility is unsupported by Android; do not query it. */
@TargetApi(30)
private fun describeInsets(label: String, insets: WindowInsets): String {
    val bars = WindowInsets.Type.systemBars()
    val cutout = WindowInsets.Type.displayCutout()
    val ime = WindowInsets.Type.ime()
    return "$label systemBars=${insets.getInsets(bars)} " +
        "systemBarsIgnoringVisibility=${insets.getInsetsIgnoringVisibility(bars)} " +
        "displayCutout=${insets.getInsets(cutout)} " +
        "displayCutoutIgnoringVisibility=${insets.getInsetsIgnoringVisibility(cutout)} " +
        "ime=${insets.getInsets(ime)} imeVisible=${insets.isVisible(ime)} " +
        "statusVisible=${insets.isVisible(WindowInsets.Type.statusBars())} " +
        "navigationVisible=${insets.isVisible(WindowInsets.Type.navigationBars())} " +
        "consumed=${insets.isConsumed}"
}
