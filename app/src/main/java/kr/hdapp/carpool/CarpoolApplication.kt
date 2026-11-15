package kr.hdapp.carpool

import android.app.Application

class CarpoolApplication : Application() {
    // A single engine, store and source are shared by every Activity and the service.
    private val controllerDelegate = lazy { CarpoolViewModel(this) }
    val controller: CarpoolViewModel get() = controllerDelegate.value

    // Reading this gate must not load records or initialize a controller on an unsolicited
    // service start. Only a visible app opening/user action enables the in-memory request.
    internal val observationStartAllowed: Boolean
        get() = controllerDelegate.isInitialized() && controllerDelegate.value.observationRequested
}
