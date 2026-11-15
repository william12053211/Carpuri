package kr.hdapp.carpool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kr.hdapp.carpool.ui.CarpoolApp

class MainActivity : ComponentActivity() {
    private lateinit var model: CarpoolViewModel
    private val observationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (::model.isInitialized) {
            if (results["android.car.permission.CAR_SPEED"] == true) model.resetDetection()
            if (results["android.car.permission.CAR_SPEED"] == false) {
                model.onProblem("속도 권한이 없습니다. P단 신호와 플랫폼 입력 제한을 함께 확인합니다. 정차 시 등록하려면 차량 속도 권한을 허용해주세요.")
            }
            if (Build.VERSION.SDK_INT >= 33 && results[Manifest.permission.POST_NOTIFICATIONS] == false) {
                model.onProblem("알림 권한이 없어 관측 알림이 숨겨질 수 있습니다. 앱에서 관측 상태와 일시 중지를 확인할 수 있습니다.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose owns system-bar/IME insets at the app root on API 34 as well as 35.
        enableEdgeToEdge()
        model = (application as CarpoolApplication).controller
        setContent { CarpoolApp(model) }
        if (savedInstanceState == null) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
                needed += "android.car.permission.CAR_SPEED"
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (needed.isNotEmpty()) observationPermissions.launch(needed.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        model.onAppOpened()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.DEBUG) {
            window.decorView.post { logWindowDiagnostics(this) }
        }
    }
    // No onStop stop: the foreground service owns signal collection across Activity transitions.
}
