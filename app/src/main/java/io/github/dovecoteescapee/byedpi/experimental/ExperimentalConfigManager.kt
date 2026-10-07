package io.github.dovecoteescapee.byedpi.experimental

import android.content.Context
import android.content.SharedPreferences

object ExperimentalConfigManager {
    private const val PREFS_NAME = "experimental_preferences"

    private const val KEY_BATTERY_SAVER = "exp_battery_saver"
    private const val KEY_BLOCK_QUIC = "exp_block_quic"
    private const val KEY_TCP_FAST_OPEN = "exp_tcp_fast_open"
    private const val KEY_MTU_CLAMP = "exp_mtu_clamp"
    private const val KEY_AGGRESSIVE_KEEPALIVE = "exp_aggressive_keepalive"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBatterySaverEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BATTERY_SAVER, false)

    fun setBatterySaverEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_SAVER, enabled).apply()
    }

    fun isBlockQuicEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BLOCK_QUIC, false)

    fun setBlockQuicEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCK_QUIC, enabled).apply()
    }

    fun isTcpFastOpenEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_TCP_FAST_OPEN, false)

    fun setTcpFastOpenEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TCP_FAST_OPEN, enabled).apply()
        // Also update ByeDPI core preferences if active
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("byedpi_tcp_fast_open", enabled).apply()
    }

    fun isMtuClampEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_MTU_CLAMP, false)

    fun setMtuClampEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MTU_CLAMP, enabled).apply()
    }

    fun isAggressiveKeepAliveEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_AGGRESSIVE_KEEPALIVE, false)

    fun setAggressiveKeepAliveEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AGGRESSIVE_KEEPALIVE, enabled).apply()
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("byedpi_tcp_fast_open", false).apply()
    }

    fun getActiveCount(context: Context): Int {
        var count = 0
        if (isBatterySaverEnabled(context)) count++
        if (isBlockQuicEnabled(context)) count++
        if (isTcpFastOpenEnabled(context)) count++
        if (isMtuClampEnabled(context)) count++
        if (isAggressiveKeepAliveEnabled(context)) count++
        return count
    }
}
