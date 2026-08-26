package com.viwa.android.ui.navigation

import androidx.navigation.NavHostController
import com.viwa.android.logging.ScreenStateLogger

enum class HomeNavAction {
    AlreadyHome,
    PopToHome,
    NavigateHome,
}

/** Что делать, чтобы снова оказаться на Home и не снять стартовый экран. */
fun resolveReturnToHome(
    currentRoute: String?,
    homeOnStack: Boolean,
): HomeNavAction =
    when {
        currentRoute == Routes.Home -> HomeNavAction.AlreadyHome
        homeOnStack -> HomeNavAction.PopToHome
        else -> HomeNavAction.NavigateHome
    }

fun NavHostController.returnToHome(reason: String) {
    val from = currentDestination?.route
    if (from == Routes.Home) {
        ScreenStateLogger.action("nav.returnHome already=home reason=$reason")
        return
    }
    val popped = popBackStack(Routes.Home, inclusive = false)
    val now = currentDestination?.route
    if (now == Routes.Home) {
        ScreenStateLogger.action("nav.returnHome from=$from popped=$popped reason=$reason")
        return
    }
    ScreenStateLogger.black("nav.empty_or_no_home", "from=$from now=$now popped=$popped reason=$reason")
    navigate(Routes.Home) { launchSingleTop = true }
}
