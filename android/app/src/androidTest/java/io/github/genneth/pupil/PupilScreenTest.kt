package io.github.genneth.pupil

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PupilScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsAreEditableOnlyWhenStopped() {
        var ui by mutableStateOf(PupilUiState())
        compose.setContent { screen(ui) }
        compose.onNodeWithTag("setting-interval").assertIsEnabled()

        compose.runOnIdle {
            ui = PupilUiState(status = BroadcastStatus.Broadcasting("test sensor"))
        }
        compose.onNodeWithTag("setting-interval").assertIsNotEnabled()
        compose.onNodeWithTag("settings-lock").assertExists()
        compose.onNodeWithText("next start", substring = true).assertExists()
    }

    @Test
    fun primaryControlReportsExactlyOneIntent() {
        val toggles = AtomicInteger()
        compose.setContent { screen(PupilUiState(), onToggle = { toggles.incrementAndGet() }) }

        compose.onNodeWithTag("broadcast-toggle").performClick()

        compose.runOnIdle { assertEquals(1, toggles.get()) }
    }

    @Composable
    private fun screen(ui: PupilUiState, onToggle: () -> Unit = {}) {
        PupilTheme(darkTheme = false, dynamicColor = false) {
            PupilScreen(
                ui = ui,
                settings = PupilSettings(),
                layout = PupilLayout.FOLDED,
                sensorReport = "test sensor",
                batteryExempt = true,
                onToggle = onToggle,
                onBattery = {},
                onInterval = {},
                onTxPower = {},
                onDeadband = {},
                onHeartbeat = {},
            )
        }
    }
}
