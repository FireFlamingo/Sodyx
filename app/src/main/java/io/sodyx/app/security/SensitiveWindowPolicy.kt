package io.sodyx.app.security

import android.view.Window
import android.view.WindowManager

/** Applies Android's secure-window flag while private conversations are visible. */
object SensitiveWindowPolicy {
    fun apply(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun clear(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
