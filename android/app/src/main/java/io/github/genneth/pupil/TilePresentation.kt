package io.github.genneth.pupil

import android.service.quicksettings.Tile

/**
 * What the Quick Settings tile should show for a given app state. Kept pure so the
 * mapping (and the Tile.* constants it inlines) is unit-testable without a device.
 */
data class TilePresentation(
    val tileState: Int,
    val subtitle: String,
    val contentDescription: String,
)

fun presentTile(state: PupilUiState): TilePresentation = when (val status = state.status) {
    is BroadcastStatus.Stopped -> TilePresentation(
        tileState = Tile.STATE_INACTIVE,
        subtitle = "Off",
        contentDescription = "Pupil is off. Tap to start broadcasting.",
    )
    is BroadcastStatus.Starting -> TilePresentation(
        tileState = Tile.STATE_ACTIVE,
        subtitle = "Starting…",
        contentDescription = "Pupil is starting broadcasting.",
    )
    is BroadcastStatus.Broadcasting -> TilePresentation(
        tileState = Tile.STATE_ACTIVE,
        subtitle = "Broadcasting",
        contentDescription = "Pupil is broadcasting. Tap to stop.",
    )
    is BroadcastStatus.Failed -> TilePresentation(
        // UNAVAILABLE tiles ignore taps; long-press still opens the app, which is the
        // settings surface where the failure is explained and recovery starts.
        tileState = Tile.STATE_UNAVAILABLE,
        subtitle = "Unavailable",
        contentDescription = "Pupil is unavailable: ${status.message}. Long-press to open the app.",
    )
}
