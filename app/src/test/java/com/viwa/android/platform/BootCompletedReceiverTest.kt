package com.viwa.android.platform

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.viwa.android.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BootCompletedReceiverTest {
    @Test
    fun bootCompletedStartsMainActivity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        BootCompletedReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }
}
