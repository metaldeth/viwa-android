package com.viwa.android.domain.catalog

import com.viwa.android.ui.screens.customer.ViwaElectronAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteMediaKeyCatalogTest {

    @Test
    fun allKeys_shouldContainExactly17AllowlistedKeys() {
        assertEquals(17, TasteMediaKeyCatalog.ALL_KEYS.size)
        assertEquals(TasteMediaKeyCatalog.ALL_KEYS.toSet().size, 17)
    }

    @Test
    fun eachKey_shouldMapToKnownAssetAndHaveRuName() {
        for (key in TasteMediaKeyCatalog.ALL_KEYS) {
            assertTrue("missing asset for $key", TasteMediaKeyCatalog.hasAssetMapping(key))
            assertTrue("missing RU name for $key", !TasteMediaKeyCatalog.nameRu(key).isNullOrBlank())
            assertTrue(TasteMediaKeyCatalog.isValid(key))
        }
    }

    @Test
    fun isValid_shouldRejectUnknownKey() {
        assertTrue(!TasteMediaKeyCatalog.isValid("unknown-taste"))
    }

    @Test
    fun pngOnlyKeys_shouldHavePreparingVideoFallback() {
        val pngOnlyKeys = listOf("peach", "mint", "pineapple")
        for (key in pngOnlyKeys) {
            assertTrue(TasteMediaKeyCatalog.hasAssetMapping(key))
            assertNotNull("fallback preparing video for $key", ViwaElectronAssets.preparingVideoFileName(key))
            assertTrue(ViwaElectronAssets.hasPreparingVideoAsset(key))
        }
    }
}
