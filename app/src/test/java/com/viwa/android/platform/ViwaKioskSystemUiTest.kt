package com.viwa.android.platform

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViwaKioskSystemUiTest {
    @Test
    fun legacyImmersiveFlags_includeStickyHideNavigationAndLowProfile() {
        val flags = ViwaKioskSystemUi.legacyImmersiveFlags()
        assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertTrue(flags and View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
    }

    @Test
    fun customerKioskPolicyValue_usesImmersiveFullForPackage() {
        assertEquals(
            "immersive.full=com.viwa.android",
            ViwaSystemUiPolicy.customerKioskPolicyValue("com.viwa.android"),
        )
    }
}
