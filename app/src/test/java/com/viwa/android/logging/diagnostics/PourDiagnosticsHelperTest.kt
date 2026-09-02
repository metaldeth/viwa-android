package com.viwa.android.logging.diagnostics

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Test

class PourDiagnosticsHelperTest {
    @Test
    fun end_emitsTerminalMarkerOnce() {
        val breadcrumbStore = mockk<DiagnosticBreadcrumbStore>(relaxUnitFun = true)
        val helper = PourDiagnosticsHelper(breadcrumbStore)
        val pourId = helper.beginPour()

        helper.commandDone(pourId)
        helper.end(pourId, "pointer_up")
        helper.end(pourId, "pointer_up")

        verify(atLeast = 1) { breadcrumbStore.recordPour(pourId, "end:pointer_up") }
        verify(exactly = 0) { breadcrumbStore.recordPour(pourId, "stop") }
        assertNull(helper.activePourIdOrNull())
    }
}
