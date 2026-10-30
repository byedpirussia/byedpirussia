package io.github.dovecoteescapee.byedpi.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.island.ServiceSwitchController

@RequiresApi(Build.VERSION_CODES.N)
class StopAllTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val activeMode = ServiceSwitchController.getActiveMode()
        val hasActive = (activeMode != ServiceSwitchController.MODE_STOP)
        
        tile.label = getString(R.string.tile_stop_all)
        tile.state = if (hasActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_UNAVAILABLE) return

        unlockAndRun {
            ServiceSwitchController.stopAll(this)
            Toast.makeText(this, getString(R.string.all_services_stopped), Toast.LENGTH_SHORT).show()
            updateTileState()
        }
    }
}
