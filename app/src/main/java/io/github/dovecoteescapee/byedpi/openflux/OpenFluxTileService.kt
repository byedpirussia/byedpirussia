package io.github.dovecoteescapee.byedpi.openflux

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.vless.VlessVpnService
import io.github.dovecoteescapee.byedpi.warp.WarpVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@RequiresApi(Build.VERSION_CODES.N)
class OpenFluxTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            OpenFluxVpnService.isRunning.collectLatest { running ->
                updateStatus(running)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
    }

    private fun updateStatus(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "OpenFLUX"
        tile.updateTile()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) return

        unlockAndRun {
            handleClick()
        }
    }

    private fun handleClick() {
        if (OpenFluxVpnService.isRunning.value) {
            OpenFluxVpnService.stop(this)
            updateStatus(false)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                TileServiceCompat.startActivityAndCollapse(
                    this, PendingIntentActivityWrapper(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT, false
                    )
                )
                return
            }

            // Stop other VPNs
            val (byedpiStatus, byedpiMode) = appStatus
            if (byedpiStatus == AppStatus.Running && byedpiMode == Mode.VPN) {
                ServiceManager.stop(this)
            }
            if (WarpVpnService.isRunning.value) {
                WarpVpnService.stop(this)
            }
            if (VlessVpnService.isRunning.value) {
                VlessVpnService.stop(this)
            }

            OpenFluxVpnService.start(this)
            updateStatus(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
