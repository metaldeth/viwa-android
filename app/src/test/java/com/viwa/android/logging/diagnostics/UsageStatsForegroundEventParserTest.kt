package com.viwa.android.logging.diagnostics

import android.app.usage.UsageEvents
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageStatsForegroundEventParserTest {
    private companion object {
        const val OWN_PACKAGE = "com.viwa.android"
    }

    @Test
    fun api30_picksLatestActivityResumed() {
        val events =
            listOf(
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    timeStamp = 1_000L,
                ),
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = "com.oem.video",
                    className = "com.oem.video.PlayerActivity",
                    timeStamp = 2_000L,
                ),
            )

        val latest =
            UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                events = events,
                sdkInt = Build.VERSION_CODES.R,
                sinceMs = 0L,
                ownPackage = OWN_PACKAGE,
                externalOnly = true,
            )

        assertEquals("com.oem.video", latest!!.packageName)
        assertEquals("com.oem.video.PlayerActivity", latest.className)
        assertEquals(2_000L, latest.observedAtMs)
    }

    @Test
    fun api25_picksLatestMoveToForeground() {
        val events =
            listOf(
                RawUsageEvent(
                    eventType = 1,
                    packageName = "com.oem.gallery",
                    className = "com.oem.gallery.HomeActivity",
                    timeStamp = 300L,
                ),
                RawUsageEvent(
                    eventType = 1,
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    timeStamp = 500L,
                ),
            )

        val latest =
            UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                events = events,
                sdkInt = Build.VERSION_CODES.N_MR1,
                sinceMs = 0L,
                ownPackage = OWN_PACKAGE,
                externalOnly = true,
            )

        assertEquals("com.oem.video", latest!!.packageName)
        assertEquals(500L, latest.observedAtMs)
    }

    @Test
    fun externalOnly_ignoresOnlyOwnPackage() {
        val events =
            listOf(
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = OWN_PACKAGE,
                    className = "com.viwa.android.ui.MainActivity",
                    timeStamp = 3_000L,
                ),
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = "com.android.settings",
                    className = "com.android.settings.homepage.SettingsHomepageActivity",
                    timeStamp = 4_000L,
                ),
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = "com.oem.video",
                    className = "com.oem.video.ListActivity",
                    timeStamp = 1_000L,
                ),
            )

        val latest =
            UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                events = events,
                sdkInt = Build.VERSION_CODES.R,
                sinceMs = 0L,
                ownPackage = OWN_PACKAGE,
                externalOnly = true,
            )

        assertEquals("com.android.settings", latest!!.packageName)
        assertEquals("com.android.settings.homepage.SettingsHomepageActivity", latest.className)
    }

    @Test
    fun externalOnly_acceptsSettingsSystemUiAndDocumentsUi() {
        val cases =
            listOf(
                "com.android.settings" to "com.android.settings.homepage.SettingsHomepageActivity",
                "com.android.systemui" to "com.android.systemui.recents.RecentsActivity",
                "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
            )
        for ((pkg, cls) in cases) {
            val latest =
                UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                    events =
                        listOf(
                            RawUsageEvent(
                                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                                packageName = pkg,
                                className = cls,
                                timeStamp = 1_000L,
                            ),
                        ),
                    sdkInt = Build.VERSION_CODES.R,
                    sinceMs = 0L,
                    ownPackage = OWN_PACKAGE,
                    externalOnly = true,
                )
            assertEquals(pkg, latest!!.packageName)
            assertEquals(cls, latest.className)
        }
    }

    @Test
    fun sinceMs_filtersOlderEvents() {
        val events =
            listOf(
                RawUsageEvent(
                    eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                    packageName = "com.oem.video",
                    className = "com.oem.video.OldActivity",
                    timeStamp = 100L,
                ),
            )

        assertNull(
            UsageStatsForegroundEventParser.pickLatestForegroundEvent(
                events = events,
                sdkInt = Build.VERSION_CODES.R,
                sinceMs = 200L,
                ownPackage = OWN_PACKAGE,
                externalOnly = true,
            ),
        )
    }

    @Test
    fun sanitizeClass_returnsUnknownWhenMissing() {
        assertEquals(
            UsageStatsForegroundEventParser.UNKNOWN_CLASS,
            UsageStatsForegroundEventParser.sanitizeClass(null),
        )
    }

    @Test
    fun isOwnPackage_rejectsOnlyViwaPackage() {
        assertTrue(UsageStatsForegroundEventParser.isOwnPackage(OWN_PACKAGE, OWN_PACKAGE))
        assertFalse(
            UsageStatsForegroundEventParser.isOwnPackage(
                "com.android.settings",
                OWN_PACKAGE,
            ),
        )
        assertFalse(
            UsageStatsForegroundEventParser.isOwnPackage(
                "com.android.systemui",
                OWN_PACKAGE,
            ),
        )
        assertFalse(
            UsageStatsForegroundEventParser.isOwnPackage(
                "com.oem.video",
                OWN_PACKAGE,
            ),
        )
    }
}
