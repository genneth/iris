package io.github.genneth.pupil

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile: tap toggles broadcasting, long-press opens the main app,
 * which doubles as the settings surface for the feature.
 *
 * Declared as an active tile: the system only binds us for clicks (and
 * onTileAdded/Removed); PupilService requests a listening window via
 * [TileService.requestListeningState] whenever the broadcast status changes.
 */
class PupilTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        applyPresentation(presentTile(PupilState.state.value))
    }

    override fun onClick() {
        super.onClick()
        val ui = PupilState.state.value
        when {
            ui.status.isActive -> {
                // Instant feedback; the service's state-change push refines it.
                applyPresentation(presentTile(PupilUiState(status = BroadcastStatus.Stopped)))
                stopService(Intent(this, PupilService::class.java))
            }
            missingStartPermissions(this).isNotEmpty() -> {
                // Runtime permission dialogs cannot be shown from a tile; the app can.
                openApp()
            }
            else -> {
                applyPresentation(presentTile(PupilUiState(status = BroadcastStatus.Starting)))
                startBroadcasting()
            }
        }
    }

    private fun startBroadcasting() {
        // Reuse the persisted settings like the in-app start does; falling back to the
        // defaults would silently lose the user's interval/tx-power choices.
        val settings = runBlocking { SettingsRepository(this@PupilTileService).settings.first() }
        try {
            startForegroundService(PupilService.startIntent(this, settings))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // A tile tap is not a reliable exemption from foreground-service
            // background-start rules on Android 14+; the visible app is.
            openApp()
        }
    }

    private fun openApp() {
        // PendingIntent overload is mandatory for apps targeting API 34+; the Intent
        // overload throws. FLAG_ACTIVITY_NEW_TASK is required since API 28.
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }

    /** Valid both in onStartListening and onClick: the system holds the binding. */
    private fun applyPresentation(p: TilePresentation) {
        val tile = qsTile ?: return
        tile.state = p.tileState
        tile.subtitle = p.subtitle
        tile.contentDescription = p.contentDescription
        tile.updateTile()
    }
}
