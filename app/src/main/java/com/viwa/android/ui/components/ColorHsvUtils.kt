package com.viwa.android.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color

data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

fun argbToHsv(argb: Int): HsvColor {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    return HsvColor(hue = hsv[0], saturation = hsv[1], value = hsv[2])
}

fun hsvToArgb(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Int = 0xFF,
): Int = AndroidColor.HSVToColor(alpha, floatArrayOf(hue, saturation, value))

fun rgbHexToColor(hex: String): Color {
    val normalized = hex.trim().removePrefix("#")
    return Color(0xFF000000L or normalized.toLong(16))
}

fun colorToRgbHex(color: Color): String {
    val rgb = composeColorToArgb(color) and 0xFFFFFF
    return "#%06X".format(rgb)
}

fun argbToComposeColor(argb: Int): Color {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return Color(r, g, b, a)
}

fun composeColorToArgb(color: Color): Int {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    val a = (color.alpha * 255).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
