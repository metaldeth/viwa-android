package com.viwa.android.domain.catalog

import com.viwa.android.ui.screens.customer.ViwaElectronAssets

/** Allowlist из 17 tasteMediaKey — канон совпадает с [ViwaElectronAssets]. */
object TasteMediaKeyCatalog {
    data class TasteEntry(
        val mediaKey: String,
        val nameRu: String,
    )

    val ALL_KEYS: List<String> =
        listOf(
            "cherry",
            "blackberry-lime",
            "coconut",
            "cucumber",
            "grapefruit",
            "lemon",
            "lime",
            "lime-mint",
            "mint",
            "orange",
            "peach",
            "peach-mango",
            "pineapple",
            "pomegranate-blueberry",
            "raspberry",
            "strawberry-lemongrass",
            "watermelon",
        )

    private val entries: List<TasteEntry> =
        listOf(
            TasteEntry("cherry", "Чёрная вишня"),
            TasteEntry("blackberry-lime", "Ежевика-лайм"),
            TasteEntry("coconut", "Кокос"),
            TasteEntry("cucumber", "Огурец"),
            TasteEntry("grapefruit", "Грейпфрут"),
            TasteEntry("lemon", "Лимон"),
            TasteEntry("lime", "Лайм"),
            TasteEntry("lime-mint", "Лайм-мята"),
            TasteEntry("mint", "Мята"),
            TasteEntry("orange", "Апельсин"),
            TasteEntry("peach", "Персик"),
            TasteEntry("peach-mango", "Манго-персик"),
            TasteEntry("pineapple", "Ананас"),
            TasteEntry("pomegranate-blueberry", "Гранат-черника"),
            TasteEntry("raspberry", "Малина"),
            TasteEntry("strawberry-lemongrass", "Клубника-лемонграсс"),
            TasteEntry("watermelon", "Арбуз"),
        )

    private val byKey: Map<String, TasteEntry> = entries.associateBy { it.mediaKey }

    fun isValid(mediaKey: String): Boolean = mediaKey in byKey

    fun nameRu(mediaKey: String): String? = byKey[mediaKey]?.nameRu

    fun allEntries(): List<TasteEntry> = entries

    /** true, если для ключа есть PNG в assets (horizontalCard). */
    fun hasAssetMapping(mediaKey: String): Boolean =
        isValid(mediaKey) && ViwaElectronAssets.horizontalCardImageUri(mediaKey) != null
}
