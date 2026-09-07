package io.github.genneth.pupil

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePresentationTest {
    @Test
    fun stoppedMapsToInactive() {
        val p = presentTile(PupilUiState(status = BroadcastStatus.Stopped))
        assertEquals(Tile.STATE_INACTIVE, p.tileState)
        assertEquals("Off", p.subtitle)
    }

    @Test
    fun startingAndBroadcastingMapToActive() {
        val starting = presentTile(PupilUiState(status = BroadcastStatus.Starting))
        val broadcasting = presentTile(PupilUiState(status = BroadcastStatus.Broadcasting("wakeup ALS")))
        assertEquals(Tile.STATE_ACTIVE, starting.tileState)
        assertEquals(Tile.STATE_ACTIVE, broadcasting.tileState)
    }

    @Test
    fun failedMapsToUnavailableWithDetails() {
        val p = presentTile(PupilUiState(status = BroadcastStatus.Failed("Bluetooth is off")))
        assertEquals(Tile.STATE_UNAVAILABLE, p.tileState)
        assertEquals("Unavailable", p.subtitle)
        assertTrue(p.contentDescription.contains("Bluetooth is off"))
    }
}
