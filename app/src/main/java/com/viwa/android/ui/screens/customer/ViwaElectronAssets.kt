package com.viwa.android.ui.screens.customer

/** Видео скринсейвера с id и подписью. */
data class IdleVideoItem(val id: String, val label: String) {
    val fileName: String get() = "$id.mp4"
}

/** Все 14 видео скринсейвера. */
val IDLE_VIDEOS: List<IdleVideoItem> = listOf(
    IdleVideoItem("blackberryLime", "Ежевика-лайм"),
    IdleVideoItem("blackCherry", "Чёрная вишня"),
    IdleVideoItem("coconut", "Кокос"),
    IdleVideoItem("cucumber", "Огурец"),
    IdleVideoItem("grapefruit", "Грейпфрут"),
    IdleVideoItem("lemon", "Лимон"),
    IdleVideoItem("lime", "Лайм"),
    IdleVideoItem("limeMint", "Лайм-мята"),
    IdleVideoItem("mangoPeach", "Манго-персик"),
    IdleVideoItem("orange", "Апельсин"),
    IdleVideoItem("pomegranateBlueberry", "Гранат-черника"),
    IdleVideoItem("raspberry", "Малина"),
    IdleVideoItem("strawberryLemongrass", "Клубника-лемонграсс"),
    IdleVideoItem("watermelon", "Арбуз"),
)

val IDLE_VIDEO_IDS_ALL: List<String> = IDLE_VIDEOS.map { it.id }

object ViwaElectronAssets {
    const val ASSET_URI_PREFIX = "file:///android_asset/viwa_electron"

    /** Все 14 видео для фонового idle-скринсейвера. */
    val IDLE_VIDEO_FILES: List<String> =
        listOf(
            "blackberryLime.mp4",
            "blackCherry.mp4",
            "coconut.mp4",
            "cucumber.mp4",
            "grapefruit.mp4",
            "lemon.mp4",
            "lime.mp4",
            "limeMint.mp4",
            "mangoPeach.mp4",
            "orange.mp4",
            "pomegranateBlueberry.mp4",
            "raspberry.mp4",
            "strawberryLemongrass.mp4",
            "watermelon.mp4",
        )

    /** Маппинг mediaKey вкуса → имя mp4-файла в assets. */
    private val MEDIA_KEY_TO_VIDEO: Map<String, String> =
        mapOf(
            "cherry" to "blackCherry.mp4",
            "blackberry-lime" to "blackberryLime.mp4",
            "coconut" to "coconut.mp4",
            "cucumber" to "cucumber.mp4",
            "grapefruit" to "grapefruit.mp4",
            "lemon" to "lemon.mp4",
            "lime" to "lime.mp4",
            "lime-mint" to "limeMint.mp4",
            "orange" to "orange.mp4",
            "peach-mango" to "mangoPeach.mp4",
            "pomegranate-blueberry" to "pomegranateBlueberry.mp4",
            "raspberry" to "raspberry.mp4",
            "strawberry-lemongrass" to "strawberryLemongrass.mp4",
            "watermelon" to "watermelon.mp4",
        )

    /** Возвращает URI видео для экрана готовки по mediaKey вкуса. */
    fun preparingVideoUri(mediaKey: String?): android.net.Uri? {
        val file = mediaKey?.let { MEDIA_KEY_TO_VIDEO[it] } ?: return null
        return android.net.Uri.parse("$ASSET_URI_PREFIX/video/$file")
    }

    private val MEDIA_KEY_TO_PNG: Map<String, String> =
        mapOf(
            "cherry" to "cherry.png",
            "blackberry-lime" to "blackberry-lime.png",
            "coconut" to "coconut.png",
            "cucumber" to "cucumber.png",
            "grapefruit" to "grapefruit.png",
            "lemon" to "lemon.png",
            "lime" to "lime.png",
            "lime-mint" to "lime-mint.png",
            "orange" to "orange.png",
            "peach-mango" to "peach-mango.png",
            "pomegranate-blueberry" to "pomegranate-blueberry.png",
            "raspberry" to "raspberry.png",
            "strawberry-lemongrass" to "strawberry-lemongrass.png",
            "watermelon" to "watermelon.png",
        )

    fun horizontalCardImageUri(mediaKey: String?): String? {
        val file = mediaKey?.let { MEDIA_KEY_TO_PNG[it] } ?: return null
        return "$ASSET_URI_PREFIX/img/horizontalCard/$file"
    }

    /** Unit-test friendly: есть mp4 в assets для mediaKey (без [android.net.Uri]). */
    fun hasPreparingVideoAsset(mediaKey: String?): Boolean =
        mediaKey != null && MEDIA_KEY_TO_VIDEO.containsKey(mediaKey)
}
