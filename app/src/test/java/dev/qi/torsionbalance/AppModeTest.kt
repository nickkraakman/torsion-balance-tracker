package dev.qi.torsionbalance

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeTest {

    @Test
    fun onlyRecordingKeepsScreenOn() {
        assertEquals(
            listOf(AppMode.RECORDING),
            AppMode.entries.filter { it.keepsScreenOn },
        )
    }
}
