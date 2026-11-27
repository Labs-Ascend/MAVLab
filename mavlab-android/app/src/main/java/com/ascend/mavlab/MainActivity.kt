package com.ascend.mavlab

import android.os.Bundle
import android.graphics.Color
import android.content.res.Configuration
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ascend.mavlab.core.ui.theme.MAVLabTheme
import com.ascend.mavlab.feature.navigation.MavLabAppShell
import com.ascend.mavlab.service.SimulationService

class MainActivity : ComponentActivity() {
    private var fullScreen by mutableStateOf(false)
    private var keyboardVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val preferences = getSharedPreferences("mavlab_app", MODE_PRIVATE)
        fullScreen = preferences.getBoolean("full_screen", false)
        setContent {
            MAVLabTheme {
                MavLabAppShell(fullScreen = fullScreen, onFullScreenChange = {
                    fullScreen = it
                    preferences.edit().putBoolean("full_screen", it).apply()
                    applyDisplayMode()
                })
            }
        }
        // Do not replace DecorView's inset handling: it must resize the window and
        // update accessibility bounds when bars hide. Observe IME on content instead.
        val content = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboardVisible != visible) {
                keyboardVisible = visible
                applyDisplayMode()
            }
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun applyDisplayMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (fullScreen && hasWindowFocus() && !isInMultiWindowMode && !keyboardVisible) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        applyDisplayMode()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyDisplayMode()
    }

    override fun onStart() {
        super.onStart()
        SimulationService.startFromVisibleUi(this)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            if (isFinishing) {
                stopService(SimulationService.stopIntent(this))
            } else {
                startService(SimulationService.appBackgroundedIntent(this))
            }
        }
        super.onStop()
    }
}
