package com.viwa.android.ui.screens.customer

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Тень карточки через [graphicsLayer] — один RenderNode вместо bitmap-кэша [Modifier.shadow] в списках.
 */
internal fun Modifier.viwaCardShadow(elevation: Dp, shape: Shape): Modifier =
    this.graphicsLayer {
        shadowElevation = elevation.toPx()
        this.shape = shape
        clip = false
    }
