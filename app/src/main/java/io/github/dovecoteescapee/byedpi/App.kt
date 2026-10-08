package io.github.dovecoteescapee.byedpi

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class App : Application() {

    companion object {
        private const val TAG = "ByeDPIApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Setup global uncaught exception handler to prevent silent crashes and improve stability
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Apply Material You Dynamic Colors if enabled or selected
        try {
            val accent = getPreferences().getString("accent_color", "dynamic") ?: "dynamic"
            if (accent == "dynamic" && DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dynamic colors", e)
        }

        try {
            if (io.github.dovecoteescapee.byedpi.security.TamperGuard.isModified(this)) {
                Log.w(TAG, "Running unofficial or modified build.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check exception", e)
        }
    }
}
