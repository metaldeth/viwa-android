package com.viwa.android.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.viwa.android.platform.ViwaKioskSystemUi

/**
 * Скрывает status/navigation для **окна Compose Dialog / AlertDialog** (отдельное от [android.app.Activity]).
 * Вызвать в корне контента диалога (как можно раньше в дереве композиции).
 */
@Composable
fun DialogWindowImmersiveSideEffect() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        ViwaKioskSystemUi.hideSystemBars(window)
    }
}
