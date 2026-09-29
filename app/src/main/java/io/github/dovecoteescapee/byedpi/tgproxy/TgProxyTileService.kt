package io.github.dovecoteescapee.byedpi.tgproxy

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@RequiresApi(Build.VERSION_CODES.N)
class TgProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            TgWsProxyService.isRunning.collectLatest { running ->
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
        tile.label = getString(io.github.dovecoteescapee.byedpi.R.string.tile_tg_proxy)
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
        if (TgWsProxyService.isRunning.value) {
            TgWsProxyService.stop(this)
            updateStatus(false)
        } else {
            TgWsProxyService.start(this)
            updateStatus(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
