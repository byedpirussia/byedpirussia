package io.github.dovecoteescapee.byedpi.island

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxManager
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxVpnService
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode
import io.github.dovecoteescapee.byedpi.vless.VlessManager
import io.github.dovecoteescapee.byedpi.vless.VlessVpnService
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService

object ServiceSwitchController {
    const val MODE_BYEDPI = "byedpi"
    const val MODE_WARP = "warp"
    const val MODE_VLESS = "vless"
    const val MODE_OPENFLUX = "openflux"
    const val MODE_STOP = "stop"

    fun getActiveMode(): String {
        val (byedpiStatus, _) = appStatus
        return when {
            WarpVpnService.isRunning.value -> MODE_WARP
            VlessVpnService.isRunning.value -> MODE_VLESS
            OpenFluxVpnService.isRunning.value -> MODE_OPENFLUX
            byedpiStatus == AppStatus.Running -> MODE_BYEDPI
            else -> MODE_STOP
        }
    }

    fun getActiveTitle(context: Context): String {
        return when (getActiveMode()) {
            MODE_WARP -> "WARP"
            MODE_VLESS -> {
                val cfg = VlessManager.getSelectedConfig(context)
                cfg?.name ?: "VLESS"
            }
            MODE_OPENFLUX -> {
                val cfg = OpenFluxManager.getSelectedConfig(context)
                cfg?.name ?: "OpenFLUX"
            }
            MODE_BYEDPI -> "ByeDPI"
            else -> "Отключено"
        }
    }

    fun switchTo(context: Context, targetMode: String) {
        val appContext = context.applicationContext
        if (targetMode != MODE_STOP && !io.github.dovecoteescapee.byedpi.security.TamperGuard.verifyExecutionPermitted(appContext)) {
            Toast.makeText(appContext, io.github.dovecoteescapee.byedpi.R.string.tamper_guard_blocked, Toast.LENGTH_LONG).show()
            return
        }

        when (targetMode) {
            MODE_STOP -> {
                stopAll(appContext)
                Toast.makeText(appContext, "Все службы остановлены", Toast.LENGTH_SHORT).show()
            }

            MODE_BYEDPI -> {
                stopOthers(appContext, except = MODE_BYEDPI)
                val (status, _) = appStatus
                if (status == AppStatus.Halted) {
                    when (appContext.getPreferences().mode()) {
                        Mode.VPN -> {
                            val prepare = VpnService.prepare(appContext)
                            if (prepare != null) {
                                openMainWithAction(appContext, "byedpi")
                            } else {
                                ServiceManager.start(appContext, Mode.VPN)
                                Toast.makeText(appContext, "ByeDPI включен", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Mode.Proxy -> {
                            ServiceManager.start(appContext, Mode.Proxy)
                            Toast.makeText(appContext, "ByeDPI (Прокси) включен", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            MODE_WARP -> {
                stopOthers(appContext, except = MODE_WARP)
                if (!WarpVpnService.isRunning.value) {
                    val prepare = VpnService.prepare(appContext)
                    if (prepare != null) {
                        openMainWithAction(appContext, "warp")
                    } else {
                        WarpVpnService.start(appContext)
                        Toast.makeText(appContext, "Подключение WARP...", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            MODE_VLESS -> {
                stopOthers(appContext, except = MODE_VLESS)
                if (!VlessVpnService.isRunning.value) {
                    val cfg = VlessManager.getSelectedConfig(appContext)
                    if (cfg == null) {
                        Toast.makeText(appContext, "Сначала добавьте VLESS сервер", Toast.LENGTH_SHORT).show()
                        openMainWithAction(appContext, "vless")
                        return
                    }
                    val prepare = VpnService.prepare(appContext)
                    if (prepare != null) {
                        openMainWithAction(appContext, "vless")
                    } else {
                        VlessVpnService.start(appContext)
                        Toast.makeText(appContext, "Подключение VLESS: ${cfg.name}...", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            MODE_OPENFLUX -> {
                stopOthers(appContext, except = MODE_OPENFLUX)
                if (!OpenFluxVpnService.isRunning.value) {
                    val cfg = OpenFluxManager.getSelectedConfig(appContext)
                    if (cfg == null) {
                        Toast.makeText(appContext, "Сначала настройте OpenFLUX", Toast.LENGTH_SHORT).show()
                        openMainWithAction(appContext, "openflux")
                        return
                    }
                    if (cfg.routingMode == "vpn") {
                        val prepare = VpnService.prepare(appContext)
                        if (prepare != null) {
                            openMainWithAction(appContext, "openflux")
                        } else {
                            OpenFluxVpnService.start(appContext)
                            Toast.makeText(appContext, "Подключение OpenFLUX: ${cfg.name}...", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        OpenFluxVpnService.start(appContext)
                        Toast.makeText(appContext, "Запуск OpenFLUX SOCKS5: ${cfg.name}...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun stopOthers(context: Context, except: String) {
        val (status, _) = appStatus
        if (except != MODE_BYEDPI && status == AppStatus.Running) {
            ServiceManager.stop(context)
        }
        if (except != MODE_WARP && WarpVpnService.isRunning.value) {
            WarpVpnService.stop(context)
        }
        if (except != MODE_VLESS && VlessVpnService.isRunning.value) {
            VlessVpnService.stop(context)
        }
        if (except != MODE_OPENFLUX && OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(context)
        }
    }

    fun stopAll(context: Context) {
        val (status, _) = appStatus
        if (status == AppStatus.Running) {
            ServiceManager.stop(context)
        }
        if (WarpVpnService.isRunning.value) {
            WarpVpnService.stop(context)
        }
        if (VlessVpnService.isRunning.value) {
            VlessVpnService.stop(context)
        }
        if (OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(context)
        }
    }

    private fun openMainWithAction(context: Context, action: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("trigger_mode", action)
        }
        context.startActivity(intent)
    }
}
